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
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import com.faveset.log.Log;
import com.faveset.log.NullLog;

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

// TODO: buffer resizing can be optimized by compacting data and continuing to wrap/unwrap
// rather than flushing to the network.
class SSLNonBlockingConnection implements AsyncConnection {
    private enum ConnState {
        ACTIVE,
        CLOSED,
        // Close-related handshaking is in progress.
        CLOSING,
    }

    private enum StepState {
        CLOSE,
        TASKS,
        WAITING,
        WRAP,
        UNWRAP,
    }

    private static Executor sSerialWorkerExecutor = Executors.newSingleThreadExecutor();

    // Virtually all browsers support TLSv1.
    private static final String sSSLProtocol = "TLS";

    private static final String sTag = SSLNonBlockingConnection.class.toString();

    private static Log sLog = new NullLog();

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
                });
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

    // This is the internal buffer that is accessible to applications via getOutBuffer().
    // It is always in read mode.  (Applications will manipulate the underlying ByteBuffer
    // directly via getOutBuffer().)
    private NetBuffer mOutAppBufferInternal;

    // Always points to the NetReader for the current read.  This may point to the internal
    // mOutAppBufferInternal or some external buffer provided by the application.
    //
    // This holds unwrapped data destined to the network.
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

    // True if currently dispatching (i.e., in the dispatch() call).
    private boolean mIsDispatching;

    // This is set when an unwrap is desired (e.g., via a recv method).  It is cleared
    // by stepUnwrap().
    private boolean mRequestUnwrap;

    // This is set when a wrap is desired (e.g., via a send method or when onNetSend() is called
    // from within a dispatch loop).  It is cleared by stepWrap().
    private boolean mRequestWrap;

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
                onNetSend(conn);
            }
        };

    /**
     * @param selector
     * @param chan
     * @param bufFactory
     * @param nonBlockingTaskQueue the task queue that runs in the event thread.  Tasks will be
     * submitted when it is desired to wake up the NonBlockingConnection after an SSL task thread
     * completes.
     * @param ctx
     */
    public SSLNonBlockingConnection(Selector selector, SocketChannel chan,
            ByteBufferFactory bufFactory, SelectTaskQueue nonBlockingTaskQueue, SSLContext ctx)
            throws IOException, NoSuchAlgorithmException {
        mConnState = ConnState.ACTIVE;

        mSelectTaskQueue = nonBlockingTaskQueue;

        // We'll assign the internal in and out buffers using sizes from the
        // SSLEngine's SSLSession.
        mConn = new NonBlockingConnection(selector, chan, null, null);

        mSSLEngine = ctx.createSSLEngine();

        mBufFactory = bufFactory;

        mActiveState = new SSLActiveState(bufFactory, mSSLEngine);
        mHandshakeState = new SSLHandshakeState(bufFactory, mSSLEngine);

        // Start in the handshake state.
        mCurrState = mHandshakeState;
        mOtherState = mActiveState;

        SSLSession session = mSSLEngine.getSession();

        // This holds cipher data coming in from the network.
        int netSize = session.getPacketBufferSize();
        ByteBuffer inNetBuf = bufFactory.make(netSize);
        mConn.setInBufferInternal(inNetBuf);
        mInNetBuffer = new NonBlockingConnectionInNetBuffer(mConn);

        // This holds cipher data going to the network.
        ByteBuffer outNetBuf = bufFactory.make(netSize);
        mConn.setOutBufferInternal(outNetBuf);
        mOutNetBuffer = new NonBlockingConnectionOutNetBuffer(mConn);

        int appSize = session.getApplicationBufferSize();
        mInAppBuffer = NetBuffer.makeAppendBuffer(bufFactory.make(appSize));
        mOutAppBufferInternal = NetBuffer.makeReadBuffer(bufFactory.make(appSize));
        // Ensure that the internal buffer is initially empty for mOutAppReader.
        mOutAppBufferInternal.clear();

        // Set up callbacks.
        mConn.setOnCloseCallback(mNetCloseCallback);
        mConn.setOnErrorCallback(mNetErrorCallback);

        // Initially, we have an empty app buffer so that no wrapping will occcur.
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

    /**
     * The connection will be put into a CLOSING state and close handshaking will begin.
     *
     * @throws SSLException
     */
    @Override
    public void close() throws SSLException {
        switch (mConnState) {
            case CLOSING:
            case CLOSED:
                return;

            default:
                break;
        }

        mConnState = ConnState.CLOSING;

        // closeInbound() will be called after the SSLEngine has time to wrap close-related
        // handshaking data that occurs as a result of closeOutbound().
        mSSLEngine.closeOutbound();

        // According to the documentation, only wrap needs to be called.  Thus, we do not worry
        // about the NEED_TASK status, which is not handled by startHandshake.
        if (!mIsDispatching) {
            startHandshake();
        }
    }

    /**
     * Closes the connection immediately without attempting to handshake the
     * closure.  This should normally be called if the engine is in the SSLEngine
     * detects the closed state or if the NonBlockingConnection itself is closed,
     * which would prevent handshaking from proceeding.
     *
     * @throws IOException
     */
    private void closeImmediately() throws IOException {
        System.out.println("closeImmediately() start");
        if (mConnState == ConnState.CLOSED) {
            System.out.println("closeImmediately(): already closed");
            return;
        }

        // closeOutbound() is also called in close().  This will be vacuous in that case.
        mSSLEngine.closeOutbound();

        try {
            mSSLEngine.closeInbound();
        } catch (SSLException e) {
            // Just log the close notify error if possible, but continue gracefully.
            sLog.w(sTag, e.toString());
        }

        mConnState = ConnState.CLOSED;

        mConn.close();

        mAppRecvCallback = null;
        mAppSendCallback = null;

        // Clear possible external references.
        mOutAppReader = null;
    }

    private void dispatch(StepState initState) {
        try {
            mIsDispatching = true;

            dispatchImpl(initState);

            mIsDispatching = false;
        } catch (IOException e) {
            mIsDispatching = false;

            onNetError(null, e.toString());
        }
    }

    private void dispatchImpl(StepState initState) throws IOException {
        // We use a two-phase commit when the WAITING state is reached to determine whether we are
        // really done.  doneUnwrapOrWrap is true after an unwrap or wrap step returns nextState WAITING
        // (i.e., unwrapping is done).  It signals that the first phase has been completed.
        boolean doneUnwrapOrWrap = false;

        StepState state = initState;
        do {
            StepState nextState = step(state);
            if (nextState == state) {
                // No change.  Stop cycles from occurring.
                break;
            }

            if (nextState == StepState.CLOSE) {
                // We cannot handshake further.  Shut down everything before the callback, which
                // might attempt to call close().
                closeImmediately();

                if (mAppCloseCallback != null) {
                    mAppCloseCallback.onClose(this);
                }
                break;
            }

            if (nextState == StepState.TASKS) {
                // mTasksDoneCallback will be called when all tasks are completed.
                // It will be called in another thread.  mTasksDoneCallback then
                // calls onTasksDone(), which always runs in the selector thread (the same
                // as this event handler).  Thus, onTasksDone() will only be called after this
                // method exits.
                if (scheduleHandshakeTasks(mTasksDoneCallback)) {
                    // Pause all persistent events until the task is completed.
                    mConn.cancelRecv();
                    break;
                }
                // We don't worry about mRequestWrap, since the mTasksDoneCallback will resume
                // dispatching when ready.

                // Otherwise, no handshake tasks are scheduled.  This can happen if a task
                // is already outstanding and an asynchronous send callback arrives, since we
                // do not cancel outstanding sends.  Just wait.
                break;
            }

            if (nextState == StepState.WAITING) {
                // Resolve any callbacks that might have occurred.  Callbacks set wrap and unwrap
                // requests.  stepUnwrap and stepWrap clear these requests.
                if (mRequestWrap) {
                    nextState = StepState.WRAP;
                } else if (mRequestUnwrap) {
                    nextState = StepState.UNWRAP;
                } else {
                    if (!doneUnwrapOrWrap && state == StepState.WRAP) {
                        // Wrapping is done.
                        doneUnwrapOrWrap = true;
                        nextState = StepState.UNWRAP;
                    } else if (!doneUnwrapOrWrap && state == StepState.UNWRAP) {
                        // Unwrapping is done.
                        doneUnwrapOrWrap = true;
                        nextState = StepState.WRAP;
                    } else {
                        // We've completely unwrapped and wrapped as much as possible.  We're done.
                        break;
                    }
                }
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

    private void onNetClose(AsyncConnection conn) {
        // We cannot handshake with a closed connection, so just shut everything down.
        try {
            closeImmediately();
        } catch (IOException e) {
            if (mAppErrorCallback != null) {
                mAppErrorCallback.onError(this, e.getMessage());
                return;
            }
        }

        // Pass up the call chain to adhere to the close protocol.
        if (mAppCloseCallback != null) {
            mAppCloseCallback.onClose(this);
        }
    }

    private void onNetError(AsyncConnection conn, String reason) {
        if (mAppErrorCallback != null) {
            mAppErrorCallback.onError(this, reason);
        }
    }

    private void onNetRecv(AsyncConnection conn, ByteBuffer buf) {
        // The onRecv callback will flip buf automatically.  Take that into account and continue
        // reading where we left off.
        mInNetBuffer.setRead();
        mInNetBuffer.rewindRead();

        dispatch(StepState.UNWRAP);
    }

    /**
     * Called when new data must be wrapped for sending to the network.  It
     * is usually called after data is completely sent.
     */
    private void onNetSend(AsyncConnection conn) {
        // AsyncConnection only triggers this callback when the outgoing net buffer has been
        // completely flushed.  Update the NetBuffer state to ready it for future writes.
        mOutNetBuffer.setAppend();
        mOutNetBuffer.clear();

        if (!mIsDispatching) {
            dispatch(StepState.WRAP);
        } else {
            mRequestWrap = true;
        }
    }

    /**
     * NOTE: this must always be called from the main selector thread.
     */
    private void onTasksDone() {
        if (mConnState == ConnState.CLOSED) {
            return;
        }

        // Restore the receive handler, which was paused for task processing.
        mConn.recvAppendPersistent(mNetRecvCallback);

        startHandshake();
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
        mRequestUnwrap = true;
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
        NetReader reader = NetBuffer.makeReadBuffer(buf);
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
        mRequestWrap = true;
    }

    /**
     * For now, this is just equivalent to send().
     *
     * TODO: implement this?
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

    public static void setLog(Log log) {
        sLog = log;
    }

    public void start() {
        if (mConnState == ConnState.CLOSED) {
            return;
        }

        // Initialize network buffer state for receiving data via the underlying connection.
        // Configure everything before the actual recv call, in case some data is populated
        // immediately.
        mInNetBuffer.setAppend();
        mInNetBuffer.clear();

        mConn.recvAppendPersistent(mNetRecvCallback);

        startHandshake();
    }

    /**
     * Queries the handshake status and wraps or unwraps accordingly.  NEED_TASK requests are NOT
     * handled.
     */
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

    private StepState step(StepState state) throws SSLException {
        switch (state) {
            case CLOSE:
            case TASKS:
            case WAITING:
                break;

            case UNWRAP:
                return stepUnwrap();

            case WRAP:
                return stepWrap();
        }

        return state;
    }

    /**
     * @return the next StepState as a result of the step.
     *
     * @throws SSLException
     */
    private StepState stepUnwrap() throws SSLException {
        do {
            // We are handling unwraps here.  Because we continue wrapping after draining
            // mInAppBuffer, it's necessary to clear the flag on each iteration; otherwise,
            // a nested recv call's unwrap request flag will not be turned off despite
            // being handled by the loop.
            mRequestUnwrap = false;

            SSLState.OpResult result = mCurrState.stepUnwrap(mInNetBuffer, mInAppBuffer);
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
                    mInAppBuffer.flipRead();

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
                    mInAppBuffer.flipAppend();

                    // An AsyncConnection assumes that content in the buffer is fully consumed by
                    // callbacks.
                    mInAppBuffer.clear();

                    // Continue unwrapping now that the app buffer is drained.
                    break;

                case ENGINE_CLOSE:
                    return StepState.CLOSE;

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

                case UNWRAP_LOAD_SRC_BUFFER:
                    mInNetBuffer.flipAppend();

                    /* A persistent receive is already configured.  Wait for it. */
                    return StepState.WAITING;
            }
        } while (true);
    }

    /**
     * @return the next StepState as a result of the step.
     *
     * @throws SSLException
     */
    private StepState stepWrap() throws SSLException {
        // See if we are done reading the source buffer and have flushed its wrapped content to the
        // network.  Such a situation indicates send completion, which triggers a callback.
        //
        // This should be checked before we wrap any further.
        if (mAppSendCallback != null &&
                mOutNetBuffer.isEmpty() &&
                mOutAppReader.isEmpty()) {
            // We have encountered send completion: all outgoing app data has been wrapped
            // and the send buffer has been flushed.  Prepare for the send callback, which is never
            // persistent.
            AsyncConnection.OnSendCallback callback = mAppSendCallback;
            mAppSendCallback = null;

            callback.onSend(this);
            return StepState.WAITING;
        }

        // We do this after the mAppSendCallback section above to respect send ordering (data
        // comes before connection close).
        //
        // NOTE: there is a possibility that this could be reached while waiting for onNetRecv()
        // data to unwrap.  It doesn't seem to be a problem, since we are closing and since the
        // engine reports isOutBoundDone() == true.
        if (mConnState == ConnState.CLOSING && mSSLEngine.isOutboundDone()) {
            // We're finally done closing.
            return StepState.CLOSE;
        }

        // We are handling wraps here.
        mRequestWrap = false;

        do {

            SSLState.OpResult result = mCurrState.stepWrap(mOutAppReader, mOutNetBuffer);
            switch (result) {
                case NONE:
                    return StepState.WAITING;

                case DRAIN_DEST_BUFFER:
                    if (mOutNetBuffer.isEmpty()) {
                        // We have nothing left to do.
                        return StepState.WAITING;
                    }

                    mOutNetBuffer.flipRead();

                    // NOTE: send may complete immediately, which could lead to a nested call to
                    // dispatch.
                    mConn.send(mNetSendCallback);
                    return StepState.WAITING;

                case ENGINE_CLOSE:
                    return StepState.CLOSE;

                case SCHEDULE_TASKS:
                    // Drain the buffer concurrently if necessary.
                    if (!mOutNetBuffer.isEmpty()) {
                        mOutNetBuffer.flipRead();

                        // NOTE: send may complete immediately, which could lead to a nested call to
                        // dispatch.
                        mConn.send(mNetSendCallback);
                    }

                    return StepState.TASKS;

                case SCHEDULE_UNWRAP:
                    return StepState.UNWRAP;

                case SCHEDULE_WRAP:
                    // We're already wrapping.
                    break;

                case STATE_CHANGE:
                    swapState();

                    // We don't have to worry about flushing any data at this point in time, because SSL
                    // states will wrap as much as possible before flushing any residual data.
                    //
                    // It's safe to continue wrapping.
                    break;

                case UNWRAP_LOAD_SRC_BUFFER:
                    // This is never called.
                    throw new RuntimeException(
                            "UNWRAP_LOAD_SRC_BUFFER should not occur when wrapping");
            }
        } while (true);
    }

    private void swapState() {
        SSLState tmp = mCurrState;
        mCurrState = mOtherState;
        mOtherState = tmp;
    }
}
