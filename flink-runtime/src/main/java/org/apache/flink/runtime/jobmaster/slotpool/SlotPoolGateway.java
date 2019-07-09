/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.jobmaster.slotpool;

import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotProfile;
import org.apache.flink.runtime.instance.SlotSharingGroupId;
import org.apache.flink.runtime.jobmanager.scheduler.ScheduledUnit;
import org.apache.flink.runtime.jobmanager.slots.TaskManagerGateway;
import org.apache.flink.runtime.jobmaster.AllocatedSlotReport;
import org.apache.flink.runtime.jobmaster.LogicalSlot;
import org.apache.flink.runtime.jobmaster.SlotRequestId;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.resourcemanager.ResourceManagerGateway;
import org.apache.flink.runtime.rpc.RpcGateway;
import org.apache.flink.runtime.rpc.RpcTimeout;
import org.apache.flink.runtime.taskexecutor.slot.SlotOffer;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.types.SerializableOptional;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * The gateway for calls on the {@link SlotPool}.
 *
 * slotPoll网关接口
 */
public interface SlotPoolGateway extends AllocatedSlotActions, RpcGateway {

	// ------------------------------------------------------------------------
	//  shutdown
	// ------------------------------------------------------------------------

	void suspend();

	// ------------------------------------------------------------------------
	//  resource manager connection 资源管理器连接
	// ------------------------------------------------------------------------

	/**
	 * Connects the SlotPool to the given ResourceManager. After this method is called, the
	 * SlotPool will be able to request resources from the given ResourceManager.
	 *
	 * 将slotPool连接到资源管理器
	 * 该方法被调用后, SlotPool 可以请求从资源管理器中请求资源
	 *
	 * @param resourceManagerGateway  The RPC gateway for the resource manager.
	 */
	void connectToResourceManager(ResourceManagerGateway resourceManagerGateway);

	/**
	 * Disconnects the slot pool from its current Resource Manager. After this call, the pool will not
	 * be able to request further slots from the Resource Manager, and all currently pending requests
	 * to the resource manager will be canceled.
	 *
	 * 断开slotPool与资源管理器之间的连接
	 * 断开后, 无法请求资源, 并且pending状态的请求也会被canceled
	 *
	 * <p>The slot pool will still be able to serve slots from its internal pool.
	 */
	void disconnectResourceManager();

	// ------------------------------------------------------------------------
	//  registering / un-registering TaskManagers and slots
	// ------------------------------------------------------------------------

	/**
	 * Registers a TaskExecutor with the given {@link ResourceID} at {@link SlotPool}.
	 *
	 * 在slotPool中注册一个TaskExecutor
	 *
	 * @param resourceID identifying the TaskExecutor to register
	 * @return Future acknowledge which is completed after the TaskExecutor has been registered
	 */
	CompletableFuture<Acknowledge> registerTaskManager(ResourceID resourceID);

	/**
	 * Releases a TaskExecutor with the given {@link ResourceID} from the {@link SlotPool}.
	 *
	 * 在slotPool中释放一个已经注册的TaskExecutor
	 *
	 * @param resourceId identifying the TaskExecutor which shall be released from the SlotPool
	 * @param cause for the releasing of the TaskManager
	 * @return Future acknowledge which is completed after the TaskExecutor has been released
	 */
	CompletableFuture<Acknowledge> releaseTaskManager(final ResourceID resourceId, final Exception cause);

	/**
	 * Offers a slot to the {@link SlotPool}. The slot offer can be accepted or
	 * rejected.
	 *
	 * 提供一个slot至slotPool
	 * slot可以被接受或拒绝
	 *
	 * @param taskManagerLocation from which the slot offer originates
	 * @param taskManagerGateway to talk to the slot offerer
	 * @param slotOffer slot which is offered to the {@link SlotPool}
	 * @return True (future) if the slot has been accepted, otherwise false (future)
	 */
	CompletableFuture<Boolean> offerSlot(
		TaskManagerLocation taskManagerLocation,
		TaskManagerGateway taskManagerGateway,
		SlotOffer slotOffer);

	/**
	 * Offers multiple slots to the {@link SlotPool}. The slot offerings can be
	 * individually accepted or rejected by returning the collection of accepted
	 * slot offers.
	 *
	 * @param taskManagerLocation from which the slot offers originate
	 * @param taskManagerGateway to talk to the slot offerer
	 * @param offers slot offers which are offered to the {@link SlotPool}
	 * @return A collection of accepted slot offers (future). The remaining slot offers are
	 * 			implicitly rejected.
	 */
	CompletableFuture<Collection<SlotOffer>> offerSlots(
		TaskManagerLocation taskManagerLocation,
		TaskManagerGateway taskManagerGateway,
		Collection<SlotOffer> offers);

	/**
	 * Fails the slot with the given allocation id.
	 *
	 * @param allocationID identifying the slot which is being failed
	 * @param cause of the failure
	 * @return An optional task executor id if this task executor has no more slots registered
	 */
	CompletableFuture<SerializableOptional<ResourceID>> failAllocation(AllocationID allocationID, Exception cause);

	// ------------------------------------------------------------------------
	//  allocating and disposing slots
	// ------------------------------------------------------------------------

	/**
	 * Requests to allocate a slot for the given {@link ScheduledUnit}. The request
	 * is uniquely identified by the provided {@link SlotRequestId} which can also
	 * be used to release the slot via {@link #releaseSlot(SlotRequestId, SlotSharingGroupId, Throwable)}.
	 * The allocated slot will fulfill the requested {@link ResourceProfile} and it
	 * is tried to place it on one of the location preferences.
	 *
	 * <p>If the returned future must not be completed right away (a.k.a. the slot request
	 * can be queued), allowQueuedScheduling must be set to true.
	 *
	 * @param slotRequestId identifying the requested slot
	 * @param scheduledUnit for which to allocate slot
	 * @param slotProfile profile that specifies the requirements for the requested slot
	 * @param allowQueuedScheduling true if the slot request can be queued (e.g. the returned future must not be completed)
	 * @param timeout for the operation
	 * @return Future which is completed with the allocated {@link LogicalSlot}
	 */
	CompletableFuture<LogicalSlot> allocateSlot(
			SlotRequestId slotRequestId,
			ScheduledUnit scheduledUnit,
			SlotProfile slotProfile,
			boolean allowQueuedScheduling,
			@RpcTimeout Time timeout);

	// ------------------------------------------------------------------------
	//  misc
	// ------------------------------------------------------------------------

	/**
	 * Create report about the allocated slots belonging to the specified task manager.
	 *
	 * @param taskManagerId identifies the task manager
	 * @return the allocated slots on the task manager
	 */
	CompletableFuture<AllocatedSlotReport> createAllocatedSlotReport(ResourceID taskManagerId);
}
