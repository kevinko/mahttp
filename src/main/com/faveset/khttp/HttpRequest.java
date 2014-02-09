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

    private Method mMethod;

    private String mUri;

    private HashMap<String, List<String>> mHeaders;

    public HttpRequest() {
        mHeaders = new HashMap<String, List<String>>();
    }

    /**
     * Appends value to the mapping list for name, creating a mapping if
     * necessary.
     */
    public void appendHeader(String name, String value) {
        List<String> l = mHeaders.get(name);
        if (l == null) {
            l = new ArrayList<String>();
            mHeaders.put(name, l);
        }

        l.add(value);
    }

    public void clearHeaders() {
        mHeaders.clear();
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

    /**
     * Assigns value to the mapping for name.  This clears any existing list
     * values.
     */
    public void setHeader(String name, String value) {
        List<String> l = new ArrayList<String>();
        l.add(value);

        mHeaders.put(name, l);
    }

    /**
     * Assigns value to the mapping for name.  This overwrites any existing
     * mapping.
     */
    public void setHeaderList(String name, List<String> values) {
        mHeaders.put(name, values);
    }

    public void setMethod(Method method) {
        mMethod = method;
    }

    public void setUri(String uri) {
        mUri = uri;
    }
}
