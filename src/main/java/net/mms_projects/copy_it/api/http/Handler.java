/*  copyit-server
 *  Copyright (C) 2013  Toon Schoenmakers
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.mms_projects.copy_it.api.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import net.mms_projects.copy_it.server.database.Database;
import net.mms_projects.copy_it.server.database.DatabasePool;

import static io.netty.handler.codec.http.HttpHeaders.Names.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;

public class Handler extends SimpleChannelInboundHandler<HttpObject> {
    protected void messageReceived(final ChannelHandlerContext chx, final HttpObject o) throws Exception {
        System.err.println(o.getClass().getName());
        if (o instanceof HttpRequest) {
            final HttpRequest http = (HttpRequest) o;
            this.request = http;
            buf.setLength(0);
            if (!http.headers().contains(AUTHORIZATION)) {
                final FullHttpResponse response = new DefaultFullHttpResponse(request.getProtocolVersion()
                        ,UNAUTHORIZED);
                chx.write(response).addListener(ChannelFutureListener.CLOSE);
            } else {
                database = DatabasePool.getDBConnection();
            }
        } else if (o instanceof HttpContent) {
            final HttpContent httpContent = (HttpContent) o;
            final ByteBuf content = httpContent.content();
            buf.append("Echo: ");
            buf.append(content.toString(CharsetUtil.UTF_8));
            buf.append("\r\n");
            if (o instanceof LastHttpContent) {
                buf.append("End of content\r\n");
                final FullHttpResponse response = new DefaultFullHttpResponse(request.getProtocolVersion()
                        ,o.getDecoderResult().isSuccess() ? OK : BAD_REQUEST
                        ,Unpooled.copiedBuffer(buf.toString(), CharsetUtil.UTF_8));
                response.headers().set(CONTENT_TYPE, "text/plain; charset=UTF-8");
                if (isKeepAlive(request)) {
                    response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                    response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
                    chx.write(response);
                } else
                    chx.write(response).addListener(ChannelFutureListener.CLOSE);
                if (database != null)
                    database.free();
            }
        }
    }

    private final StringBuilder buf = new StringBuilder();
    private Database database = null;
    private HttpRequest request;
}
