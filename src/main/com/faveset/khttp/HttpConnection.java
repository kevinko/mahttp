// Copyright 2014, Kevin Ko <kevin@faveset.com>

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
        SERVER_ERROR,
    }

    /* Size for the internal buffers. */
    public static final int BUFFER_SIZE = 4096;

    private NonBlockingConnection mConn;

    private State mState;

    private HttpRequest mRequest;

    public HttpConnection(Selector selector, SocketChannel chan) throws IOException {
        mConn = new NonBlockingConnection(selector, chan, BUFFER_SIZE);
        mState = State.REQUEST_START;
        mRequest = new HttpRequest();
    }

    private void handleRecv(NonBlockingConnection conn, ByteBuffer buf) {
        boolean done = false;
        do {
            done = handleStateStep(conn, buf);
        } while (!done);
    }

    /**
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
                    mState = State.REQUEST_HEADERS;
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
            throw new InvalidRequestException();
        }

        if (lineBuf == null) {
            // Signal that more data is needed.
            return false;
        }

        try {
            HttpRequest.Method method = HttpConnectionParser.parseMethod(lineBuf);
            req.setMethod(method);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException();
        }

        String reqUri = HttpConnectionParser.parseWord(lineBuf);
        if (reqUri.length() == 0) {
            throw new InvalidRequestException();
        }
        req.setUri(reqUri);

        int version = HttpConnectionParser.parseHttpVersion(lineBuf);
        if (version > 1) {
            // We only support HTTP/1.0 and HTTP/1.1.
            throw new InvalidRequestException();
        }

        return true;
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
