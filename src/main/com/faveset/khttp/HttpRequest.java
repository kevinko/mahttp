// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

class HttpRequest {
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

    private Method mMethod;

    private String mUri;

    public HttpRequest() {}

    public Method getMethod() {
        return mMethod;
    }

    public String getUri() {
        return mUri;
    }

    public void setMethod(Method method) {
        mMethod = method;
    }

    public void setUri(String uri) {
        mUri = uri;
    }
}
