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

package org.apache.flink.runtime.io.network.netty;

import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.core.memory.MemorySegmentFactory;
import org.apache.flink.runtime.io.network.NetworkClientHandler;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferListener;
import org.apache.flink.runtime.io.network.buffer.BufferProvider;
import org.apache.flink.runtime.io.network.buffer.FreeingBufferRecycler;
import org.apache.flink.runtime.io.network.buffer.NetworkBuffer;
import org.apache.flink.runtime.io.network.netty.exception.LocalTransportException;
import org.apache.flink.runtime.io.network.netty.exception.RemoteTransportException;
import org.apache.flink.runtime.io.network.netty.exception.TransportException;
import org.apache.flink.runtime.io.network.partition.PartitionNotFoundException;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannelID;
import org.apache.flink.runtime.io.network.partition.consumer.RemoteInputChannel;

import org.apache.flink.shaded.guava18.com.google.common.collect.Maps;
import org.apache.flink.shaded.netty4.io.netty.buffer.ByteBuf;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelHandlerContext;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelInboundHandlerAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Channel handler to read the messages of buffer response or error response from the
 * producer.
 *
 * 从生产者读取buffer响应信息或错误信息
 *
 * <p>It is used in the old network mode. 用于老的网络模式(新模式credit-based)
 */
class PartitionRequestClientHandler extends ChannelInboundHandlerAdapter implements NetworkClientHandler {

	private static final Logger LOG = LoggerFactory.getLogger(PartitionRequestClientHandler.class);

	// inputChannel集合, 用来保存于其他机器通讯的RemoteInputChannel
	private final ConcurrentMap<InputChannelID, RemoteInputChannel> inputChannels = new ConcurrentHashMap<InputChannelID, RemoteInputChannel>();

	// channel错误
	private final AtomicReference<Throwable> channelError = new AtomicReference<Throwable>();

	// buffer监听器
	private final BufferListenerTask bufferListener = new BufferListenerTask();

	private final Queue<Object> stagedMessages = new ArrayDeque<Object>();

	private final StagedMessagesHandlerTask stagedMessagesHandler = new StagedMessagesHandlerTask();

	/**
	 * Set of cancelled partition requests. A request is cancelled iff an input channel is cleared
	 * while data is still coming in for this channel.
	 *
	 * 已取消的分区请求的集合
	 */
	private final ConcurrentMap<InputChannelID, InputChannelID> cancelled = Maps.newConcurrentMap();

	// channelHandler上线文
	private volatile ChannelHandlerContext ctx;

	// ------------------------------------------------------------------------
	// Input channel/receiver registration
	// ------------------------------------------------------------------------

	@Override
	public void addInputChannel(RemoteInputChannel listener) throws IOException {
		checkError();

		inputChannels.putIfAbsent(listener.getInputChannelId(), listener);
	}

	@Override
	public void removeInputChannel(RemoteInputChannel listener) {
		inputChannels.remove(listener.getInputChannelId());
	}

	@Override
	public void cancelRequestFor(InputChannelID inputChannelId) {
		if (inputChannelId == null || ctx == null) {
			return;
		}

		if (cancelled.putIfAbsent(inputChannelId, inputChannelId) == null) {
			ctx.writeAndFlush(new NettyMessage.CancelPartitionRequest(inputChannelId));
		}
	}

	@Override
	public void notifyCreditAvailable(final RemoteInputChannel inputChannel) {
	}

	// ------------------------------------------------------------------------
	// Network events 网络事件
	// ------------------------------------------------------------------------

	@Override
	public void channelActive(final ChannelHandlerContext ctx) throws Exception {
		if (this.ctx == null) {
			this.ctx = ctx;
		}

		super.channelActive(ctx);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		// Unexpected close. In normal operation, the client closes the connection after all input
		// channels have been removed. This indicates a problem with the remote task manager.
		if (!inputChannels.isEmpty()) {
			final SocketAddress remoteAddr = ctx.channel().remoteAddress();

			notifyAllChannelsOfErrorAndClose(new RemoteTransportException(
				"Connection unexpectedly closed by remote task manager '" + remoteAddr + "'. "
					+ "This might indicate that the remote task manager was lost.",
				remoteAddr));
		}

		super.channelInactive(ctx);
	}

