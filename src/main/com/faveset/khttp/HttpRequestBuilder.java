// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

class HttpRequestBuilder extends HttpRequest {
    private HeadersBuilder mHeadersBuilder;

    public HttpRequestBuilder() {
        super();

        mHeadersBuilder = new HeadersBuilder();
        init(mHeadersBuilder);
    }

    public void clear() {
        super.clear();

        mHeadersBuilder.clear();
    }

    public HeadersBuilder getHeadersBuilder() {
        return mHeadersBuilder;
    }

    public void setMethod(Method method) {
        mMethod = method;
    }

    public void setUri(String uri) {
        mUri = uri;
    }

    /**
     * Set the minor version of the HTTP protocol used in the request.
     */
    public void setMinorVersion(int minor) {
        mMinorVersion = minor;
    }
}
