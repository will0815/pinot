/**
 * Copyright (C) 2014-2016 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.transport.netty;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.concurrent.CountDownLatch;
import javax.annotation.Nullable;


public class NettyTestUtils {
  private NettyTestUtils() {
  }

  public static final String DUMMY_RESPONSE = "Dummy Response";
  public static final String DUMMY_REQUEST = "Dummy Request";
  public static final int DEFAULT_PORT = 9089;

  public static class LatchControlledRequestHandler implements NettyServer.RequestHandler {
    private final CountDownLatch _responseHandlingLatch;
    private String _response;
    private String _request;

    public LatchControlledRequestHandler(@Nullable CountDownLatch responseHandlingLatch) {
      _responseHandlingLatch = responseHandlingLatch;
    }

    public void setResponse(String response) {
      _response = response;
    }

    @Override
    public ListenableFuture<byte[]> processRequest(ChannelHandlerContext channelHandlerContext, ByteBuf request) {
      byte[] bytes = new byte[request.readableBytes()];
      request.readBytes(bytes);
      _request = new String(bytes);
      if (_responseHandlingLatch != null) {
        while (true) {
          try {
            _responseHandlingLatch.await();
            break;
          } catch (InterruptedException e) {
            // Ignore
          }
        }
      }
      return Futures.immediateFuture(_response.getBytes());
    }

    public String getRequest() {
      return _request;
    }
  }

  public static class LatchControlledRequestHandlerFactory implements NettyServer.RequestHandlerFactory {
    private final LatchControlledRequestHandler _requestHandler;

    public LatchControlledRequestHandlerFactory(LatchControlledRequestHandler requestHandler) {
      _requestHandler = requestHandler;
    }

    @Override
    public NettyServer.RequestHandler createNewRequestHandler() {
      return _requestHandler;
    }
  }
}
