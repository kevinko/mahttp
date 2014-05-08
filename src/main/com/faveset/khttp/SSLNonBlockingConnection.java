// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSession;

/**
 * NOTE: Versions of Android before 3.0 have flawed support for SSLEngine.
 * This should only be used with Android 4.0+ targets.
 *
 * See https://code.google.com/p/android/issues/detail?id=12955
 *
 * We assume that all public methods are only callable when the
 * SSLNonBlockingConnection is not handshaking.  This is enforced by
 * only calling application callbacks during the ACTIVE state.
 */

// TODO: check buffer resizing.  Do you need to compact anything?
// TODO: hide prepareRead/prepareAppend in NetBuffer.
class SSLNonBlockingConnection implements AsyncConnection {
    private enum ConnState {
        ACTIVE,
        CLOSED,
        CLOSING,
    }

    private enum StepState {
        CLOSE,
        WAITING,
        WRAP,
        UNWRAP,
    }

    private static Executor sSerialWorkerExecutor = Executors.newSingleThreadExecutor();

    // Virtually all browsers support TLSv1.
    private static final String sSSLProtocol = "TLS";

    // This should be scheduled after any executor tasks to signal completion
    // and restore events that were paused prior to execution.
    private Runnable mTasksDoneCallback =
        new Runnable() {
            @Override
            public void run() {
                // Run in the event selector loop.
                mSelectTaskQueue.execute(new Runnable() {
                    @Override
                    public void run() {
                        onTasksDone();
                    }
                }
            }
        };

    private ConnState mConnState;

    // This is used for queueing up an action to the main event loop
    // from a worker thread.  Tasks will run in the thread that loops
    // over the selector.
    private SelectTaskQueue mSelectTaskQueue;

    private NonBlockingConnection mConn;
    private SSLEngine mSSLEngine;

    private ByteBufferFactory mBufFactory;

    private SSLActiveState mActiveState;
    private SSLHandshakeState mHandshakeState;

    // We toggle between the two states (active/handshake) using these references.
    private SSLState mCurrState;
    private SSLState mOtherState;

    // Wrapped data coming in from the network.
    private NetBuffer mInNetBuffer;
    // Wrapped data going out to the network.
    private NetBuffer mOutNetBuffer;

    // Unwrapped data from the network.
    private NetBuffer mInAppBuffer;

    // Unwrapped data destined to the network.
    private NetBuffer mOutAppBufferInternal;

    // Always points to the NetReader for the current read.  This may point to the internal
    // mOutAppBufferInternal or some external buffer provided by the application.
    private NetReader mOutAppReader;

    // These callbacks are provided by the application
    private AsyncConnection.OnCloseCallback mAppCloseCallback;
    private AsyncConnection.OnErrorCallback mAppErrorCallback;
    private AsyncConnection.OnRecvCallback mAppRecvCallback;
    private AsyncConnection.OnSendCallback mAppSendCallback;

    // true if the app requested persistent receive callbacks.
    private boolean mAppRecvIsPersistent;

    // true if the SSLNonBlockingConnection is awaiting an explicit recv call from the app layer
    // for unwrapping to continue.  The underlying NonBlockingConnection's receive will be inactive
    // when this is true.  It will be rescheduled by the recv method.
    private boolean mNeedsAppRecv;

    // This is set when an application desires an unwrap via a recv method.
    private boolean mAppRequestUnwrap;

    // This is set when an application desires a wrap via a send method.
    private boolean mAppRequestWrap;

    private AsyncConnection.OnCloseCallback mNetCloseCallback =
        new AsyncConnection.OnCloseCallback() {
            @Override
            public void onClose(AsyncConnection conn) {
                onNetClose(conn);
            }
        };

    private AsyncConnection.OnErrorCallback mNetErrorCallback =
        new AsyncConnection.OnErrorCallback() {
            @Override
            public void onError(AsyncConnection conn, String reason) {
                onNetError(conn, reason);
            }
        };

    private AsyncConnection.OnRecvCallback mNetRecvCallback =
        new AsyncConnection.OnRecvCallback() {
            @Override
            public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                onNetRecv(conn, buf);
            }
        };

    private AsyncConnection.OnSendCallback mNetSendCallback =
        new AsyncConnection.OnSendCallback() {
            @Override
            public void onSend(AsyncConnection conn) {
                onNetSend(conn, buf);
            }
        };

