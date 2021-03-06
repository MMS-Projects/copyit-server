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

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.util.CharsetUtil;
import net.mms_projects.copy_it.api.http.pages.exceptions.ErrorException;
import net.mms_projects.copy_it.api.oauth.HeaderVerifier;
import net.mms_projects.copy_it.server.database.Database;
import net.mms_projects.copy_it.server.database.DatabasePool;
import java.net.URI;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;

public class Handler extends SimpleChannelInboundHandler<HttpObject> {
    /**
     * Internal message handler, this is where all the http requests come in. This is where most of the authorization
     * and page logic goes on.
     * @see net.mms_projects.copy_it.api.http.Page
     * @see net.mms_projects.copy_it.api.oauth.HeaderVerifier
     */
    protected void messageReceived(final ChannelHandlerContext chx, final HttpObject o) throws Exception {
        if (o instanceof HttpRequest) {
            final HttpRequest http = (HttpRequest) o;
            this.request = http;
            final URI uri = new URI(request.getUri());
            if ((page = Page.getNoAuthPage(uri.getPath())) != null) {
                database = DatabasePool.getDBConnection();
                if (request.getMethod() == HttpMethod.GET) {
                    try {
                        final FullHttpResponse response = page.onGetRequest(request, database);
                        HttpHeaders.setContentLength(response, response.content().readableBytes());
                        HttpHeaders.setHeader(response, CONTENT_TYPE, page.GetContentType());
                        if (isKeepAlive(request)) {
                            HttpHeaders.setKeepAlive(response, true);
                            chx.write(response);
                        } else
                            chx.write(response).addListener(ChannelFutureListener.CLOSE);
                    } catch (ErrorException e) {
                        e.printStackTrace();
                        final FullHttpResponse response = new DefaultFullHttpResponse(request.getProtocolVersion()
                                ,e.getStatus(), Unpooled.copiedBuffer(e.toString(), CharsetUtil.UTF_8));
                        HttpHeaders.setHeader(response, CONTENT_TYPE, Page.ContentTypes.JSON_TYPE);
                        chx.write(response).addListener(ChannelFutureListener.CLOSE);
                    } catch (Exception e) {
                        e.printStackTrace();
                        final FullHttpResponse response = new DefaultFullHttpResponse(request.getProtocolVersion()
                                ,INTERNAL_SERVER_ERROR);
                        chx.write(response).addListener(ChannelFutureListener.CLOSE);
                    }
                } else if (request.getMethod() == HttpMethod.POST)
                    postRequestDecoder = new HttpPostRequestDecoder(request);
            } else if ((page = Page.getAuthPage(uri.getPath())) == null) {
                final FullHttpResponse response = new DefaultFullHttpResponse(request.getProtocolVersion()
                        ,NOT_FOUND, Unpooled.copiedBuffer("404", CharsetUtil.UTF_8));
                chx.write(response).addListener(ChannelFutureListener.CLOSE);
            } else {
                try {
                    headerVerifier = new HeaderVerifier(http, uri);
                    database = DatabasePool.getDBConnection();
                    headerVerifier.verifyConsumer(database);
                    headerVerifier.verifyOAuthToken(database);
                    headerVerifier.verifyOAuthNonce(database);
                    if (request.getMethod() == HttpMethod.GET) {
                        headerVerifier.checkSignature(null, false);
                        final FullHttpResponse response = ((AuthPage) page).onGetRequest(request, database, headerVerifier);
                        HttpHeaders.setContentLength(response, response.content().readableBytes());
                        HttpHeaders.setHeader(response, CONTENT_TYPE, page.GetContentType());
                        if (isKeepAlive(request)) {
                            HttpHeaders.setKeepAlive(response, true);
                            chx.write(response);
                        } else
                            chx.write(response).addListener(ChannelFutureListener.CLOSE);
                    } else if (request.getMethod() == HttpMethod.POST)
                        postRequestDecoder = new HttpPostRequestDecoder(request);
                } catch (ErrorException e) {
                    e.printStackTrace();
                    final FullHttpResponse response = new DefaultFullHttpResponse(request.getProtocolVersion()
                            ,e.getStatus(), Unpooled.copiedBuffer(e.toString(), CharsetUtil.UTF_8));
                    HttpHeaders.setHeader(response, CONTENT_TYPE, Page.ContentTypes.JSON_TYPE);
                    chx.write(response).addListener(ChannelFutureListener.CLOSE);
                } catch (Exception e) {
                    e.printStackTrace();
                    final FullHttpResponse response = new DefaultFullHttpResponse(request.getProtocolVersion()
                            ,INTERNAL_SERVER_ERROR);
                    chx.write(response).addListener(ChannelFutureListener.CLOSE);
               }
            }
        } else if (o instanceof HttpContent && request != null && request.getMethod() == HttpMethod.POST) {
            final HttpContent httpContent = (HttpContent) o;
            postRequestDecoder.offer(httpContent);
            if (o instanceof LastHttpContent && page != null) {
                try {
                    FullHttpResponse response;
                    if (headerVerifier != null && page instanceof AuthPage) {
                        headerVerifier.checkSignature(postRequestDecoder, false);
                        response = ((AuthPage) page).onPostRequest(request, postRequestDecoder, database, headerVerifier);
                    } else
                        response = page.onPostRequest(request, postRequestDecoder, database);
                    HttpHeaders.setContentLength(response, response.content().readableBytes());
                    HttpHeaders.setHeader(response, CONTENT_TYPE, page.GetContentType());
                    if (isKeepAlive(request)) {
                        HttpHeaders.setKeepAlive(response, true);
                        chx.write(response);
                    } else
                        chx.write(response).addListener(ChannelFutureListener.CLOSE);
                } catch (ErrorException e) {
                    e.printStackTrace();
                    final FullHttpResponse response = new DefaultFullHttpResponse(request.getProtocolVersion()
                            ,e.getStatus(), Unpooled.copiedBuffer(e.toString(), CharsetUtil.UTF_8));
                    HttpHeaders.setHeader(response, CONTENT_TYPE, Page.ContentTypes.JSON_TYPE);
                    chx.write(response).addListener(ChannelFutureListener.CLOSE);
                } catch (Exception e) {
                    e.printStackTrace();
                    final FullHttpResponse response = new DefaultFullHttpResponse(request.getProtocolVersion()
                            ,INTERNAL_SERVER_ERROR);
                    chx.write(response).addListener(ChannelFutureListener.CLOSE);
                }
            }
        }
        if (o instanceof LastHttpContent && database != null)
            database.free();
    }

    private final StringBuilder buf = new StringBuilder();
    private Database database = null;
    private HeaderVerifier headerVerifier = null;
    private HttpRequest request = null;
    private HttpPostRequestDecoder postRequestDecoder = null;
    private Page page = null;
}
