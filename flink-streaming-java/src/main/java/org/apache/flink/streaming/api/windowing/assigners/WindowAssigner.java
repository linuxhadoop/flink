/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.windowing.assigners;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.triggers.Trigger;
import org.apache.flink.streaming.api.windowing.windows.Window;

import java.io.Serializable;
import java.util.Collection;

/**
 * A {@code WindowAssigner} assigns zero or more {@link Window Windows} to an element.
 *
 * WindowAssigner分配0个或多个Window给一个元素
 *
 * <p>In a window operation, elements are grouped by their key (if available) and by the windows to
 * which it was assigned. The set of elements with the same key and window is called a pane.
 * When a {@link Trigger} decides that a certain pane should fire the
 * {@link org.apache.flink.streaming.api.functions.windowing.WindowFunction} is applied
 * to produce output elements for that pane.
 *
 * 在一个window操作中, 元素通过他们的key被组织在一起????
 *
 * 相同key的元素集合与window 被称为一个pane。
 * 当trigger决定一个pane应该fire WindowFunction, 它被用来为该pane输出元素???
 *
 * @param <T> The type of elements that this WindowAssigner can assign windows to.
 * @param <W> The type of {@code Window} that this assigner assigns.
 */
@PublicEvolving
public abstract class WindowAssigner<T, W extends Window> implements Serializable {
	private static final long serialVersionUID = 1L;

	/**
	 * Returns a {@code Collection} of windows that should be assigned to the element.
	 *
	 * 返回一个为元素分配的window的集合
	 *
	 * @param element The element to which windows should be assigned.
	 * @param timestamp The timestamp of the element.
	 * @param context The {@link WindowAssignerContext} in which the assigner operates.
	 */
	public abstract Collection<W> assignWindows(T element, long timestamp, WindowAssignerContext context);

	/**
	 * Returns the default trigger associated with this {@code WindowAssigner}.
	 *
	 * 返回与当前windowAssigner关联的默认trigger
	 */
	public abstract Trigger<T, W> getDefaultTrigger(StreamExecutionEnvironment env);

	/**
	 * Returns a {@link TypeSerializer} for serializing windows that are assigned by
	 * this {@code WindowAssigner}.
	 *
	 * 返回一个类型序列化器, 用来序列化分配给当前WindowAssigner的window
	 */
	public abstract TypeSerializer<W> getWindowSerializer(ExecutionConfig executionConfig);

	/**
	 * Returns {@code true} if elements are assigned to windows based on event time,
	 * {@code false} otherwise.
	 *
	 * 元素是否被分配给基于event时间的window
	 */
	public abstract boolean isEventTime();

	/**
	 * A context provided to the {@link WindowAssigner} that allows it to query the
	 * current processing time.
	 *
	 * windowAssigner上线文
	 *
	 * <p>This is provided to the assigner by its containing
	 * {@link org.apache.flink.streaming.runtime.operators.windowing.WindowOperator},
	 * which, in turn, gets it from the containing
	 * {@link org.apache.flink.streaming.runtime.tasks.StreamTask}.
	 */
	public abstract static class WindowAssignerContext {

		/**
		 * Returns the current processing time.
		 */
		public abstract long getCurrentProcessingTime();

	}
}
