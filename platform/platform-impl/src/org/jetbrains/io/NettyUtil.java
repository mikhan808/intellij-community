/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.io;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.SystemProperties;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.BootstrapUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.ide.PooledThreadExecutor;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Random;

public final class NettyUtil {
  public static final int MAX_CONTENT_LENGTH = 100 * 1024 * 1024;

  public static final int DEFAULT_CONNECT_ATTEMPT_COUNT = 20;
  public static final int MIN_START_TIME = 100;

  static {
    // IDEA-120811
    if (SystemProperties.getBooleanProperty("io.netty.random.id", true)) {
      System.setProperty("io.netty.machineId", "9e43d860");
      System.setProperty("io.netty.processId", Integer.toString(new Random().nextInt(65535)));
    }
  }

  public static void log(Throwable throwable, Logger log) {
    if (isAsWarning(throwable)) {
      log.warn(throwable);
    }
    else {
      log.error(throwable);
    }
  }

  @Nullable
  public static Channel connect(@NotNull Bootstrap bootstrap, @NotNull InetSocketAddress remoteAddress, @Nullable AsyncPromise<?> promise) {
    return connect(bootstrap, remoteAddress, promise, DEFAULT_CONNECT_ATTEMPT_COUNT);
  }

  @Nullable
  public static Channel connect(@NotNull Bootstrap bootstrap, @NotNull InetSocketAddress remoteAddress, @Nullable AsyncPromise<?> promise, int maxAttemptCount) {
    try {
      return doConnect(bootstrap, remoteAddress, promise, maxAttemptCount);
    }
    catch (Throwable e) {
      if (promise != null) {
        promise.setError(e);
      }
      return null;
    }
  }

  @Nullable
  private static Channel doConnect(@NotNull Bootstrap bootstrap, @NotNull InetSocketAddress remoteAddress, @Nullable AsyncPromise<?> promise, int maxAttemptCount)
    throws Throwable {
    int attemptCount = 0;

    if (bootstrap.group() instanceof NioEventLoopGroup) {
      while (true) {
        ChannelFuture future = bootstrap.connect(remoteAddress).awaitUninterruptibly();
        if (future.isSuccess()) {
          return future.channel();
        }
        else if (maxAttemptCount == -1) {
          //noinspection BusyWait
          Thread.sleep(300);
          attemptCount++;
        }
        else if (++attemptCount < maxAttemptCount) {
          //noinspection BusyWait
          Thread.sleep(attemptCount * MIN_START_TIME);
        }
        else {
          @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
          Throwable cause = future.cause();
          if (promise != null) {
            //noinspection ThrowableResultOfMethodCallIgnored
            promise.setError(cause == null ? Promise.createError("Cannot connect: unknown error") : cause);
          }
          return null;
        }
      }
    }

    Socket socket;
    while (true) {
      try {
        //noinspection SocketOpenedButNotSafelyClosed
        socket = new Socket(remoteAddress.getAddress(), remoteAddress.getPort());
        break;
      }
      catch (IOException e) {
        if (maxAttemptCount == -1) {
          //noinspection BusyWait
          Thread.sleep(300);
          attemptCount++;
        }
        else if (++attemptCount < maxAttemptCount) {
          //noinspection BusyWait
          Thread.sleep(attemptCount * MIN_START_TIME);
        }
        else {
          if (promise != null) {
            promise.setError(e);
          }
          return null;
        }
      }
    }

    OioSocketChannel channel = new OioSocketChannel(socket);
    BootstrapUtil.initAndRegister(channel, bootstrap).awaitUninterruptibly();
    return channel;
  }

  private static boolean isAsWarning(Throwable throwable) {
    String message = throwable.getMessage();
    if (message == null) {
      return false;
    }

    return (throwable instanceof IOException && message.equals("An existing connection was forcibly closed by the remote host")) ||
           (throwable instanceof ChannelException && message.startsWith("Failed to bind to: ")) ||
           throwable instanceof BindException ||
           (message.startsWith("Connection reset") || message.equals("Operation timed out") || message.equals("Connection timed out"));
  }

  // applicable only in case of ClientBootstrap&OioClientSocketChannelFactory
  public static void closeAndReleaseFactory(@NotNull Channel channel) {
    EventLoop channelFactory = channel.eventLoop();
    try {
      channel.close().awaitUninterruptibly();
    }
    finally {
      // in our case it does nothing, we don't use ExecutorService, but we are aware of future changes
      channelFactory.shutdownGracefully();
    }
  }

  public static ServerBootstrap nioServerBootstrap(@NotNull EventLoopGroup eventLoopGroup) {
    ServerBootstrap bootstrap = new ServerBootstrap().group(eventLoopGroup).channel(NioServerSocketChannel.class);
    bootstrap.childOption(ChannelOption.TCP_NODELAY, true).childOption(ChannelOption.SO_KEEPALIVE, true);
    return bootstrap;
  }

  public static Bootstrap oioClientBootstrap() {
    Bootstrap bootstrap = new Bootstrap().group(new OioEventLoopGroup(1, PooledThreadExecutor.INSTANCE)).channel(OioSocketChannel.class);
    bootstrap.option(ChannelOption.TCP_NODELAY, true).option(ChannelOption.SO_KEEPALIVE, true);
    return bootstrap;
  }

  @SuppressWarnings("UnusedDeclaration")
  public static Bootstrap nioClientBootstrap() {
    return nioClientBootstrap(new NioEventLoopGroup(1, PooledThreadExecutor.INSTANCE));
  }

  public static Bootstrap nioClientBootstrap(@NotNull EventLoopGroup eventLoopGroup) {
    Bootstrap bootstrap = new Bootstrap().group(eventLoopGroup).channel(NioSocketChannel.class);
    bootstrap.option(ChannelOption.TCP_NODELAY, true).option(ChannelOption.SO_KEEPALIVE, true);
    return bootstrap;
  }

  public static void addHttpServerCodec(@NotNull ChannelPipeline pipeline) {
    pipeline.addLast(new HttpRequestDecoder(),
                     new HttpResponseEncoder(),
                     new CorsHandler(CorsConfig.withAnyOrigin().allowCredentials().allowNullOrigin().allowedRequestMethods().build()),
                     new HttpObjectAggregator(MAX_CONTENT_LENGTH));
  }
}