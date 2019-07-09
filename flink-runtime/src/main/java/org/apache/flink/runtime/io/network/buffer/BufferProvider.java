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

package org.apache.flink.runtime.io.network.buffer;

import java.io.IOException;

/**
 * A buffer provider to request buffers from in a synchronous or asynchronous fashion.
 *
 * 可以以同步或异步的方式 请求buffer
 *
 * <p>The data producing side (result partition writers) request buffers in a synchronous fashion,
 * whereas the input side requests asynchronously.
 *
 * 数据生产端(结果分区writer)以同步方式请求buffer,
 * 但输入端则是以异步方式请求获取buffer
 */
public interface BufferProvider {

	/**
	 * Returns a {@link Buffer} instance from the buffer provider, if one is available.
	 *
	 * 如果有可用的buffer, 直接从provider返回一个buffer实例
	 *
	 * <p>Returns <code>null</code> if no buffer is available or the buffer provider has been destroyed.
	 *
	 * 如果没有buffer可用或者provider已经被销毁了, 则返回nul
	 */
	Buffer requestBuffer() throws IOException;

	/**
	 * Returns a {@link Buffer} instance from the buffer provider.
	 *
	 * 从provider返回一个buffer实例
	 *
	 * <p>If there is no buffer available, the call will block until one becomes available again or the
	 * buffer provider has been destroyed.
	 *
	 * 如果没有可用的buffer, 调用会被block, 直到有一个可用或者provider被销毁
	 */
	Buffer requestBufferBlocking() throws IOException, InterruptedException;

	/**
	 * Returns a {@link BufferBuilder} instance from the buffer provider.
	 *
	 * 从provider返回一个BufferBuilder实例
	 *
	 * <p>If there is no buffer available, the call will block until one becomes available again or the
	 * buffer provider has been destroyed.
	 *
	 * 如果没有可用的buffer, 调用会被block, 直到有一个可用或者provider被销毁
	 */
	BufferBuilder requestBufferBuilderBlocking() throws IOException, InterruptedException;

	/**
	 * Adds a buffer availability listener to the buffer provider.
	 *
	 * 添加buffer listener至provider
	 *
	 * <p>The operation fails with return value <code>false</code>, when there is a buffer available or
	 * the buffer provider has been destroyed.
	 *
	 * 当有一个buffer可用或者provider已经被销毁时, 调用失败返回false
	 */
	boolean addBufferListener(BufferListener listener);

	/**
	 * Returns whether the buffer provider has been destroyed.
	 *
	 * 判断provider是否已经被销毁
	 */
	boolean isDestroyed();

	/**
	 * Returns the size of the underlying memory segments. This is the maximum size a {@link Buffer}
	 * instance can have.
	 *
	 * 返回底层memory segments的大小。这是一个buffer实例可以拥有的最大size
	 */
	int getMemorySegmentSize();

}
