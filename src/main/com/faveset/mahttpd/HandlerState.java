// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.nio.ByteBuffer;

/**
 * HandlerState tracks per-connection state for an HttpConnection's handlers.
 */
class HandlerState {
    /**
     * This callback will be called to handle decoded HTTP requests.
     *
     * @param req a populated HttpRequest
     * @param data incoming data associated with the request.  Since this is
     * a fixed buffer, it may only hold partial contents, depending on the
     * context of the calling handler.
     * @param w a ResponseWriter that should be populated with the response and
     * then sent over the connection.
     *
     * @return true if the state is complete and a transition should occur.
     */
    public interface OnRequestCallback {
        public boolean onRequest(HttpRequest req, ByteBuffer data, ResponseWriter w);
    }

    private HttpRequestBuilder mReq;

    private ResponseWriter mResponseWriter;

    private String mLastHeaderName;

    private OnRequestCallback mOnRequestCallback;

    /**
     * One must call close() when the constructed HandlerState is no longer
     * needed.
     */
    public HandlerState() {
        mReq = new HttpRequestBuilder();
        mResponseWriter = new ResponseWriter();
    }

    /**
     * One must call close() when the constructed HandlerState is no longer
     * needed.
     */
    public HandlerState(Pool<ByteBuffer> pool) {
        mReq = new HttpRequestBuilder();
        mResponseWriter = new ResponseWriter(pool);
    }

    /**
     * Prepares the HandlerState for handling a new request.
     * Resets the HttpRequest object's headers and the last header name.
     */
    public void clear() {
        mReq.clear();
        mResponseWriter.clear();
        mLastHeaderName = "";
    }

    /**
     * Closes all resources used by the HandlerState.
     */
    public void close() {
        mResponseWriter.close();
    }

    /**
     * @return Name of the last parsed header.  Empty string if not yet
     * encountered.
     */
    public String getLastHeaderName() {
        return mLastHeaderName;
    }

    public OnRequestCallback getOnRequestCallback() {
        return mOnRequestCallback;
    }

    /**
     * @return the HttpRequestBuilder object, which is used when assembling the
     * HTTP request as it is parsed from the client.
     */
    public HttpRequestBuilder getRequestBuilder() {
        return mReq;
    }

    /**
     * @return the ResponseWriter, which will be populated by the caller and
     * pushed to the client at the end of the HTTP request-response stage.
     */
    public ResponseWriter getResponseWriter() {
        return mResponseWriter;
    }

    public void setLastHeaderName(String name) {
        mLastHeaderName = name;
    }

    /**
     * @return this for chaining.
     */
    public HandlerState setOnRequestCallback(OnRequestCallback callback) {
        mOnRequestCallback = callback;
        return this;
    }
}
