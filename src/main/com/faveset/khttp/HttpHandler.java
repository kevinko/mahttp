// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

public interface HttpHandler {
    void onRequest(HttpRequest req, HttpResponseWriter writer);
}
