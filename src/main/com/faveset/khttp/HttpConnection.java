// Copyright 2014, Kevin Ko <kevin@faveset.com>

// TODO: handle multi-valued headers as a rare case.
package com.faveset.khttp;

import java.io.IOException;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

// Handles an HTTP request in non-blocking fashion.
class HttpConnection {
    private enum State {
        // Request start line.
        REQUEST_START,
        REQUEST_HEADERS,
        MESSAGE_BODY,
        SERVER_ERROR,
    }

    /* Size for the internal buffers. */
    public static final int BUFFER_SIZE = 4096;

    private NonBlockingConnection mConn;

    private State mState;

    private HttpRequest mRequest;

    // Name of the last parsed header.  Empty string if not yet encountered.
    private String mLastHeaderName;

    // The error code to report, if encountered when processing a request.
    private int mErrorCode;

    public HttpConnection(Selector selector, SocketChannel chan) throws IOException {
        mConn = new NonBlockingConnection(selector, chan, BUFFER_SIZE);
        mState = State.REQUEST_START;
        mRequest = new HttpRequest();
        mLastHeaderName = new String();
    }

    private boolean handleBody(NonBlockingConnection conn, HttpRequest req, ByteBuffer buf) throws InvalidRequestException {
        // TODO
        return false;
    }

    private void handleRecv(NonBlockingConnection conn, ByteBuffer buf) {
        boolean done = false;
        do {
            done = handleStateStep(conn, buf);
        } while (!done);
    }

    /**
     * Performs one step for the state machine.
     *
     * @return true if receiving is done and more data is needed.
     */
    private boolean handleStateStep(NonBlockingConnection conn, ByteBuffer buf) {
        switch (mState) {
            case REQUEST_START:
                try {
                    if (!parseRequestStart(buf, mRequest)) {
                        // Continue reading.  The recv() is already persistent.
                        return true;
                    }

                    // Clear header continuation, since we're seeing a new
                    // set of headers.
                    mLastHeaderName = new String();

                    mState = State.REQUEST_HEADERS;
                } catch (InvalidRequestException e) {
                    mState = State.SERVER_ERROR;
                }

                break;

            case REQUEST_HEADERS:
                try {
                    if (!parseRequestHeaders(buf, mRequest)) {
                        return true;
                    }

                    mState = State.MESSAGE_BODY;
                } catch (InvalidRequestException e) {
                    mState = State.SERVER_ERROR;
                }

                break;

            case MESSAGE_BODY:
                try {
                    if (!handleBody(conn, mRequest, buf)) {
                        return true;
                    }

                    // Handle pipelining.
                    mState = State.REQUEST_START;

                } catch (InvalidRequestException e) {
                    mState = State.SERVER_ERROR;
                }

                break;

            default:
                // TODO
                break;
        }

        return false;
    }

    /**
     * Parses a request header from buf and places the contents in req.
     *
     * This updates mLastHeaderName on success.
     *
     * @param lineBuf will be interpreted as a complete line and is typically
     * provided via parseLine.
     * @throws IllegalArgumentException if the header is malformed.
     */
    private void parseHeaderLine(ByteBuffer lineBuf, HttpRequest req) throws IllegalArgumentException {
        String fieldName = HttpConnectionParser.parseToken(lineBuf);
        if (fieldName.length() == 0 || !lineBuf.hasRemaining()) {
            throw new IllegalArgumentException();
        }

        char ch = (char) lineBuf.get();
        if (ch != ':') {
            throw new IllegalArgumentException();
        }

        String v = parseHeaderValue(lineBuf);
        req.addHeader(fieldName, v);

        mLastHeaderName = fieldName;
    }

    /**
     * @param valueBuf must be positioned at the start of the header value.
     * Any preceding whitespace will be skipped.
     *
     * @return a String containing the trimmed value.
     */
    private static String parseHeaderValue(ByteBuffer valueBuf) {
        HttpConnectionParser.skipWhitespace(valueBuf);
        return HttpConnectionParser.parseText(valueBuf);
    }

    /**
     * @throws InvalidRequestException on bad request.
     * @return true if enough data existed in the buffer to parse a request
     * line.
     */
    private static boolean parseRequestStart(ByteBuffer buf, HttpRequest req) throws InvalidRequestException {
        // The spec allows for leading CRLFs.
        HttpConnectionParser.skipCrlf(buf);

        // Now, look for the Request-Line.
        ByteBuffer lineBuf;
        try {
            lineBuf = HttpConnectionParser.parseLine(buf);
        } catch (BufferOverflowException e) {
            throw new InvalidRequestException("Request-Line exceeded buffer", HttpStatus.RequestURITooLong);
        }

        if (lineBuf == null) {
            // Signal that more data is needed.
            return false;
        }

        try {
            HttpRequest.Method method = HttpConnectionParser.parseMethod(lineBuf);
            req.setMethod(method);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Unknown request method", HttpStatus.NotImplemented);
        }

        String reqUri = HttpConnectionParser.parseWord(lineBuf);
        if (reqUri.length() == 0) {
            throw new InvalidRequestException("Request is missing URI", HttpStatus.BadRequest);
        }
        req.setUri(reqUri);

        int version = HttpConnectionParser.parseHttpVersion(lineBuf);
        if (version > 1) {
            // We only support HTTP/1.0 and HTTP/1.1.
            throw new InvalidRequestException("Unsupported HTTP version in request", HttpStatus.NotImplemented);
        }

        return true;
    }

    /**
     * Parses a set of request headers from buf and populates req.
     * mLastHeaderName will be updated as each header is parsed.
     *
     * @return false if more data is needed for reading into buf.  True if
     * header parsing is complete.
     *
     * @throws InvalidRequestException on bad request.
     */
    private boolean parseRequestHeaders(ByteBuffer buf, HttpRequest req) throws InvalidRequestException {
        ByteBuffer lineBuf;
        try {
            lineBuf = HttpConnectionParser.parseLine(buf);
        } catch (BufferOverflowException e) {
            throw new InvalidRequestException("Request-Line exceeded buffer", HttpStatus.RequestURITooLong);
        }

        if (HttpConnectionParser.hasLeadingSpace(lineBuf)) {
            // Handle continuations.
            if (mLastHeaderName.isEmpty()) {
                throw new InvalidRequestException("Invalid request header continuation", HttpStatus.BadRequest);
            }
            String value = parseHeaderValue(lineBuf);
            req.addHeader(mLastHeaderName, value);
            return false;
        }

        if (HttpConnectionParser.hasLeadingCrlf(lineBuf)) {
            // We found the lone CRLF.  We're done.
            return true;
        }

        try {
            // This updates mLastHeaderName.
            parseHeaderLine(lineBuf, mRequest);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("could not parse header line", HttpStatus.BadRequest);
        }

        return false;
    }

    /**
     * Resets the connection state for a new request.
     */
    private void reset() {
        mState = State.REQUEST_START;
        mRequest.clearHeaders();
        mLastHeaderName = new String();
        mErrorCode = 0;
    }

    /**
     * Start HttpConnection processing.
     */
    public void start() {
        mConn.recvPersistent(new NonBlockingConnection.OnRecvCallback() {
            public void onRecv(NonBlockingConnection conn, ByteBuffer buf) {
                handleRecv(conn, buf);
            }
        });
    }
};
