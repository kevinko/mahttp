// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

class RequestHeaderHandler implements StateHandler {
    /**
     * Parses a set of request headers from buf and populates req.
     * state's LastHeaderName will be updated as each header is parsed.
     *
     * @return false if more data is needed for reading into buf.  True if
     * header parsing is complete.
     *
     * @throws InvalidRequestException on bad request.
     */
    public boolean handleState(NonBlockingConnection conn, ByteBuffer buf, HandlerState state) throws InvalidRequestException {
        HttpRequest req = state.getRequest();

        ByteBuffer lineBuf;
        try {
            lineBuf = Strings.parseLine(buf);
        } catch (BufferOverflowException e) {
            throw new InvalidRequestException("Request-Line exceeded buffer", HttpStatus.RequestURITooLong);
        }

        if (lineBuf == null) {
            // We need more data.
            return false;
        }

        if (Strings.hasLeadingSpace(lineBuf)) {
            // Handle continuations.
            if (state.getLastHeaderName().isEmpty()) {
                throw new InvalidRequestException("Invalid request header continuation", HttpStatus.BadRequest);
            }
            String value = parseHeaderValue(lineBuf);
            req.addHeader(state.getLastHeaderName(), value);
            return false;
        }

        if (Strings.hasLeadingCrlf(lineBuf)) {
            // We found the lone CRLF.  We're done.
            return true;
        }

        try {
            // This updates mLastHeaderName.
            parseHeaderLine(lineBuf, state);
        } catch (ParseException e) {
            throw new InvalidRequestException("could not parse header line", HttpStatus.BadRequest);
        }

        return false;
    }

    /**
     * Parses a request header from buf and places the contents in req.
     *
     * This updates state's LastHeaderName on success.
     *
     * @param lineBuf will be interpreted as a complete line and is typically
     * provided via parseLine.
     * @throws ParseException if the header is malformed.
     */
    private void parseHeaderLine(ByteBuffer lineBuf, HandlerState state) throws ParseException {
        HttpRequest req = state.getRequest();

        String fieldName = Strings.parseToken(lineBuf);
        if (fieldName.length() == 0 || !lineBuf.hasRemaining()) {
            throw new ParseException("Could not parse header name");
        }

        char ch = (char) lineBuf.get();
        if (ch != ':') {
            throw new ParseException("Could not parse header separator");
        }

        String v = parseHeaderValue(lineBuf);
        req.addHeader(fieldName, v);

        state.setLastHeaderName(fieldName);
    }

    /**
     * @param valueBuf must be positioned at the start of the header value.
     * Any preceding whitespace will be skipped.
     *
     * @return a String containing the trimmed value.
     */
    private static String parseHeaderValue(ByteBuffer valueBuf) {
        Strings.skipWhitespace(valueBuf);
        return Strings.parseText(valueBuf);
    }
}
