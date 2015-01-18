// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

class RequestHeaderHandler implements StateHandler {
    /**
     * Handles a continuation line.
     *
     * @param lineBuf the current line buffer.
     * @param lastHeaderName the name of the last processed header.
     * @param builder the HeadersBuilder to update.
     *
     * @throws InvalidRequestException will be thrown if an invalid
     * continuation is detected.
     */
    private static void handleContinuation(ByteBuffer lineBuf,
            String lastHeaderName, HeadersBuilder builder) throws InvalidRequestException {
        if (lastHeaderName.isEmpty()) {
            throw new InvalidRequestException("Invalid request header continuation", HttpStatus.BAD_REQUEST);
        }

        String addedValue = parseHeaderValue(lineBuf);

        // Append to the last added header value.
        builder.appendValue(lastHeaderName, addedValue);
    }

    /**
     * Parses a set of request headers from buf and populates req.
     * state's LastHeaderName will be updated as each header is parsed.
     *
     * @param conn is not used
     *
     * @return false if more data is needed for reading into buf.  True if
     * ALL header parsing is complete; a state transition should occur in
     * this case.
     *
     * @throws InvalidRequestException on bad request.
     */
    @Override
    public boolean handleState(AsyncConnection conn, ByteBuffer buf, HandlerState state) throws InvalidRequestException {
        HttpRequestBuilder req = state.getRequestBuilder();

        do {
            if (Strings.hasLeadingCrlf(buf)) {
                // We found the lone CRLF.  We're done with this state.
                return true;
            }

            ByteBuffer lineBuf;
            try {
                lineBuf = Strings.parseLine(buf);
            } catch (BufferOverflowException e) {
                throw new InvalidRequestException("Request-Line exceeded buffer", HttpStatus.REQUEST_URI_TOO_LONG);
            }

            if (lineBuf == null) {
                // We need more data.  buf was compacted by parseLine.
                return false;
            }

            if (Strings.hasLeadingSpace(lineBuf)) {
                handleContinuation(lineBuf, state.getLastHeaderName(), req.getHeadersBuilder());
                continue;
            }

            try {
                // This updates mLastHeaderName.
                parseHeaderLine(lineBuf, state);
            } catch (ParseException e) {
                throw new InvalidRequestException("could not parse header line", HttpStatus.BAD_REQUEST);
            }
        } while (true);
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
    private static void parseHeaderLine(ByteBuffer lineBuf, HandlerState state) throws ParseException {
        HttpRequestBuilder req = state.getRequestBuilder();

        String fieldName = Strings.parseToken(lineBuf);
        if (fieldName.length() == 0 || !lineBuf.hasRemaining()) {
            throw new ParseException("Could not parse header name");
        }

        char ch = (char) lineBuf.get();
        if (ch != ':') {
            throw new ParseException("Could not parse header separator");
        }

        String v = parseHeaderValue(lineBuf);
        req.getHeadersBuilder().add(fieldName, v);

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
