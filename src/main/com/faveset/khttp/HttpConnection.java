// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import com.faveset.log.Log;
import com.faveset.log.NullLog;

// TODO: what happens on error.  Does the connection close?

/**
 * Handles an HTTP request in non-blocking fashion.
 *
 * This follows the protocol outlined in NonBlockingConnection for handling
 * closes: bubble up to the higher level via OnCloseCallback and expect the
 * upper level to eventually call HttpConnection's close().
 */
class HttpConnection {
    /**
     * Called when the HttpConnection is closed.  The caller should call
     * close() to clean up.
     */
    public interface OnCloseCallback {
        void onClose(HttpConnection conn);
    }

    private enum State {
        // Request start line.
        REQUEST_START,
        REQUEST_HEADERS,
        MESSAGE_BODY,
        // Sending response.  Receives will be blocked until
        // this state is complete.
        RESPONSE_SEND,
        SERVER_ERROR,

        // The state will be manually assigned by a callback.  If the callback
        // does not assign a state, the state will remain unchanged.
        // The receive handler will exit once this is encountered during
        // a transition.  (A selector can then call the receive handler again
        // if so configured.)
        MANUAL,
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

    private static final String sTag = HttpConnection.class.toString();

    // Creates direct ByteBuffers with size
    // Constants.NON_BLOCKING_CONNECTION_BUFFER_SIZE.
    private static ByteBufferPool sByteBufferPool =
        new ByteBufferPool(Constants.NON_BLOCKING_CONNECTION_BUFFER_SIZE,
                true, Constants.HTTP_CONNECTION_BUFFER_POOL_SIZE);

    private static final EnumMap<State, StateEntry> mStateHandlerMap;

    // NOTE: this is not thread-safe.
    private static DateFormatter sDateFormatter = new DateFormatter();

    private Log mLog = new NullLog();

    private Map<String, HttpHandler> mHttpHandlerMap;

    private NonBlockingConnection mConn;

    private HandlerState mHandlerState;

    private State mState;

    private OnCloseCallback mOnCloseCallback;

    private NonBlockingConnection.OnCloseCallback mNbcCloseCallback =
        new NonBlockingConnection.OnCloseCallback() {
            @Override
            public void onClose(NonBlockingConnection conn) {
                handleClose(conn);
            }
        };

    private NonBlockingConnection.OnErrorCallback mNbcErrorCallback =
        new NonBlockingConnection.OnErrorCallback() {
            @Override
            public void onError(NonBlockingConnection conn, String reason) {
                handleError(conn, reason);
            }
        };

    private NonBlockingConnection.OnRecvCallback mNbcRecvCallback =
        new NonBlockingConnection.OnRecvCallback() {
            @Override
            public void onRecv(NonBlockingConnection conn, ByteBuffer buf) {
                handleRecv(conn, buf);
            }
        };

    private HandlerState.OnRequestCallback mRequestCallback =
        new HandlerState.OnRequestCallback() {
            @Override
            public boolean onRequest(HttpRequest req, ByteBuffer data, ResponseWriter w) {
                return handleRequest(req, data, w);
            }
        };

    private ResponseWriter.OnSendCallback mSendResponseCallback =
        new ResponseWriter.OnSendCallback() {
            @Override
            public void onSend() {
                handleSendResponse();
            }
        };

    /**
     * The HttpConnection will manage registration of selector keys.
     * A SelectorHandler will be attached to selector keys for handling
     * by callers.
     */
    public HttpConnection(Selector selector, SocketChannel chan) throws IOException {
        mConn = new NonBlockingConnection(selector, chan, sByteBufferPool);

        mHandlerState = new HandlerState().setOnRequestCallback(mRequestCallback);
        mState = State.REQUEST_START;
    }

    /**
     * Closes the connection and releases all resources.
     */
    public void close() throws IOException {
        mConn.close();
    }

    /**
     * Performs initial configuration of the ResponseWriter according
     * to the request.
     */
    private void configureWriter(HttpRequest req, ResponseWriter w) {
        // Check for Connection close.
        Set<String> values = req.getHeaders().getValueSet(HeaderField.General.CONNECTION);
        w.setCloseConnection(values.contains(HeaderToken.CLOSE));

        int minorVersion = req.getMinorVersion();
        w.setHttpMinorVersion(minorVersion);

        if (minorVersion == 0) {
            // HTTP/1.0 does not support persistent connections, but we can
            // check for HTTP/1.0 Keep-Alive support.
            if (!values.contains(HeaderToken.KEEP_ALIVE)) {
                w.setCloseConnection(true);
            } else {
                w.getHeadersBuilder().set(HeaderField.General.CONNECTION, HeaderToken.KEEP_ALIVE);
            }
        }
    }

    /**
     * @return the underlying NonBlockingConnection.
     */
    public NonBlockingConnection getNonBlockingConnection() {
        return mConn;
    }

    /**
     * Handle read closes.  This adheres to our protocol described at the
     * top of the file.
     */
    private void handleClose(NonBlockingConnection conn) {
        if (mOnCloseCallback != null) {
            mOnCloseCallback.onClose(this);
        }
    }

    private void handleError(NonBlockingConnection conn, String reason) {
        mLog.e(sTag, "error with connection, closing: " + reason);

        handleClose(conn);
    }

    private void handleRecv(NonBlockingConnection conn, ByteBuffer buf) {
        boolean done = false;
        do {
            done = handleStateStep(conn, buf);
        } while (!done);
    }

