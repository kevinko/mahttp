// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.IOException;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

// Handles an HTTP request in non-blocking fashion.
class HttpConnection {
    private enum State {
        // Request start line.
        REQUEST_START,
        REQUEST_HEADERS,
        SERVER_ERROR,
    }

    /* Size for the internal buffers. */
    public static final int BUFFER_SIZE = 4096;

    private static final Charset US_ASCII_CHARSET = Charset.forName("US-ASCII");

    private NonBlockingConnection mConn;

    private State mState;

    private HttpRequest mRequest;

    public HttpConnection(Selector selector, SocketChannel chan) throws IOException {
        mConn = new NonBlockingConnection(selector, chan, BUFFER_SIZE);
        mState = State.REQUEST_START;
        mRequest = new HttpRequest();
    }

    private void handleRecv(NonBlockingConnection conn, ByteBuffer buf) {
        switch (mState) {
            case REQUEST_START:
                try {
                    if (!parseRequestStart(buf, mRequest)) {
                        // Continue reading.  The recv() is already persistent.
                        return;
                    }
                    mState = State.REQUEST_HEADERS;
                } catch (InvalidRequestException e) {
                    mState = State.SERVER_ERROR;
                }
                break;

            default:
                // TODO
                break;
        }
    }

    /**
     * @return null if a line has yet to be parsed from buf, in which case
     * buf will be compacted so that new data can be loaded.  Otherwise, returns
     * a read-only buffer holding the parsed line.  Carriage returns might be
     * in the returned string but newlines will not.
     *
     * @throws BufferOverflowException if the line is longer than buf's
     * capacity.  A server will typically send a 414 Request-URI Too Long error
     * as a result.
     */
    private static ByteBuffer parseLine(ByteBuffer buf) throws BufferOverflowException {
        int startPos = buf.position();

        while (buf.hasRemaining()) {
            char ch = (char) buf.get();
            if (ch == '\r') {
                continue;
            }

            if (ch == '\n') {
                ByteBuffer result = buf.asReadOnlyBuffer();
                result.limit(buf.position());
                result.position(startPos);
                return result;
            }
        }

        // We haven't encountered a newline yet.  See if the buffer space has
        // been exceeded.
        if (startPos == 0 &&
            buf.position() == buf.capacity()) {
            throw new BufferOverflowException();
        }

        buf.position(startPos);
        buf.compact();
        // Signal that more data should be loaded.
        return null;
    }

    /**
     * @throws InvalidRequestException on bad request.
     * @return true if enough data existed in the buffer to parse a request
     * line.
     */
    private static boolean parseRequestStart(ByteBuffer buf, HttpRequest req) throws InvalidRequestException {
        // The spec allows for leading CRLFs.
        skipCrlf(buf);

        // Now, look for the Request-Line.
        ByteBuffer lineBuf;
        try {
            lineBuf = parseLine(buf);
        } catch (BufferOverflowException e) {
            throw new InvalidRequestException();
        }

        if (lineBuf == null) {
            // Signal that more data is needed.
            return false;
        }

        try {
            HttpRequest.Method method = parseMethod(lineBuf);
            req.setMethod(method);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException();
        }

        String reqUri = parseWord(lineBuf);
        if (reqUri.length() == 0) {
            throw new InvalidRequestException();
        }
        req.setUri(reqUri);

        int version = parseHttpVersion(lineBuf);
        if (version > 1) {
            // We only support HTTP/1.0 and HTTP/1.1.
            throw new InvalidRequestException();
        }

        return true;
    }

    /**
     * @return the http version (0 for 1.0 or 1 for 1.1).
     * @throws IllegalArgumentException on error.
     */
    private static int parseHttpVersion(ByteBuffer buf) throws IllegalArgumentException {
        String word = parseWord(buf);
        if (word.length() == 0) {
            throw new IllegalArgumentException();
        }
        if (!word.startsWith("HTTP/1.")) {
            throw new IllegalArgumentException();
        }
        String minorVersionStr = word.substring(7);
        return Integer.parseInt(minorVersionStr);
    }

    /**
     * @throws IllegalArgumentException if the method name is unknown.
     */
    private static HttpRequest.Method parseMethod(ByteBuffer buf) throws IllegalArgumentException {
        String methodStr = parseWord(buf);
        if (methodStr.length() == 0) {
            throw new IllegalArgumentException();
        }
        return HttpRequest.Method.valueOf(methodStr);
    }

    /**
     * Parses the next word from buf, using whitespace as a delimiter.
     * Repeated whitespace will be trimmed.
     *
     * buf's position will be incremented to one past the separating LWS
     * character.
     *
     * The separating whitespace is not included in the result.
     *
     * This will return the empty string if buf is exhausted.
     */
    private static String parseWord(ByteBuffer buf) {
        int startPos = buf.position();

        // Eat up all leading whitespace.
        while (buf.hasRemaining()) {
            char ch = (char) buf.get();
            if (ch != ' ' && ch != '\t') {
                break;
            }
            startPos++;
        }

        while (buf.hasRemaining()) {
            // At this point, buf.position is not LWS.
            char ch = (char) buf.get();
            if (ch == ' ' || ch == '\t') {
                break;
            }
        }

        ByteBuffer result = buf.asReadOnlyBuffer();
        result.limit(buf.position());
        result.position(startPos);
        return new String(result.array(), US_ASCII_CHARSET);
    }

    /**
     * Skips all leading CRLFs in buf.
     */
    private static void skipCrlf(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            char ch = (char) buf.get();
            if (ch != '\r' && ch != '\n') {
                // Put back the read character.
                buf.position(buf.position() - 1);
                break;
            }
        }
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
