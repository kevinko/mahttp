// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

class HandlerState {
    private HttpRequestBuilder mReq;

    private String mLastHeaderName;

    public HandlerState() {
        mReq = new HttpRequestBuilder();
    }

    /**
     * Prepares the HandlerState for handling a new request.
     * Resets the HttpRequest object's headers and the last header name.
     */
    public void clear() {
        mReq.clear();
        mLastHeaderName = "";
    }

    /**
     * @return Name of the last parsed header.  Empty string if not yet
     * encountered.
     */
    public String getLastHeaderName() {
        return mLastHeaderName;
    }

    /**
     * @return the HttpRequestBuilder object, which is used assembling the
     * HTTP request as it is parsed from the client.
     */
    public HttpRequestBuilder getRequestBuilder() {
        return mReq;
    }

    public void setLastHeaderName(String name) {
        mLastHeaderName = name;
    }
}
