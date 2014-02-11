// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

class HttpRequestBuilder extends HttpRequest {
    public HttpRequestBuilder() {
        super();
    }

    /**
     * Appends value to the mapping list for name, creating a mapping if
     * necessary.
     */
    public void addHeader(String name, String value) {
        List<String> l = mHeaders.get(name);
        if (l == null) {
            l = new ArrayList<String>();
            mHeaders.put(name, l);
        }

        l.add(value);
    }

    /**
     * Appends addedValue to the existing value for name, or creates a new
     * mapping if one does not exist.  A space will separate the existing
     * value and the new value.
     */
    public void appendHeaderValue(String name, String addedValue) {
        String trimmedAddedValue = addedValue.trim();

        List<String> l = mHeaders.get(name);
        if (l == null) {
            setHeader(name, trimmedAddedValue);
            return;
        }

        if (l.size() == 0) {
            // Lists shouldn't be empty, but just in case.
            l.add(trimmedAddedValue);
            return;
        }

        // Access is fast, since we use an ArrayList internally.
        // NOTE: be aware of this when changing internal structures.
        int lastIndex = l.size() - 1;
        String lastValue = l.get(lastIndex);
        String newValue = lastValue.trim().concat(" " + trimmedAddedValue);
        l.set(lastIndex, newValue);
    }

    public void clearHeaders() {
        mHeaders.clear();
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
