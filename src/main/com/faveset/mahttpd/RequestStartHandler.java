// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

class RequestStartHandler implements StateHandler {
    /**
     * @param conn is not used currently.
     * @param state will be cleared for the header parsing stage on success.
     *
     * @throws InvalidRequestException on bad request.
     * @return true if enough data existed in the buffer to parse a request
     * line.
     */
    @Override
    public boolean handleState(AsyncConnection conn, ByteBuffer buf, HandlerState state) throws InvalidRequestException {
        // The spec allows for leading CRLFs.
        Strings.skipCrlf(buf);

        // Now, look for the Request-Line.
        ByteBuffer lineBuf;
        try {
            lineBuf = Strings.parseLine(buf);
        } catch (BufferOverflowException e) {
            throw new InvalidRequestException("Request-Line exceeded buffer", HttpStatus.REQUEST_URI_TOO_LONG);
        }

        if (lineBuf == null) {
            // Signal that more data is needed.
            return false;
        }

        // We have a full line for parsing.  Populate the Request structure.
        state.clear();

        HttpRequestBuilder req = state.getRequestBuilder();

        try {
            HttpRequest.Method method = Strings.parseMethod(lineBuf);
            req.setMethod(method);
        } catch (ParseException e) {
            throw new InvalidRequestException("Unknown request method", HttpStatus.NOT_IMPLEMENTED);
        }

        String reqUri = Strings.parseWord(lineBuf);
        if (reqUri.length() == 0) {
            throw new InvalidRequestException("Request is missing URI", HttpStatus.BAD_REQUEST);
        }
        req.setUri(reqUri);

        try {
            int version = Strings.parseHttpVersion(lineBuf);
            if (version > 1) {
                // We only support HTTP/1.0 and HTTP/1.1.
                throw new InvalidRequestException("Unsupported HTTP version in request", HttpStatus.NOT_IMPLEMENTED);
            }

            req.setMinorVersion(version);
        } catch (ParseException e) {
                throw new InvalidRequestException("Could not parse HTTP version", HttpStatus.BAD_REQUEST);
        }

        return true;
    }
}
