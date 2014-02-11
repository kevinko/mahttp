// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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

    protected Method mMethod;

    protected String mUri;

    protected HashMap<String, List<String>> mHeaders;

    public HttpRequest() {
        mHeaders = new HashMap<String, List<String>>();
    }

    /**
     * @return The list of values for the header named name or null if it
     * doesn't exist.  The returned list will always have non-zero length.
     */
    public List<String> getHeader(String name) {
        return mHeaders.get(name);
    }

    /**
     * Convenience method for returning the first header value or null if no
     * mapping exists.
     */
    public String getHeaderFirst(String name) {
        List<String> l = mHeaders.get(name);
        if (l == null) {
            return null;
        }
        return l.get(0);
    }

    public Method getMethod() {
        return mMethod;
    }

    public String getUri() {
        return mUri;
    }
}
