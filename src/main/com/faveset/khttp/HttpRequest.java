// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class HttpRequest {
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

    protected Method mMethod;

    protected String mUri;

    protected Headers mHeaders;

    public HttpRequest() {
        mHeaders = new Headers();
    }

    public Headers getHeaders() {
        return mHeaders;
    }

    public Method getMethod() {
        return mMethod;
    }

    public String getUri() {
        return mUri;
    }

    protected void init(Headers headers) {
        mHeaders = headers;
    }
}
