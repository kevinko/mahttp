// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;

public class HttpRequest {
    private static final int sDefaultMinorVersion = 1;

    enum BodyType {
        // Ignore the body (default for GET/HEAD)
        IGNORE,
        // Read the body and call the OnBodyCallback when ready.
        READ,
        // Store the body and call the OnBodyCallback when ready.
        COPY
    }

    public enum Method {
        OPTIONS,
        GET,
        HEAD,
        POST,
        PUT,
        DELETE,
        TRACE,
        CONNECT
    }

    public interface OnBodyCopyCallback {
        void onBody(HttpRequest req);
    }

    public interface OnBodyReadCallback {
        void onBody(HttpRequest req, ByteBuffer body);
    }

    protected Method mMethod;

    protected String mUri;

    protected int mMinorVersion;

    private Headers mHeaders;
    private BodyType mBodyType;
    private OnBodyCopyCallback mOnBodyCopyCallback;
    private OnBodyReadCallback mOnBodyReadCallback;

    public HttpRequest() {
        mBodyType = BodyType.IGNORE;
        mHeaders = new Headers();
        mMinorVersion = sDefaultMinorVersion;
    }

    protected void clear() {
        mBodyType = BodyType.IGNORE;
        mMethod = Method.GET;
        mUri = "";
        mMinorVersion = sDefaultMinorVersion;
    }

    BodyType getBodyType() {
        return mBodyType;
    }

    public Headers getHeaders() {
        return mHeaders;
    }

    public Method getMethod() {
        return mMethod;
    }

    /**
     * @return the minor version of the HTTP request.
     */
    public int getMinorVersion() {
        return mMinorVersion;
    }

    OnBodyCopyCallback getOnBodyCopyCallback() {
        return mOnBodyCopyCallback;
    }

    OnBodyReadCallback getOnBodyReadCallback() {
        return mOnBodyReadCallback;
    }

    public String getUri() {
        return mUri;
    }

    protected void init(Headers headers) {
        mHeaders = headers;
    }

    /**
     * Signal that the request body should be read in its entirety and
     * copied into the given channel.
     *
     * Callback should be called when the body has been fully copied.
     */
    public void setBodyCopy(OnBodyCopyCallback callback, FileChannel chan) {
        // TODO
        throw new RuntimeException("not implemented");
    }

    /**
     * Signal that the request body should be read in its entirety and
     * that callback should be called when the body is ready.
     */
    public void setBodyRead(OnBodyReadCallback callback) {
        mBodyType = BodyType.READ;
        mOnBodyReadCallback = callback;
    }

    /**
     * @return a string representation of the request.
     */
    @Override
    public String toString() {
        return String.format("%s %s HTTP/1.%d", mMethod.name(), mUri, mMinorVersion);
    }
}
