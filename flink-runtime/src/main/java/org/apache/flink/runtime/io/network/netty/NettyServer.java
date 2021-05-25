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

import org.apache.flink.runtime.util.FatalExitExceptionHandler;

import org.apache.flink.shaded.guava18.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.flink.shaded.netty4.io.netty.bootstrap.ServerBootstrap;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelFuture;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelInitializer;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelOption;
import org.apache.flink.shaded.netty4.io.netty.channel.epoll.Epoll;
import org.apache.flink.shaded.netty4.io.netty.channel.epoll.EpollEventLoopGroup;
import org.apache.flink.shaded.netty4.io.netty.channel.epoll.EpollServerSocketChannel;
import org.apache.flink.shaded.netty4.io.netty.channel.nio.NioEventLoopGroup;
import org.apache.flink.shaded.netty4.io.netty.channel.socket.SocketChannel;
import org.apache.flink.shaded.netty4.io.netty.channel.socket.nio.NioServerSocketChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

class NettyServer {

	// 使用guava的ThreadFactoryBuilder来创建线程池
	private static final ThreadFactoryBuilder THREAD_FACTORY_BUILDER =
		new ThreadFactoryBuilder()
			.setDaemon(true)
			.setUncaughtExceptionHandler(FatalExitExceptionHandler.INSTANCE);

	private static final Logger LOG = LoggerFactory.getLogger(NettyServer.class);

	// netty配置
	private final NettyConfig config;

	// ServerBootstrap作为一个启动辅助类，通过他可以很方便的创建一个Netty服务端
	private ServerBootstrap bootstrap;

	// 用来保存Channel异步操作的结果
	private ChannelFuture bindFuture;

	// 本地地址
	private InetSocketAddress localAddress;

	NettyServer(NettyConfig config) {
		this.config = checkNotNull(config);
		localAddress = null;
	}

