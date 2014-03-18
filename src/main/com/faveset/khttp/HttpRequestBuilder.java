// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

class HttpRequestBuilder extends HttpRequest {
    private HeadersBuilder mHeadersBuilder;

    public HttpRequestBuilder() {
        super();

        mHeadersBuilder = new HeadersBuilder();
        init(mHeadersBuilder);
    }

    @Override
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
