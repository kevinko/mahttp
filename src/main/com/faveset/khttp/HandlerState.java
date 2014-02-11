// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

class HandlerState {
    private HttpRequest mReq;

    private String mLastHeaderName;

    public HandlerState() {
        mReq = new HttpRequest();
    }

    /**
     * @return Name of the last parsed header.  Empty string if not yet
     * encountered.
     */
    public String getLastHeaderName() {
        return mLastHeaderName;
    }

    public HttpRequest getRequest() {
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
