// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

class HandlerState {
    private HttpRequestBuilder mReq;

    private String mLastHeaderName;

    public HandlerState() {
        mReq = new HttpRequestBuilder();
    }

    /**
     * @return Name of the last parsed header.  Empty string if not yet
     * encountered.
     */
    public String getLastHeaderName() {
        return mLastHeaderName;
    }

    public HttpRequestBuilder getRequestBuilder() {
        return mReq;
    }

    /**
     * Prepares the HandlerState for handling a new request.
     * Resets the HttpRequest object's headers and the last header name.
     */
    public void reset() {
        mReq.clearHeaders();
        mLastHeaderName = "";
    }

    public void setLastHeaderName(String name) {
        mLastHeaderName = name;
    }
}