	/**
	 * Called on exceptions in the client handler pipeline.
	 *
	 * <p> Remote exceptions are received as regular payload.
	 */
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {

		if (cause instanceof TransportException) {
			notifyAllChannelsOfErrorAndClose(cause);
		}
		else {
			final SocketAddress remoteAddr = ctx.channel().remoteAddress();

			final TransportException tex;

			// Improve on the connection reset by peer error message
			if (cause instanceof IOException
				&& cause.getMessage().equals("Connection reset by peer")) {

				tex = new RemoteTransportException(
					"Lost connection to task manager '" + remoteAddr + "'. This indicates "
						+ "that the remote task manager was lost.", remoteAddr, cause);
			}
			else {
				SocketAddress localAddr = ctx.channel().localAddress();
				tex = new LocalTransportException(
					String.format("%s (connection to '%s')", cause.getMessage(), remoteAddr),
					localAddr,
					cause);
			}

			notifyAllChannelsOfErrorAndClose(tex);
		}
	}

	/**
	 * 从PartitionRequestQueue#writeAndFlushNextMessageIfPossible发送的数据
	 *
	 * 收到消息时的回调方法，可以作为数据传输入口来分析
	 * */
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		try {
			if (!bufferListener.hasStagedBufferOrEvent() && stagedMessages.isEmpty()) {
				// 消息处理
				decodeMsg(msg, false);
			}
			else {
				stagedMessages.add(msg);
			}
		}
		catch (Throwable t) {
			notifyAllChannelsOfErrorAndClose(t);
		}
	}

	private void notifyAllChannelsOfErrorAndClose(Throwable cause) {
		if (channelError.compareAndSet(null, cause)) {
			try {
				for (RemoteInputChannel inputChannel : inputChannels.values()) {
					inputChannel.onError(cause);
				}
			}
			catch (Throwable t) {
				// We can only swallow the Exception at this point. :(
				LOG.warn("An Exception was thrown during error notification of a "
					+ "remote input channel.", t);
			}
			finally {
				inputChannels.clear();

				if (ctx != null) {
					ctx.close();
				}
			}
		}
	}

	// ------------------------------------------------------------------------

	/**
	 * Checks for an error and rethrows it if one was reported.
	 */
	private void checkError() throws IOException {
		final Throwable t = channelError.get();

		if (t != null) {
			if (t instanceof IOException) {
				throw (IOException) t;
			}
			else {
				throw new IOException("There has been an error in the channel.", t);
			}
		}
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		super.channelReadComplete(ctx);
	}

	// 消息解码
	private boolean decodeMsg(Object msg, boolean isStagedBuffer) throws Throwable {
		final Class<?> msgClazz = msg.getClass();

		// ---- Buffer --------------------------------------------------------
		// 数据是从PartitionRequestQueue#writeAndFlushNextMessageIfPossible方法中 发送的
		if (msgClazz == NettyMessage.BufferResponse.class) {
			NettyMessage.BufferResponse bufferOrEvent = (NettyMessage.BufferResponse) msg;

			RemoteInputChannel inputChannel = inputChannels.get(bufferOrEvent.receiverId);
			// 如果没有接收者，则释放buffer。 并且通知服务端 取消请求
			if (inputChannel == null) {
				bufferOrEvent.releaseBuffer();

				cancelRequestFor(bufferOrEvent.receiverId);

				return true;
			}
			// 数据解析
			return decodeBufferOrEvent(inputChannel, bufferOrEvent, isStagedBuffer);
		}
		// ---- Error ---------------------------------------------------------
		else if (msgClazz == NettyMessage.ErrorResponse.class) {
			NettyMessage.ErrorResponse error = (NettyMessage.ErrorResponse) msg;

			SocketAddress remoteAddr = ctx.channel().remoteAddress();

			if (error.isFatalError()) {
				notifyAllChannelsOfErrorAndClose(new RemoteTransportException(
					"Fatal error at remote task manager '" + remoteAddr + "'.",
					remoteAddr, error.cause));
			}
			else {
				RemoteInputChannel inputChannel = inputChannels.get(error.receiverId);

				if (inputChannel != null) {
					if (error.cause.getClass() == PartitionNotFoundException.class) {
						inputChannel.onFailedPartitionRequest();
					}
					else {
						inputChannel.onError(new RemoteTransportException(
							"Error at remote task manager '" + remoteAddr + "'.",
							remoteAddr, error.cause));
					}
				}
			}
		}
		else {
			throw new IllegalStateException("Received unknown message from producer: " + msg.getClass());
		}

		return true;
	}

	/**
	 * 解码buffer或event
	 * */
	private boolean decodeBufferOrEvent(RemoteInputChannel inputChannel, NettyMessage.BufferResponse bufferOrEvent, boolean isStagedBuffer) throws Throwable {
		boolean releaseNettyBuffer = true;

		try {
			ByteBuf nettyBuffer = bufferOrEvent.getNettyBuffer();
			final int receivedSize = nettyBuffer.readableBytes();
			if (bufferOrEvent.isBuffer()) {
				// ---- Buffer ------------------------------------------------

				// Early return for empty buffers. Otherwise Netty's readBytes() throws an
				// IndexOutOfBoundsException.
				if (receivedSize == 0) {
					inputChannel.onEmptyBuffer(bufferOrEvent.sequenceNumber, -1);
					return true;
				}

				BufferProvider bufferProvider = inputChannel.getBufferProvider();

				if (bufferProvider == null) {
					// receiver has been cancelled/failed
					cancelRequestFor(bufferOrEvent.receiverId);
					return isStagedBuffer;
				}

				while (true) {
					// 直接从provider返回一个buffer实例
					Buffer buffer = bufferProvider.requestBuffer();

					if (buffer != null) {
						// 将接收到的数据写入buffer
						nettyBuffer.readBytes(buffer.asByteBuf(), receivedSize);

						inputChannel.onBuffer(buffer, bufferOrEvent.sequenceNumber, -1);

						return true;
					}
					else if (bufferListener.waitForBuffer(bufferProvider, bufferOrEvent)) {
						releaseNettyBuffer = false;

						return false;
					}
					else if (bufferProvider.isDestroyed()) {
						return isStagedBuffer;
					}
				}
			}
			else {
				// ---- Event -------------------------------------------------
				// TODO We can just keep the serialized data in the Netty buffer and release it later at the reader
				byte[] byteArray = new byte[receivedSize];
				nettyBuffer.readBytes(byteArray);

				MemorySegment memSeg = MemorySegmentFactory.wrap(byteArray);
				Buffer buffer = new NetworkBuffer(memSeg, FreeingBufferRecycler.INSTANCE, false, receivedSize);

				inputChannel.onBuffer(buffer, bufferOrEvent.sequenceNumber, -1);

				return true;
			}
		}
		finally {
			if (releaseNettyBuffer) {
				bufferOrEvent.releaseBuffer();
			}
		}
	}

	private class AsyncErrorNotificationTask implements Runnable {

		private final Throwable error;

		public AsyncErrorNotificationTask(Throwable error) {
			this.error = error;
		}

		@Override
		public void run() {
			notifyAllChannelsOfErrorAndClose(error);
		}
	}

	/**
	 * A buffer availability listener, which subscribes/unsubscribes the NIO
	 * read event.
	 * buffer可用性监听器，用来订阅NIO read事件
	 *
	 * <p>If no buffer is available, the channel read event will be unsubscribed
	 * until one becomes available again.
	 * 如果没有可用的buffer，channel read event会被取消订阅 之道一个buffer变为可用
	 *
	 * <p>After a buffer becomes available again, the buffer is handed over by
	 * the thread calling {@link #notifyBufferAvailable(Buffer)} to the network I/O
	 * thread, which then continues the processing of the staged buffer.
	 */
	private class BufferListenerTask implements BufferListener, Runnable {

		private final AtomicReference<Buffer> availableBuffer = new AtomicReference<Buffer>();

		private NettyMessage.BufferResponse stagedBufferResponse;

		private boolean waitForBuffer(BufferProvider bufferProvider, NettyMessage.BufferResponse bufferResponse) {

			stagedBufferResponse = bufferResponse;

			if (bufferProvider.addBufferListener(this)) {
				if (ctx.channel().config().isAutoRead()) {
					ctx.channel().config().setAutoRead(false);
				}

				return true;
			}
			else {
				stagedBufferResponse = null;

				return false;
			}
		}

		private boolean hasStagedBufferOrEvent() {
			return stagedBufferResponse != null;
		}

		public void notifyBufferDestroyed() {
			// The buffer pool has been destroyed
			stagedBufferResponse = null;

			if (stagedMessages.isEmpty()) {
				ctx.channel().config().setAutoRead(true);
				ctx.channel().read();
			}
			else {
				ctx.channel().eventLoop().execute(stagedMessagesHandler);
			}
		}

		// Called by the recycling thread (not network I/O thread)
		@Override
		public NotificationResult notifyBufferAvailable(Buffer buffer) {
			if (availableBuffer.compareAndSet(null, buffer)) {
				ctx.channel().eventLoop().execute(this);

				return NotificationResult.BUFFER_USED_NO_NEED_MORE;
			}
			else {
				ctx.channel().eventLoop().execute(new AsyncErrorNotificationTask(
					new IllegalStateException("Received a buffer notification, " +
						" but the previous one has not been handled yet.")));
				return NotificationResult.BUFFER_NOT_USED;
			}
		}

		/**
		 * Continues the decoding of a staged buffer after a buffer has become available again.
		 *
		 * <p>This task is executed by the network I/O thread.
		 */
		@Override
		public void run() {
			boolean success = false;

			Buffer buffer = null;

			try {
				if ((buffer = availableBuffer.getAndSet(null)) == null) {
					throw new IllegalStateException("Running buffer availability task w/o a buffer.");
				}

				ByteBuf nettyBuffer = stagedBufferResponse.getNettyBuffer();
				nettyBuffer.readBytes(buffer.asByteBuf(), nettyBuffer.readableBytes());
				stagedBufferResponse.releaseBuffer();

				RemoteInputChannel inputChannel = inputChannels.get(stagedBufferResponse.receiverId);

				if (inputChannel != null) {
					inputChannel.onBuffer(buffer, stagedBufferResponse.sequenceNumber, -1);

					success = true;
				}
				else {
					cancelRequestFor(stagedBufferResponse.receiverId);
				}

				stagedBufferResponse = null;

				if (stagedMessages.isEmpty()) {
					ctx.channel().config().setAutoRead(true);
					ctx.channel().read();
				}
				else {
					ctx.channel().eventLoop().execute(stagedMessagesHandler);
				}
			}
			catch (Throwable t) {
				notifyAllChannelsOfErrorAndClose(t);
			}
			finally {
				if (!success) {
					if (buffer != null) {
						buffer.recycleBuffer();
					}
				}
			}
		}
	}

	public class StagedMessagesHandlerTask implements Runnable {

		@Override
		public void run() {
			try {
				Object msg;
				while ((msg = stagedMessages.poll()) != null) {
					if (!decodeMsg(msg, true)) {
						return;
					}
				}

				ctx.channel().config().setAutoRead(true);
				ctx.channel().read();
			}
			catch (Throwable t) {
				notifyAllChannelsOfErrorAndClose(t);
			}
		}
	}
}