    /**
     * @param selector
     * @param chan
     * @param bufFactory
     * @param nonBlockingTaskQueue the task queue that runs in the event thread.  Tasks will be
     * submitted when it is desired to wake up the NonBlockingConnection after an SSL task thread
     * completes.
     */
    public SSLNonBlockingConnection(Selector selector, SocketChannel chan,
            ByteBufferFactory bufFactory, SelectTaskQueue nonBlockingTaskQueue)
            throws IOException, NoSuchAlgorithmException {
        mConnState = ACTIVE;

        mSelectTaskQueue = nonBlockingTaskQueue;

        // We'll assign the internal in and out buffers using sizes from the
        // SSLSession.
        mConn = new NonBlockingConnection(selector, chan, null, null);

        SSLContext ctx = SSLContext.getInstance(sSSLProtocol);
        mSSLEngine = ctx.createSSLEngine();

        mBufFactory = bufFactory;
        mActiveState = new SSLActiveState();
        mHandshakeState = new SSLHandshakeState();

        // Start in the handshake state.
        mCurrState = mHandshakeState;
        mOtherState = mActiveState;

        SSLSession session = mSSLEngine.getSession();

        // This holds cipher data coming in from the network.
        int netSize = session.getPacketBufferSize();
        ByteBuffer inNetBuf = bufFactory.make(netSize);
        mConn.setInBufferInternal(inNetBuf);
        mInNetBuffer = new NonBlockingConnectionInNetBuffer(mConn, inNetBuf);

        // This holds cipher data going to the network.
        ByteBuffer outNetBuf = bufFactory.make(netSize);
        mConn.setOutBufferInternal(outNetBuf);
        mOutNetBuffer = new NonBlockingConnectionOutNetBuffer(mConn, outNetBuf);

        int appSize = session.getApplicationBufferSize();
        mInAppBuffer = new NetBuffer(bufFactory.make(appSize));
        mOutAppBufferInternal = new NetBuffer(bufFactory.make(appSize));

        // Set up callbacks.
        mConn.setOnCloseCallback(mNetCloseCallback);
        mConn.setOnErrorCallback(mNetErrorCallback);

        // Initially, we have an empty app buffer so that no wrapping will occcur.
        mOutAppBufferInternal.prepareRead();
        mOutAppReader = mOutAppBufferInternal;
    }

    @Override
    public OnRecvCallback cancelRecv() {
        // We don't cancel mConn's receive, because some handshaking may still be occurring.  That
        // will be taken care of in the unwrap step.
        OnRecvCallback result = mAppRecvCallback;
        mAppRecvCallback = null;

        return result;
    }

    @Override
    public void close() throws IOException {
        switch (mConnState) {
            case CLOSING:
            case CLOSED:
                return;

            default:
                break;
        }

        mSSLEngine.closeInbound();
        mSSLEngine.closeOutbound();

        startHandshake();
    }

    /**
     * Closes the connection immediately without attempting to handshake the
     * closure.  This should normally be called if the engine is in the SSLEngine
     * detects the closed state or if the NonBlockingConnection itself is closed,
     * which would prevent handshaking from proceeding.
     */
    private void closeImmediately() throws IOException {
        if (mConnState == CLOSED) {
            return;
        }

        mState = ConnState.CLOSED;

        mConn.close();

        mAppRecvCallback = null;
        mAppSendCallback = null;

        // Clear possible external references.
        mOutAppReader = null;
    }

    private void dispatch(StepState initState) {
        StepState state = initState;
        do {
            StepState nextState = step(state);
            if (nextState == state) {
                // No change.  Stop cycles from occurring.
                break;
            }

            if (nextState == WAITING) {
                // Resolve any callbacks that might have occurred.  Callbacks set oprequests.
                // stepUnwrap and stepWrap clear the oprequests.
                if (mAppRequestWrap) {
                    nextState = StepState.WRAP;
                } else if (mAppRequestUnwrap) {
                    nextState = StepState.UNWRAP;
                } else {
                    break;
                }
            }

            if (nextState == CLOSE) {
                if (mAppCloseCallback != null) {
                    mAppCloseCallback.onClose(this);
                }

                closeImmediately();
                break;
            }

            if (nextState == TASKS) {
                // mTasksDoneCallback will be called when all tasks are completed.
                // It will be called in another thread.  However, mTasksDoneCallback
                // calls onTaskDone(), which always runs in the selector thread (the same
                // as this event handler).
                if (scheduleHandshakeTasks(mTasksDoneCallback)) {
                    // Pause all persistent events until the task is completed.
                    mConn.cancelRecv();
                    break;
                }

                // Otherwise, no handshake tasks are scheduled.  This can happen if a task
                // is already outstanding and an asynchronous send callback arrives, since we
                // do not cancel outstanding sends.  Just wait.
                break;
            }

            state = nextState;
        } while (true);
    }

