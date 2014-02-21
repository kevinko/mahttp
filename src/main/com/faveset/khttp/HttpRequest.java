// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class HttpRequest {
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

    protected Headers mHeaders;

    private BodyType mBodyType;
    private OnBodyCopyCallback mOnBodyCopyCallback;
    private OnBodyReadCallback mOnBodyReadCallback;

    public HttpRequest() {
        mBodyType = BodyType.IGNORE;
        mHeaders = new Headers();
    }

    protected void clear() {
        mBodyType = BodyType.IGNORE;
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
}
