/*******************************************************************************
 * Copyright (c) 2010, 2011 Ed Anuff and Usergrid, all rights reserved.
 * http://www.usergrid.com
 * 
 * This file is part of Usergrid Stack.
 * 
 * Usergrid Stack is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * 
 * Usergrid Stack is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License along
 * with Usergrid Stack. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Additional permission under GNU AGPL version 3 section 7
 * 
 * Linking Usergrid Stack statically or dynamically with other modules is making
 * a combined work based on Usergrid Stack. Thus, the terms and conditions of the
 * GNU General Public License cover the whole combination.
 * 
 * In addition, as a special exception, the copyright holders of Usergrid Stack
 * give you permission to combine Usergrid Stack with free software programs or
 * libraries that are released under the GNU LGPL and with independent modules
 * that communicate with Usergrid Stack solely through:
 * 
 *   - Classes implementing the org.usergrid.services.Service interface
 *   - Apache Shiro Realms and Filters
 *   - Servlet Filters and JAX-RS/Jersey Filters
 * 
 * You may copy and distribute such a system following the terms of the GNU AGPL
 * for Usergrid Stack and the licenses of the other code concerned, provided that
 ******************************************************************************/
package org.usergrid.mongo.testproxy;

import java.net.InetSocketAddress;
import java.nio.ByteOrder;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.buffer.HeapChannelBufferFactory;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.usergrid.mongo.MongoMessageDecoder;
import org.usergrid.mongo.protocol.Message;

public class MongoProxyInboundHandler extends SimpleChannelUpstreamHandler {

	private final ClientSocketChannelFactory cf;
	private final String remoteHost;
	private final int remotePort;

	// This lock guards against the race condition that overrides the
	// OP_READ flag incorrectly.
	// See the related discussion: http://markmail.org/message/x7jc6mqx6ripynqf
	final Object trafficLock = new Object();

	private volatile Channel outboundChannel;

	public MongoProxyInboundHandler(ClientSocketChannelFactory cf,
			String remoteHost, int remotePort) {
		this.cf = cf;
		this.remoteHost = remoteHost;
		this.remotePort = remotePort;
	}

	@Override
	public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e)
			throws Exception {
		// Suspend incoming traffic until connected to the remote host.
		final Channel inboundChannel = e.getChannel();
		inboundChannel.setReadable(false);

		// Start the connection attempt.
		ClientBootstrap cb = new ClientBootstrap(cf);
		cb.setOption("bufferFactory",
				HeapChannelBufferFactory.getInstance(ByteOrder.LITTLE_ENDIAN));
		cb.getPipeline().addLast("framer", new MongoMessageFrame());
		cb.getPipeline()
				.addLast("handler", new OutboundHandler(e.getChannel()));
		ChannelFuture f = cb.connect(new InetSocketAddress(remoteHost,
				remotePort));

		outboundChannel = f.getChannel();
		f.addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future)
					throws Exception {
				if (future.isSuccess()) {
					// Connection attempt succeeded:
					// Begin to accept incoming traffic.
					inboundChannel.setReadable(true);
				} else {
					// Close the connection if the connection attempt has
					// failed.
					inboundChannel.close();
				}
			}
		});
	}

	@Override
	public void messageReceived(ChannelHandlerContext ctx, final MessageEvent e)
			throws Exception {
		ChannelBuffer msg = (ChannelBuffer) e.getMessage();
		Message mongo_message = MongoMessageDecoder.decode(msg);
		if (mongo_message != null) {
			System.out.println(">>> " + mongo_message.toString());
		}
		synchronized (trafficLock) {
			outboundChannel.write(msg);
			// If outboundChannel is saturated, do not read until notified in
			// OutboundHandler.channelInterestChanged().
			if (!outboundChannel.isWritable()) {
				e.getChannel().setReadable(false);
			}
		}
	}

	@Override
	public void channelInterestChanged(ChannelHandlerContext ctx,
			ChannelStateEvent e) throws Exception {
		// If inboundChannel is not saturated anymore, continue accepting
		// the incoming traffic from the outboundChannel.
		synchronized (trafficLock) {
			if (e.getChannel().isWritable()) {
				outboundChannel.setReadable(true);
			}
		}
	}

	@Override
	public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e)
			throws Exception {
		if (outboundChannel != null) {
			closeOnFlush(outboundChannel);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
			throws Exception {
		e.getCause().printStackTrace();
		closeOnFlush(e.getChannel());
	}

	private class OutboundHandler extends SimpleChannelUpstreamHandler {

		private final Channel inboundChannel;

		OutboundHandler(Channel inboundChannel) {
			this.inboundChannel = inboundChannel;
		}

		@Override
		public void messageReceived(ChannelHandlerContext ctx,
				final MessageEvent e) throws Exception {
			ChannelBuffer msg = (ChannelBuffer) e.getMessage();
			Message mongo_message = MongoMessageDecoder.decode(msg);
			if (mongo_message != null) {
				System.out.println("<<< " + mongo_message.toString() + "\n");
			}
			synchronized (trafficLock) {
				inboundChannel.write(msg);
				// If inboundChannel is saturated, do not read until notified in
				// HexDumpProxyInboundHandler.channelInterestChanged().
				if (!inboundChannel.isWritable()) {
					e.getChannel().setReadable(false);
				}
			}
		}

		@Override
		public void channelInterestChanged(ChannelHandlerContext ctx,
				ChannelStateEvent e) throws Exception {
			// If outboundChannel is not saturated anymore, continue accepting
			// the incoming traffic from the inboundChannel.
			synchronized (trafficLock) {
				if (e.getChannel().isWritable()) {
					inboundChannel.setReadable(true);
				}
			}
		}

		@Override
		public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e)
				throws Exception {
			closeOnFlush(inboundChannel);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
				throws Exception {
			e.getCause().printStackTrace();
			closeOnFlush(e.getChannel());
		}
	}

	/**
	 * Closes the specified channel after all queued write requests are flushed.
	 */
	static void closeOnFlush(Channel ch) {
		if (ch.isConnected()) {
			ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(
					ChannelFutureListener.CLOSE);
		}
	}
}
