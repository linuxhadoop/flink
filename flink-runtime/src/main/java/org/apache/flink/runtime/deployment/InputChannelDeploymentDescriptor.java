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

package org.apache.flink.runtime.deployment;

import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.executiongraph.Execution;
import org.apache.flink.runtime.executiongraph.ExecutionEdge;
import org.apache.flink.runtime.executiongraph.ExecutionGraphException;
import org.apache.flink.runtime.executiongraph.IntermediateResultPartition;
import org.apache.flink.runtime.io.network.ConnectionID;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGate;
import org.apache.flink.runtime.jobmaster.LogicalSlot;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;

import java.io.Serializable;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Deployment descriptor for a single input channel instance.
 *
 * 一个单独input channel实例的部署描述符
 *
 * <p>Each input channel consumes a single subpartition. The index of the subpartition to consume
 * is part of the {@link InputGateDeploymentDescriptor} as it is the same for each input channel of
 * the respective input gate.
 *
 * 每一个input channel消费一个子分区
 *
 * @see InputChannel
 * @see SingleInputGate
 */
public class InputChannelDeploymentDescriptor implements Serializable {

	private static final long serialVersionUID = 373711381640454080L;

	/**
	 * The ID of the partition the input channel is going to consume.
	 *
	 * input channel将要消费的partition的ID
	 * */
	private final ResultPartitionID consumedPartitionId;

	/**
	 * The location of the partition the input channel is going to consume.
	 *
	 * 将要消费的partition的位置。 就是去哪里找这个分区
	 * */
	private final ResultPartitionLocation consumedPartitionLocation;

	public InputChannelDeploymentDescriptor(
			ResultPartitionID consumedPartitionId,
			ResultPartitionLocation consumedPartitionLocation) {

		this.consumedPartitionId = checkNotNull(consumedPartitionId);
		this.consumedPartitionLocation = checkNotNull(consumedPartitionLocation);
	}

	public ResultPartitionID getConsumedPartitionId() {
		return consumedPartitionId;
	}

	public ResultPartitionLocation getConsumedPartitionLocation() {
		return consumedPartitionLocation;
	}

	@Override
	public String toString() {
		return String.format("InputChannelDeploymentDescriptor [consumed partition id: %s, " +
						"consumed partition location: %s]",
				consumedPartitionId, consumedPartitionLocation);
	}

	// ------------------------------------------------------------------------

	/**
	 * Creates an input channel deployment descriptor for each partition.
	 *
	 * 为每一个分区创建一个input channel部署描述符
	 */
	public static InputChannelDeploymentDescriptor[] fromEdges(
			ExecutionEdge[] edges,
			ResourceID consumerResourceId,
			boolean allowLazyDeployment) throws ExecutionGraphException {

		final InputChannelDeploymentDescriptor[] icdd = new InputChannelDeploymentDescriptor[edges.length];

		// Each edge is connected to a different result partition 每一条边连接到不同的结果分区
		for (int i = 0; i < edges.length; i++) {
			// edge的源头, 表示需要消费的分区数据
			final IntermediateResultPartition consumedPartition = edges[i].getSource();

			// 数据的生产者
			final Execution producer = consumedPartition.getProducer().getCurrentExecutionAttempt();

			// 生产者的状态
			final ExecutionState producerState = producer.getState();

			// 生产者分配的资源
			final LogicalSlot producerSlot = producer.getAssignedResource();

			// 分区数据所在的位置
			final ResultPartitionLocation partitionLocation;

			// The producing task needs to be RUNNING or already FINISHED
			// 生产者的任务需要是RUNNING或者FINISHED状态
			// 当前分区数据是否可以消费, 自否已经分配资源...
			if (consumedPartition.isConsumable() && producerSlot != null &&
					(producerState == ExecutionState.RUNNING ||
						producerState == ExecutionState.FINISHED ||
						producerState == ExecutionState.SCHEDULED ||
						producerState == ExecutionState.DEPLOYING)) {

				// 获取分区数据所在的taskManager位置
				final TaskManagerLocation partitionTaskManagerLocation = producerSlot.getTaskManagerLocation();
				final ResourceID partitionTaskManager = partitionTaskManagerLocation.getResourceID();

				// 两者相等,说明在同一台机器上。 LocationType为LOCAL
				if (partitionTaskManager.equals(consumerResourceId)) {
					// Consuming task is deployed to the same TaskManager as the partition => local
					partitionLocation = ResultPartitionLocation.createLocal();
				}
				else {
					// Different instances => remote 否则创建远程类型的ResultPartitionLocation
					final ConnectionID connectionId = new ConnectionID(
							partitionTaskManagerLocation,
							consumedPartition.getIntermediateResult().getConnectionIndex());

					partitionLocation = ResultPartitionLocation.createRemote(connectionId);
				}
			}
			else if (allowLazyDeployment) {
				// The producing task might not have registered the partition yet 生产任务或许还未在分区上进行注册
				partitionLocation = ResultPartitionLocation.createUnknown();
			}
			else if (producerState == ExecutionState.CANCELING
						|| producerState == ExecutionState.CANCELED
						|| producerState == ExecutionState.FAILED) {
				String msg = "Trying to schedule a task whose inputs were canceled or failed. " +
					"The producer is in state " + producerState + ".";
				throw new ExecutionGraphException(msg);
			}
			else {
				String msg = String.format("Trying to eagerly schedule a task whose inputs " +
					"are not ready (partition consumable? %s, producer state: %s, producer slot: %s).",
						consumedPartition.isConsumable(),
						producerState,
						producerSlot);
				throw new ExecutionGraphException(msg);
			}

			// ResultPartitionID = 要消费分区的ID + 生产该分区的子任务ID
			final ResultPartitionID consumedPartitionId = new ResultPartitionID(
					consumedPartition.getPartitionId(), producer.getAttemptId());

			// 生成InputChannel描述符(从哪个taskManager上消费哪个分区)
			icdd[i] = new InputChannelDeploymentDescriptor(
					consumedPartitionId, partitionLocation);
		}

		return icdd;
	}
}
