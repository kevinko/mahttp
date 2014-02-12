// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

// This contains all header fields defined in RFC2616.
class HeaderField {
    private static class Entity {
        private static final String ALLOW = "Allow";
        private static final String CONTENT_ENCODING = "Content-Encoding";
        private static final String CONTENT_LANGUAGE = "Content-Language";
        private static final String CONTENT_LENGTH = "Content-Length";
        private static final String CONTENT_LOCATION = "Content-Location";
        private static final String CONTENT_MD5 = "Content-MD5";
        private static final String CONTENT_RANGE = "Content-Range";
        private static final String CONTENT_TYPE = "Content-Type";
        private static final String EXPIRES = "Expires";
        private static final String LAST_MODIFIED = "Last-Modified";
    }

    private static class General {
        private static final String CACHE_CONTROL = "Cache-Control";
        private static final String CONNECTION = "Connection";
        private static final String DATE = "Date";
        private static final String PRAGMA = "Pragma";
        private static final String TRAILER = "Trailer";
        private static final String TRANSFER_ENCODING = "Transfer-Encoding";
        private static final String UPGRADE = "Upgrade";
        private static final String VIA = "Via";
        private static final String WARNING = "Warning";
    }

    private static class Request {
        private static final String ACCEPT = "Accept";
        private static final String ACCEPT_CHARSET = "Accept-Charset";
        private static final String ACCEPT_ENCODING = "Accept-Encoding";
        private static final String ACCEPT_LANGUAGE = "Accept-Language";
        private static final String AUTHORIZATION = "Authorization";
        private static final String EXPECT = "Expect";
        private static final String FROM = "From";
        private static final String HOST = "Host";
        private static final String IF_MATCH = "If-Match";
        private static final String IF_MODIFIED_SINCE = "If-Modified-Since";
        private static final String IF_NONE_MATCH = "If-None-Match";
        private static final String IF_RANGE = "If-Range";
        private static final String IF_UNMODIFIED_SINCE = "If-Unmodified-Since";
        private static final String MAX_FORWARDS = "Max-Forwards";
        private static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
        private static final String RANGE = "Range";
        private static final String REFERER = "Referer";
        private static final String TE = "TE";
        private static final String USER_AGENT = "User-Agent";
    }
}
