// Copyright 2014, Kevin Ko <kevin@faveset.com>

// TODO: handle multi-valued headers as a rare case.
package com.faveset.khttp;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import java.util.EnumMap;

// Handles an HTTP request in non-blocking fashion.
class HttpConnection {
    private enum State {
        // Request start line.
        REQUEST_START,
        REQUEST_HEADERS,
        MESSAGE_BODY,
        SERVER_ERROR,
    }

    private static class StateEntry {
        private State mNextState;
        private StateHandler mHandler;

        /**
         * @param nextState the state to transition to on success.
         */
        public StateEntry(State nextState, StateHandler handler) {
            mNextState = nextState;
            mHandler = handler;
        }

        public StateHandler getHandler() {
            return mHandler;
        }

        public State getNextState() {
            return mNextState;
        }
    }

    /* Size for the internal buffers. */
    public static final int BUFFER_SIZE = 4096;

    private static final EnumMap<State, StateEntry> mStateHandlerMap;

    private NonBlockingConnection mConn;

    private HandlerState mHandlerState;

    private State mState;

    // The error code to report, if encountered when processing a request.
    private int mErrorCode;

    public HttpConnection(Selector selector, SocketChannel chan) throws IOException {
        mConn = new NonBlockingConnection(selector, chan, BUFFER_SIZE);
        mHandlerState = new HandlerState();
        mState = State.REQUEST_START;
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
     * @return true if more data is needed.
     */
    private boolean handleStateStep(NonBlockingConnection conn, ByteBuffer buf) {
        StateEntry entry = mStateHandlerMap.get(mState);
        if (entry == null) {
            // Unknown state.
            mErrorCode = HttpStatus.INTERNAL_SERVER_ERROR;
            mState = State.SERVER_ERROR;
            return false;
        }

        try {
            if (!entry.getHandler().handleState(conn, buf, mHandlerState)) {
                // Continue reading.  The recv() is already persistent.
                return true;
            }
            mState = entry.getNextState();
        } catch (InvalidRequestException e) {
            mErrorCode = e.getErrorCode();
            mState = State.SERVER_ERROR;
        }

        return false;
    }

    /**
     * Resets the connection state for a new request.
     */
    private void reset() {
        mState = State.REQUEST_START;
        mHandlerState.reset();
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

    static {
        mStateHandlerMap = new EnumMap<State, StateEntry>(State.class);
        mStateHandlerMap.put(State.REQUEST_START,
                new StateEntry(State.REQUEST_HEADERS, new RequestStartHandler()));
        mStateHandlerMap.put(State.REQUEST_HEADERS,
                new StateEntry(State.MESSAGE_BODY, new RequestHeaderHandler()));
        mStateHandlerMap.put(State.MESSAGE_BODY,
                new StateEntry(State.REQUEST_START, new MessageBodyHandler()));
    }
};
