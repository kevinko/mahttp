// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.khttp;

import java.nio.ByteBuffer;

public interface HttpResponseWriter {
    /**
     * NOTE: the Connection header will not take effect.
     * Use setCloseConnection() instead.
     */
    HeadersBuilder getHeadersBuilder();

    /**
     * Set to close the connection after sending the response.
     * Connections will persist by default unless requested by the client
     * (via the Connection header).
     */
    void setCloseConnection(boolean close);

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
