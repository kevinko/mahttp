// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

class RequestStartHandler implements StateHandler {
    /**
     * @param conn is not used currently.
     * @param state will be reset for the header parsing stage on success.
     *
     * @throws InvalidRequestException on bad request.
     * @return true if enough data existed in the buffer to parse a request
     * line.
     */
    public boolean handleState(NonBlockingConnection conn, ByteBuffer buf, HandlerState state) throws InvalidRequestException {
        HttpRequestBuilder req = state.getRequestBuilder();

        // The spec allows for leading CRLFs.
        Strings.skipCrlf(buf);

        // Now, look for the Request-Line.
        ByteBuffer lineBuf;
        try {
            lineBuf = Strings.parseLine(buf);
        } catch (BufferOverflowException e) {
            throw new InvalidRequestException("Request-Line exceeded buffer", HttpStatus.RequestURITooLong);
        }

        if (lineBuf == null) {
            // Signal that more data is needed.
            return false;
        }

        try {
            HttpRequest.Method method = Strings.parseMethod(lineBuf);
            req.setMethod(method);
        } catch (ParseException e) {
            throw new InvalidRequestException("Unknown request method", HttpStatus.NotImplemented);
        }

        String reqUri = Strings.parseWord(lineBuf);
        if (reqUri.length() == 0) {
            throw new InvalidRequestException("Request is missing URI", HttpStatus.BadRequest);
        }
        req.setUri(reqUri);

        try {
            int version = Strings.parseHttpVersion(lineBuf);
            if (version > 1) {
                // We only support HTTP/1.0 and HTTP/1.1.
                throw new InvalidRequestException("Unsupported HTTP version in request", HttpStatus.NotImplemented);
            }
        } catch (ParseException e) {
                throw new InvalidRequestException("Could not parse HTTP version", HttpStatus.BadRequest);
        }

        state.reset();

        return true;
    }
}