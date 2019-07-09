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

package org.apache.flink.runtime.io.network.api.serialization;

import org.apache.flink.core.io.IOReadableWritable;
import org.apache.flink.runtime.io.network.buffer.BufferBuilder;

import java.io.IOException;

/**
 * Interface for turning records into sequences of memory segments.
 *
 * 将记录转换为序列化的memory segments
 */
public interface RecordSerializer<T extends IOReadableWritable> {

	/**
	 * Status of the serialization result.
	 *
	 * 序列化结果的状态
	 */
	enum SerializationResult {
		PARTIAL_RECORD_MEMORY_SEGMENT_FULL(false, true),
		FULL_RECORD_MEMORY_SEGMENT_FULL(true, true),
		FULL_RECORD(true, false);

		private final boolean isFullRecord;

		private final boolean isFullBuffer;

		SerializationResult(boolean isFullRecord, boolean isFullBuffer) {
			this.isFullRecord = isFullRecord;
			this.isFullBuffer = isFullBuffer;
		}

		/**
		 * Whether the full record was serialized and completely written to
		 * a target buffer.
		 *
		 * 是否所有的记录已经被序列并且被全部写入目标buffer
		 *
		 * @return <tt>true</tt> if the complete record was written
		 */
		public boolean isFullRecord() {
			return this.isFullRecord;
		}

		/**
		 * Whether the target buffer is full after the serialization process.
		 *
		 * 序列化处理后, 目标buffer是否已满
		 *
		 * @return <tt>true</tt> if the target buffer is full
		 * 如果已满,返回true
		 */
		public boolean isFullBuffer() {
			return this.isFullBuffer;
		}
	}

	/**
	 * Starts serializing the given record to an intermediate data buffer.
	 *
	 * 开始序列化给定的记录, 将其写入至intermediate data buffer
	 *
	 * @param record the record to serialize
	 */
	void serializeRecord(T record) throws IOException;

	/**
	 * Copies the intermediate data serialization buffer to the given target buffer.
	 *
	 * 将序列化的intermediate data数据 复制至目标buffer
	 *
	 * @param bufferBuilder the new target buffer to use
	 * @return how much information was written to the target buffer and
	 *         whether this buffer is full
	 */
	SerializationResult copyToBufferBuilder(BufferBuilder bufferBuilder);

	/**
	 * Clears the buffer and checks to decrease the size of intermediate data serialization buffer
	 * after finishing the whole serialization process including
	 * {@link #serializeRecord(IOReadableWritable)} and {@link #copyToBufferBuilder(BufferBuilder)}.
	 *
	 * 清理buffer
	 */
	void prune();

	/**
	 * Supports copying an intermediate data serialization buffer to multiple target buffers
	 * by resetting its initial position before each copying.
	 *
	 * 在每个拷贝之前, 通过设置初始化位置,可以支持 将序列化的intermediate data数据 复制至多个目标buffer
	 */
	void reset();

	/**
	 * @return <tt>true</tt> if has some serialized data pending copying to the result {@link BufferBuilder}.
	 *
	 * 是否还有未拷贝至BufferBuilder的数据, 如果有, 则返回true
	 */
	boolean hasSerializedData();
}
