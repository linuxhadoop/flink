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

package org.apache.flink.runtime.io.network.api.reader;

import org.apache.flink.runtime.event.TaskEvent;
import org.apache.flink.runtime.util.event.EventListener;

import java.io.IOException;

/**
 * The basic API for every reader.
 *
 * 每个reader的基本操作API
 */
public interface ReaderBase {

	/**
	 * Returns whether the reader has consumed the input.
	 *
	 * 判断reader是否已经消费了input
	 */
	boolean isFinished();

	// ------------------------------------------------------------------------
	// Task events
	// ------------------------------------------------------------------------

	// 发送taskEvent
	void sendTaskEvent(TaskEvent event) throws IOException;

	// 注册TaskEventListener
	void registerTaskEventListener(EventListener<TaskEvent> listener, Class<? extends TaskEvent> eventType);

	// ------------------------------------------------------------------------
	// Iterations
	// ------------------------------------------------------------------------

	// 设置可迭代的reader
	void setIterativeReader();

	void startNextSuperstep();

	boolean hasReachedEndOfSuperstep();

}
