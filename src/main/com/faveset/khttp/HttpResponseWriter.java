// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

import java.util.Map;

public interface HttpResponseWriter {
    HeadersBuilder getHeadersBuilder();

    /**
     * Add buf to the response body.
     *
     * This will implicitly call writeHeader with status OK if not already
     * performed by the caller.
     */
    void write(ByteBuffer buf);

    /**
     * Write s to the response body.
     *
     * This will implicitly call writeHeader with status OK if not already
     * performed by the caller.
     */
    void write(String s);

    /**
     * Prepares and writes an HTTP response header with given status code.
     * If not called, the other write methods will call this implicitly with
     * status OK.
     */
    void writeHeader(int statusCode);
}