    @Override
    public ByteBuffer getOutBuffer() {
        return mOutAppBufferInternal.getByteBuffer();
    }

    /**
     * Returns the underlying SSLEngine for configuration of parameters.
     * Configuration must only occur before any send or receive method is
     * called.
     */
    public SSLEngine getSSLEngine() {
        return mSSLEngine;
    }

    private void onNetRecv(AsyncConnection conn, ByteBuffer buf) {
        dispatch(UNWRAP);
    }

    /**
     * Called when new data must be wrapped for sending to the network.  It
     * is usually called after data is completely sent.
     */
    private void onNetSend(AsyncConnection conn) {
        dispatch(WRAP);
    }

    /**
     * NOTE: this must always be called from the main selector thread.
     */
    private void onTaskDone() {
        start();
    }

    @Override
    public void recv(OnRecvCallback callback) throws IllegalArgumentException {
        recvImpl(callback, false);
    }

    private void recvImpl(OnRecvCallback callback, boolean isPersistent)
        throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        mAppRecvCallback = callback;
        mAppRecvIsPersistent = isPersistent;

        if (mNeedsAppRecv) {
            mNeedsAppRecv = false;
            mConn.recvAppendPersistent(mNetRecvCallback);
        }

        // Receives are unwraps.
        mAppRequestUnwrap = true;
    }

    @Override
    public void recvPersistent(OnRecvCallback callback) throws IllegalArgumentException {
        recvImpl(callback, true);
    }

    /**
     * Send calls are destructive and take the place of any outstanding send.
     */
    @Override
    public void send(OnSendCallback callback) throws IllegalArgumentException {
        sendImpl(callback, mOutAppBufferInternal);
    }

    @Override
    public void send(OnSendCallback callback, ByteBuffer buf) throws IllegalArgumentException {
        NetReader reader = NetBuffer.makeReader(buf);
        sendImpl(callback, reader);
    }

    @Override
    public void send(OnSendCallback callback, ByteBuffer[] bufs, long bufsRemaining)
            throws IllegalArgumentException {
        NetReader reader = new ArrayNetReader(bufs);
        sendImpl(callback, reader);
    }

    /**
     * @throws IllegalArgumentException if callback is null
     */
    private void sendImpl(OnSendCallback callback, NetReader reader)
            throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        mAppSendCallback = callback;
        mOutAppReader = reader;

        // Sending is wrapping.
        mAppRequestWrap = true;
    }

    /**
     * For now, this is just equivalent to send().
     */
    @Override
    public void sendPartial(OnSendCallback callback) throws IllegalArgumentException {
        send(callback);
    }

    @Override
    public AsyncConnection setOnCloseCallback(OnCloseCallback callback) {
        mAppCloseCallback = callback;
        return this;
    }

    @Override
    public AsyncConnection setOnErrorCallback(OnErrorCallback callback) {
        mAppErrorCallback = callback;
        return this;
    }

    @Override
    public SocketChannel socketChannel() {
        return mConn.socketChannel();
    }

    /**
     * @param onTasksDoneCallback will be called after all engine tasks
     * are completed.  It is only called if a task is scheduled.
     *
     * @return true if any tasks were scheduled
     */
    private boolean scheduleHandshakeTasks(Runnable onTasksDoneCallback) {
        boolean hasTask = false;
        do {
            Runnable task = mSSLEngine.getDelegatedTask();
            if (task == null) {
                break;
            }
            sSerialWorkerExecutor.execute(task);
            hasTask = true;
        } while (true);

        if (hasTask) {
            // Re-schedule after tasks have completed.
            sSerialWorkerExecutor.execute(onTasksDoneCallback);
        }

        return hasTask;
    }

    public void start() {
        if (mConnState == CLOSED) {
            return;
        }

        mConn.recvAppendPersistent(mNetRecvCallback);

        startHandshake();
    }

    private void startHandshake() {
        switch (mSSLEngine.getHandshakeStatus()) {
            case FINISHED:
            case NEED_TASK:
            case NOT_HANDSHAKING:
                // Quietly ignore and let the receive callback trigger further
                // events.
                break;

            case NEED_UNWRAP:
                dispatch(StepState.UNWRAP);
                return;

            case NEED_WRAP:
                dispatch(StepState.WRAP);
                return;
        }
    }

    private StepState step(StepState state) {
        switch (state) {
            case CLOSE:
            case WAITING:
                return state;

            case UNWRAP:
                return stepUnwrap();

            case WRAP:
                return stepWrap();
        }
    }

    /**
     * @return the next StepState as a result of the step.
     */
    private StepState stepUnwrap() {
        do {
            // We are handling unwraps here.  Because we continue wrapping after draining
            // mInAppBuffer, it's necessary to clear the flag on each iteration; otherwise,
            // a nested recv call's unwrap request flag will not be turned off despite
            // being handled by the loop.
            mAppRequestUnwrap = false;

            mInNetBuffer.prepareRead();
            mInAppBuffer.prepareAppend();

            OpResult result = mCurrState.stepUnwrap(mInNetBuffer, mInAppBuffer);
            switch (result) {
                case NONE:
                    return StepState.WAITING;

                case DRAIN_DEST_BUFFER:
                    if (mAppRecvCallback == null) {
                        // Just pause until awoken by an explicit recv() from the app layer.
                        mConn.cancelRecv();

                        // This flag will signal that mConn's persistent receive needs to be
                        // rescheduled.
                        mNeedsAppRecv = true;

                        return StepState.WAITING;
                    }

                    // Otherwise, it's safe to flush mInAppBuffer to the app layer's recv callback.
                    mInAppBuffer.prepareRead();

                    AsyncConnection.OnRecvCallback callback = mAppRecvCallback;

                    // Manage the callback's persistent state before calling so that the callback
                    // can reconfigure a recv if desired.
                    if (!mAppRecvIsPersistent) {
                        mAppRecvCallback = null;
                    }

                    callback.onRecv(this, mInAppBuffer.getByteBuffer());

                    // Now, continue reading from the network and unwrapping to the app buffer.
                    // Even if app recv callbacks have stopped, continue unwrapping
                    // opportunistically until we can unwrap no further.  Any subsequent execution
                    // of this code path will exit appropriately if mAppRecvCallback == null.
                    mInAppBuffer.prepareAppend();

                    // An AsyncConnection assumes that content in the buffer is fully consumed by
                    // callbacks.
                    mInAppBuffer.clear();

                    // Continue unwrapping now that the app buffer is drained.
                    break;

                case ENGINE_CLOSE:
                    return StepState.CLOSE;

                case UNWRAP_LOAD_SRC_BUFFER:
                    mInNetBuffer.prepareAppend();

                    /* A persistent receive is already configured.  Wait for it. */
                    return StepState.WAITING;

                case SCHEDULE_TASKS:
                    return StepState.TASKS;

                case SCHEDULE_UNWRAP:
                    // We're already unwrapping.
                    break;

                case SCHEDULE_WRAP:
                    return StepState.WRAP;

                case STATE_CHANGE:
                    swapState();
                    break;
            }
        } while (true);
    }

    /**
     * @return the next StepState as a result of the step.
     */
    private StepState stepWrap() {
        // See if we are done reading the source buffer and have flushed its wrapped content to the
        // network.  Such a situation indicates send completion, which triggers a callback.
        //
        // This should be checked before we wrap any further.
        if (mAppSendCallback != null &&
                mOutNetBuffer.isEmpty() &&
                mOutAppReader.isEmpty()) {
            // Prepare for the send callback, which is never persistent.
            AsyncConnection.OnSendCallback callback = mAppSendCallback;
            mAppSendCallback = null;

            callback.onSend(this);
            return StepState.WAITING;
        }

        if (mOutNetBuffer.isEmpty()) {
            // We've flushed all wrapped data to the network (or have an empty buffer).  Prepare for new
            // data.
            mOutNetBuffer.clear();
        }

        // We are handling wraps here.
        mAppRequestWrap = false;

        do {
            mOutNetBuffer.prepareAppend();

            OpResult result = mCurrState.stepWrap(mOutAppReader, mOutNetBuffer);
            switch (result) {
                case NONE:
                    return StepState.WAITING;

                case DRAIN_DEST_BUFFER:
                    mOutNetBuffer.prepareRead();

                    mConn.send(mNetSendCallback);
                    return StepState.WAITING;

                case ENGINE_CLOSE:
                    return StepState.CLOSE;

                case UNWRAP_LOAD_SRC_BUFFER:
                    // This is never called.
                    throw RuntimeException("UNWRAP_LOAD_SRC_BUFFER should not occur when wrapping");

                case SCHEDULE_TASKS:
                    return StepState.TASKS;

                case SCHEDULE_UNWRAP:
                    return StepState.UNWRAP;

                case SCHEDULE_WRAP:
                    // We're already wrapping.
                    break;

                case STATE_CHANGE:
                    swapState();
                    break;
            }
        } while (true);
    }

    private void swapState() {
        SSLState tmp = mCurrState;
        mCurrState = mOtherState;
        mOtherState = tmp;
    }
}