	/**
	 * 初始化服务端
	 * */
	void init(final NettyProtocol protocol, NettyBufferPool nettyBufferPool) throws IOException {
		// 检测netty服务是否已经启动
		checkState(bootstrap == null, "Netty server has already been initialized.");

		final long start = System.nanoTime();

		bootstrap = new ServerBootstrap();

		// --------------------------------------------------------------------
		// Transport-specific configuration
		// --------------------------------------------------------------------

		// getTransportType 默认是NIO
		switch (config.getTransportType()) {
			case NIO:
				initNioBootstrap();
				break;

			case EPOLL:
				initEpollBootstrap();
				break;

			case AUTO:
				if (Epoll.isAvailable()) {
					initEpollBootstrap();
					LOG.info("Transport type 'auto': using EPOLL.");
				}
				else {
					initNioBootstrap();
					LOG.info("Transport type 'auto': using NIO.");
				}
		}

		// --------------------------------------------------------------------
		// Configuration
		// --------------------------------------------------------------------

		// Server bind address 服务端地址、端口绑定
		bootstrap.localAddress(config.getServerAddress(), config.getServerPort());

		// Netty为了提升报文的读写性能，默认会采用“零拷贝”模式，即消息读取时使用非堆的DirectBuffer来减少ByteBuffer的内存拷贝
		// 如果需要修改接收Buffer的类型，需要进行如下配置
		// Pooled allocators for Netty's ByteBuf instances
		bootstrap.option(ChannelOption.ALLOCATOR, nettyBufferPool);
		bootstrap.childOption(ChannelOption.ALLOCATOR, nettyBufferPool);

		/**
		 * ChannelOption.SO_BACKLOG对应的是tcp/ip协议listen函数中的backlog参数，
		 * 函数listen(int socketfd,int backlog)用来初始化服务端可连接队列，服务端处理客户端连接请求是顺序处理的，
		 * 所以同一时间只能处理一个客户端连接，多个客户端来的时候，服务端将不能处理的客户端连接请求放在队列中等待处理，backlog参数指定了队列的大小
		 *
		 * BACKLOG用于构造服务端套接字ServerSocket对象，
		 * 标识当服务器请求处理线程全满时，用于临时存放已完成三次握手的请求的队列的最大长度。
		 * 如果未设置或所设置的值小于1，Java将使用默认值50
		 * */
		if (config.getServerConnectBacklog() > 0) {
			bootstrap.option(ChannelOption.SO_BACKLOG, config.getServerConnectBacklog());
		}

		// Receive and send buffer size 设置接收与发送缓冲区的大小
		int receiveAndSendBufferSize = config.getSendAndReceiveBufferSize();
		if (receiveAndSendBufferSize > 0) {
			bootstrap.childOption(ChannelOption.SO_SNDBUF, receiveAndSendBufferSize);
			bootstrap.childOption(ChannelOption.SO_RCVBUF, receiveAndSendBufferSize);
		}

		// Low and high water marks for flow control 高低水位控制
		// hack around the impossibility (in the current netty version) to set both watermarks at
		// the same time:
		final int defaultHighWaterMark = 64 * 1024; // from DefaultChannelConfig (not exposed)
		final int newLowWaterMark = config.getMemorySegmentSize() + 1;
		final int newHighWaterMark = 2 * config.getMemorySegmentSize();
		if (newLowWaterMark > defaultHighWaterMark) {
			bootstrap.childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, newHighWaterMark);
			bootstrap.childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, newLowWaterMark);
		} else { // including (newHighWaterMark < defaultLowWaterMark)
			bootstrap.childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, newLowWaterMark);
			bootstrap.childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, newHighWaterMark);
		}

		// SSL related configuration
		final SSLHandlerFactory sslHandlerFactory;
		try {
			sslHandlerFactory = config.createServerSSLEngineFactory();
		} catch (Exception e) {
			throw new IOException("Failed to initialize SSL Context for the Netty Server", e);
		}

		// --------------------------------------------------------------------
		// Child channel pipeline for accepted connections
		// --------------------------------------------------------------------

		bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
			@Override
			public void initChannel(SocketChannel channel) throws Exception {
				if (sslHandlerFactory != null) {
					channel.pipeline().addLast("ssl", sslHandlerFactory.createNettySSLHandler());
				}

				// 在pipeline中添加ServerChannelHandler
				channel.pipeline().addLast(protocol.getServerChannelHandlers());
			}
		});

		// --------------------------------------------------------------------
		// Start Server
		// --------------------------------------------------------------------

		bindFuture = bootstrap.bind().syncUninterruptibly();

		localAddress = (InetSocketAddress) bindFuture.channel().localAddress();

		final long duration = (System.nanoTime() - start) / 1_000_000;
		LOG.info("Successful initialization (took {} ms). Listening on SocketAddress {}.", duration, localAddress);
	}

	NettyConfig getConfig() {
		return config;
	}

	ServerBootstrap getBootstrap() {
		return bootstrap;
	}

	public InetSocketAddress getLocalAddress() {
		return localAddress;
	}

	void shutdown() {
		final long start = System.nanoTime();
		if (bindFuture != null) {
			bindFuture.channel().close().awaitUninterruptibly();
			bindFuture = null;
		}

		if (bootstrap != null) {
			if (bootstrap.group() != null) {
				bootstrap.group().shutdownGracefully();
			}
			bootstrap = null;
		}
		final long duration = (System.nanoTime() - start) / 1_000_000;
		LOG.info("Successful shutdown (took {} ms).", duration);
	}

	private void initNioBootstrap() {
		// Add the server port number to the name in order to distinguish
		// multiple servers running on the same host.
		String name = NettyConfig.SERVER_THREAD_GROUP_NAME + " (" + config.getServerPort() + ")";

		NioEventLoopGroup nioGroup = new NioEventLoopGroup(config.getServerNumThreads(), getNamedThreadFactory(name));
		bootstrap.group(nioGroup).channel(NioServerSocketChannel.class);
	}

	private void initEpollBootstrap() {
		// Add the server port number to the name in order to distinguish
		// multiple servers running on the same host.
		String name = NettyConfig.SERVER_THREAD_GROUP_NAME + " (" + config.getServerPort() + ")";

		EpollEventLoopGroup epollGroup = new EpollEventLoopGroup(config.getServerNumThreads(), getNamedThreadFactory(name));
		bootstrap.group(epollGroup).channel(EpollServerSocketChannel.class);
	}

	public static ThreadFactory getNamedThreadFactory(String name) {
		return THREAD_FACTORY_BUILDER.setNameFormat(name + " Thread %d").build();
	}
}