    private boolean handleRequest(HttpRequest req, ByteBuffer data, ResponseWriter w) {
        // Prepare the writer at a general level, for both errors and successes.
        configureWriter(req, w);

        // By default, the response takes the current date.
        sDateFormatter.update();
        w.getHeadersBuilder().set(HeaderField.General.DATE,
                sDateFormatter.getRFC1123String());

        String uri = req.getUri();
        HttpHandler handler = mHttpHandlerMap.get(uri);
        if (handler == null) {
            sendErrorResponse(HttpStatus.NOT_FOUND);
            // Transition to a new state to handle the send.
            return true;
        }

        handler.onRequest(req, w);

        switch (req.getBodyType()) {
            case READ:
                // We need to read the body in its entirety.
                // TODO

            case IGNORE:
            default:
                // It's safe to send the response right now.
                sendResponse(mConn, w);
                return true;
        }
    }

    /**
     * Called after the HTTP response has been sent to the client.  This
     * reconfigures the HttpConnection to listen for another request unless
     * specified otherwise by the ResponseWriter.
     */
    private void handleSendResponse() {
        // We're done sending the response; log the result.
        String remoteAddrStr = mConn.socketChannel().socket().getInetAddress().toString();
        ResponseWriter w = mHandlerState.getResponseWriter();
        logRequest(mHandlerState.getRequestBuilder(), remoteAddrStr,
                w.getStatus(), w.getSentCount());

        if (w.getCloseConnection()) {
            // We are done, so clean up.
            handleClose(mConn);
            return;
        }

        mState = State.REQUEST_START;

        // Restart the receive, now that we're at the start state.
        mConn.recvPersistent(mNbcRecvCallback);
    }

    /**
     * Performs one step for the state machine.
     *
     * @return true if more data needs to be read into buf.  The receive
     * handler will finish as a result.
     */
    private boolean handleStateStep(NonBlockingConnection conn, ByteBuffer buf) {
        StateEntry entry = mStateHandlerMap.get(mState);
        if (entry == null) {
            mLog.e(sTag, "invalid HTTP state: " + mState);

            sendErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR);
            // Unknown state.  Stop state steps until we send the response.
            return true;
        }

        try {
            if (!entry.getHandler().handleState(conn, buf, mHandlerState)) {
                // Continue reading new data from the connection.  The recv()
                // is already persistent.
                return true;
            }

            State nextState = entry.getNextState();
            if (nextState == State.MANUAL) {
                // Hold back on advancing steps automatically so that the
                // receive handler can be called again (if configured).
                //
                // This gives an opportunity to pause receive handling when,
                // for example, sending a response.
                return true;
            }

            mState = nextState;
        } catch (InvalidRequestException e) {
            sendErrorResponse(e.getErrorCode());
            // Stop stepping states until we send the response.
            return true;
        }

        return false;
    }

    /**
     * Logs the request using the response code httpStatus.
     *
     * @param req may be null if undetermined (due to error)
     * @param httpStatus
     * @param remoteAddrStr remote address
     * @param responseLen number of bytes for the response.
     */
    private void logRequest(HttpRequest req,
            String remoteAddrStr, int httpStatus, long responseLen) {
        String reqStr;
        if (req != null) {
            reqStr = req.toString();
        } else {
            reqStr = "";
        }

        sDateFormatter.update();
        String logTime = sDateFormatter.getClfString();
        String s = String.format("%s - - [%s] \"%s\" %d %d",
                remoteAddrStr, logTime, reqStr, httpStatus, responseLen);

        mLog.i(sTag, s);
    }

    /**
     * Convenience method for sending error responses.  The state will
     * change to RESPONSE_SEND.  Thus, this should be called in a transition
     * to a MANUAL state or outside a state machine callback.
     */
    private void sendErrorResponse(int errorCode) {
        ResponseWriter writer = mHandlerState.getResponseWriter();
        writer.writeHeader(errorCode);

        sendResponse(mConn, writer);
    }

    /**
     * Sends w over conn and configures handleSendResponse to handle the
     * send completion callback.
     *
     * The state will change to RESPONSE_SEND.  Thus, this should be called
     * in a transition to a MANUAL state or outside a state machine callback.
     *
     * Receive callbacks will be blocked until send completion.
     */
    private void sendResponse(NonBlockingConnection conn, ResponseWriter w) {
        mState = State.RESPONSE_SEND;

        // Turn off receive callbacks, since the state machine is in a send
        // state.
        conn.cancelRecv();

        w.send(conn, mSendResponseCallback);
    }

    /**
     * Configures the HttpConnection to log output to log.
     */
    public void setLog(Log log) {
        mLog = log;
    }

    /**
     * Assigns the callback that will be called when the connection is closed.
     *
     * Use this for cleaning up any external dependencies on the
     * HttpConnection.
     *
     * The recipient should close the HttpConnection to clean up.
     */
    public void setOnCloseCallback(OnCloseCallback cb) {
        mOnCloseCallback = cb;
    }

    /**
     * Start HttpConnection processing.
     *
     * @param handlers maps uris to HttpHandlers for handling requests.
     */
    public void start(Map<String, HttpHandler> handlers) {
        mHttpHandlerMap = handlers;

        // Configure all callbacks.
        mConn.setOnCloseCallback(mNbcCloseCallback);
        mConn.setOnErrorCallback(mNbcErrorCallback);

        mConn.recvPersistent(mNbcRecvCallback);
    }

    static {
        mStateHandlerMap = new EnumMap<State, StateEntry>(State.class);

        mStateHandlerMap.put(State.REQUEST_START,
                new StateEntry(State.REQUEST_HEADERS, new RequestStartHandler()));
        mStateHandlerMap.put(State.REQUEST_HEADERS,
                new StateEntry(State.MESSAGE_BODY, new RequestHeaderHandler()));

        // The handleRequest handler will configure the state.
        mStateHandlerMap.put(State.MESSAGE_BODY,
                new StateEntry(State.MANUAL, new MessageBodyHandler()));
    }
};
