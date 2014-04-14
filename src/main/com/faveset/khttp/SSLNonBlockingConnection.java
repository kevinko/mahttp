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

// TODO: fix send callbacks (esp. in handshake wraps), like with onActiveNetRecv.
// it might not be necessary to have the send checks, if you handle send states
// directly in the send() method.  however, we use it in handlers so that we can
// switch between the various send/receive types and handle things immediately.
class SSLNonBlockingConnection implements AsyncConnection {
    private enum State {
        // Actively sending data.
        ACTIVE,
        // closeImmediately has been reached.  Once closed, a connection
        // remains closed.
        CLOSED,
        // App callbacks are never called while in this state.
        HANDSHAKE
    }

    private enum RecvType {
        NONE,
        SIMPLE,
        PERSISTENT
    }

    private enum SendType {
        NONE,
        // App data using the internal buffer.
        SINGLE,
        // A send that fires the callback whenever some data is sent.
        SINGLE_PARTIAL,
        MULTIPLE,
    }

    // Virtually all browsers support TLSv1.
    private static final String sSSLProtocol = "TLS";

    // This is used for executing SSLEngine blocking tasks in a dedicated
    // thread.  It is guaranteed to process tasks in serial order.
    private static Executor sSerialWorkerExecutor =
        Executors.newSingleThreadExecutor();

    private State mState;

    // The type of receive requested by the application or NONE if no
    // receive is active.
    private RecvType mRecvType;

    // The type of send requested by the application.  This is NONE if no
    // send is active.
    private SendType mSendType;

    // This is used for queueing up an action to the main event loop
    // from a worker thread.  Tasks will run in the thread that loops
    // over the selector.
    private SelectTaskQueue mSelectTaskQueue;

    private NonBlockingConnection mConn;

    private SSLEngine mSSLEngine;

    // True if direct ByteBuffers should be used.  This must be assigned before
    // calling allocate().
    private boolean mIsDirect;

    // Start position for the incoming net buffer.  This allows for lazy
    // compaction and partial SSL/TLS packets.  The net buffer itself is
    // mConn's internal in buffer.
    private int mInNetBufferStart;

    // The internal in buffer that holds plaintext application data unwrapped
    // from the SSLEngine.
    //
    // Unlike with NonBlockingConnection, we do not allow an app-configurable
    // in buffer.  This is necessary, because the SSLEngine dictates the
    // minimum app buffer size for unwrapping.
    private ByteBuffer mInAppBuffer;

    // Points to the active out buffer when mSendType is some sort of SINGLE
    // buffer.
    //
    // INVARIANT: non-null when a SINGLE send() is scheduled.
    private ByteBuffer mOutAppBuffer;

    // The internal out buffer that applications can use.  This will hold data
    // from the user that is directed to the network.
    private ByteBuffer mOutAppBufferInternal;

    // INVARIANT: non-null when a MULTIPLE send() is scheduled.
    private ByteBufferArray mOutAppBuffersExternal;

    private AsyncConnection.OnCloseCallback mAppCloseCallback;

    private AsyncConnection.OnErrorCallback mAppErrorCallback;

    // INVARIANT: this is null iff mRecvType == NONE.
    private AsyncConnection.OnRecvCallback mAppRecvCallback;

    // INVARIANT: this is null iff mSendType == NONE.
    private AsyncConnection.OnSendCallback mAppSendCallback;

    // This is signaled on abrupt connection close by NonBlockingConnection.
    private AsyncConnection.OnCloseCallback mNetCloseCallback =
        new AsyncConnection.OnCloseCallback() {
            @Override
            public void onClose(AsyncConnection conn) {
                onNetClose(conn);
            }
        };

    // This is signaled by an error in NonBlockingConnection.
    private AsyncConnection.OnErrorCallback mNetErrorCallback =
        new AsyncConnection.OnErrorCallback() {
            @Override
            public void onError(AsyncConnection conn, String reason) {
                onNetError(conn, reason);
            }
        };

    // The connection's net receive handler for the underlying
    // NonBlockingConnection in the ACTIVE state.
    private AsyncConnection.OnRecvCallback mActiveNetRecvCallback =
        new AsyncConnection.OnRecvCallback() {
            @Override
            public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                onActiveNetRecv(conn, buf);
            }
        };

    // NonBlockingConnection's send handler in the ACTIVE state.
    private AsyncConnection.OnSendCallback mActiveNetSendCallback =
        new AsyncConnection.OnSendCallback() {
            @Override
            public void onSend(AsyncConnection conn) {
                onActiveNetSend(conn);
            }
        };

    // The connection's net receive handler for the underlying
    // NonBlockingConnection in the HANDSHAKE state.
    private AsyncConnection.OnRecvCallback mHandshakeNetRecvCallback =
        new AsyncConnection.OnRecvCallback() {
            @Override
            public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                onHandshakeNetRecv(conn, buf);
            }
        };

    // NonBlockingConnection's send handler in the HANDSHAKE state.
    private AsyncConnection.OnSendCallback mHandshakeNetSendCallback =
        new AsyncConnection.OnSendCallback() {
            @Override
            public void onSend(AsyncConnection conn) {
                onHandshakeNetSend(conn);
            }
        };

    // This should be scheduled after executor tasks to signal completion
    // and restore any paused events.
    private Runnable mTaskDoneCallback =
        new Runnable() {
            @Override
            public void run() {
                onTaskDone();
            }
        };

    /**
     * @param selector
     * @param chan
     * @param isDirect true if direct ByteBuffers should be allocated
     * @param taskQueue for handling short non-blocking operations in the
     * primary event thread (e.g., for scheduling events).
     *
     * @throws IOException
     * @throws NoSuchAlgorithmException if SSL could not be configured
     * due to lack of security algorithm support on the platform.
     */
    public SSLNonBlockingConnection(Selector selector, SocketChannel chan,
            boolean isDirect, SelectTaskQueue taskQueue)
                throws IOException, NoSuchAlgorithmException {
        mState = HANDSHAKE;

        mRecvType = RecvType.NONE;
        mSendType = SendType.NONE;

        mSelectTaskQueue = taskQueue;

        // We'll assign the internal in and out buffers using sizes from the
        // SSLSession.
        mConn = new NonBlockingConnection(selector, chan, null, null);
        mConn.setOnCloseCallback(mNetCloseCallback);
        mConn.setOnErrorCallback(mNetErrorCallback);

        SSLContext ctx = SSLContext.getInstance(sSSLProtocol);
        mSSLEngine = ctx.createSSLEngine();

        // NOTE: This must be assigned before calling allocate().
        mIsDirect = isDirect;

        SSLSession session = mSSLEngine.getSession();

        // This holds cipher data coming in from the network.
        int netSize = session.getPacketBufferSize();
        ByteBuffer inBuf = allocate(netSize);
        mConn.setInBufferInternal(inBuf);

        // This holds cipher data going to the network.
        ByteBuffer outBuf = allocate(netSize);
        mConn.setOutBufferInternal(outBuf);

        int appSize = session.getApplicationBufferSize();
        mInAppBuffer = allocate(appSize);
        mOutAppBufferInternal = allocate(appSize);
    }

    /**
     * Allocates a ByteBuffer of given size while respecting the mIsDirect
     * flag.
     */
    private ByteBuffer allocate(int bufSize) {
        if (mIsDirect) {
            return ByteBuffer.allocateDirect(bufSize);
        }
        return ByteBuffer.allocate(bufSize);
    }

    @Override
    public OnRecvCallback cancelRecv() {
        mConn.cancelRecv();

        OnRecvCallback result = mAppRecvCallback;

        mRecvType = RecvType.NONE;
        mAppRecvCallback = null;

        return result;
    }

    /**
     * Resets all send-related state.  This must only be called from within
     * a NonBlockingConnection send callback, and it assumes that any underlying
     * sends are not persistent.
     */
    private OnSendCallback cancelSend() {
        // Underlying sends are not persistent.  Thus, we need not do anything
        // with the NonBlockingConnection.

        OnSendCallback result = mAppSendCallback;

        mSendType = SendType.NONE;
        mAppSendCallback = null;

        mOutAppBuffer = null;
        mOutAppExternalBuffers = null;

        return result;
    }

    /**
     * This performs a graceful closure.  Final close-related handshaking will
     * need to proceed via wrap/unwrap SSLEngine calls.
     */
    @Override
    public void close() throws IOException {
        mSSLEngine.closeInbound();
        mSSLEngine.closeOutbound();

        // We don't check the return, since we never go back to the active state
        // after close.
        stepHandshake(mSSLEngine.getHandshakeStatus());
    }

    /**
     * Closes the connection immediately without attempting to handshake the
     * closure.  This should normally be called when the engine enters
     * the CLOSED state or if the NonBlockingConnection is closed.
     *
     * mIsClosed will be set as a result.
     */
    private void closeImmediately() throws IOException {
        if (mState == State.CLOSED) {
            return;
        }

        mState = State.CLOSED;

        // Clear possible external references.
        mOutAppBuffer = null;
        mOutAppBuffersExternal = null;

        mConn.close();
    }

    /**
     * Restores the active state and all relevant handlers.
     */
    private void configureActiveState() {
        mState = ACTIVE;

        restoreActiveRecv(mRecvType);
        restoreActiveSend(mSendType);
    }

    private void configureHandshakeState() {
        mState = HANDSHAKE;

        // Turn off all persistent callbacks, as handshakes do not use
        // persistent methods.
        mConn.cancelRecv();
    }

    /**
     * Returns the internal application out buffer.
     */
    @Override
    public ByteBuffer getOutBuffer() {
        return mOutAppBufferInternal;
    }

    /**
     * Returns the underlying SSLEngine for configuration of parameters.
     * Configuration must only occur before any send or receive method is
     * called.
     */
    public SSLEngine getSSLEngine() {
        return mSSLEngine;
    }

    /**
     * Handles an SSLEngineResult.Status.CLOSED.  The connection will be
     * closed immediately.
     */
    private void handleEngineClose() {
        // Bubble up the close.
        if (mAppOnCloseCallback != null) {
            mAppOnCloseCallback.onClose(this);
        }

        // The engine has signaled completion, so there is nothing
        // left to do.
        closeImmediately();
    }

    /**
     * @return true if wrapping should stop for the time being.  An event will
     * be scheduled in such a case.
     */
    private boolean handleStepActiveWrapResult(SSLEngineResult result) {
        switch (result.getStatus()) {
            case BUFFER_OVERFLOW:
                // This call can change the out buffer if the SSLEngine
                // demands a change in buffer size.
                boolean needsSend = handleWrapOverflow();
                if (needsSend) {
                    ByteBuffer outBuf = mConn.getOutBuffer();
                    outBuf.flip();
                    mConn.send(mActiveNetSendCallback);
                    return true;
                }

                break;

            case BUFFER_UNDERFLOW:
                // We're out of application data.  Send to the network.
                // The application callback will be triggered on completion.
                ByteBuffer outBuf = mConn.getOutBuffer();
                outBuf.flip();
                mConn.send(mActiveNetSendCallback);
                return true;

            case CLOSED:
                handleEngineClose();
                return true;

            case OK:
                break;
        }

        return false;
    }

    /**
     * Handles an SSLEngineResult.Status.BUFFER_OVERFLOW, which indicates that
     * We don't have enough space in mInAppBuffer for unwrapping.
     *
     * @return true if the contents of mInAppBuffer need to be drained to the
     * app receive callback.
     */
    private boolean handleUnwrapOverflow() {
        // See if the app buffer is completely empty, in which case
        // we need to resize it.
        if (mInAppBuffer.position() == 0) {
            if (mInAppBuffer.limit() == mInAppBuffer.capacity()) {
                // The buffer is empty.  The engine must need
                // a larger buffer.  Resize it.
                int appSize = mSSLEngine.getSession().getApplicationBufferSize();
                mInAppBuffer = allocate(appSize);
            } else {
                // The buffer is empty but shorter than capacity for whatever
                // reason.  This shouldn't happen, but clear it just in case
                // it does.
                mInAppBuffer.clear();
            }

            // Try unwrapping with the resized buffer.
            return false;
        }

        // Otherwise, mInAppBuffer contains unwrapped data that should be
        // drained.
        return true;
    }

    /**
     * Handles SSLEngineResult.Status.BUFFER_UNDERFLOW.
     *
     * mConn's buffer will be prepared for appending more data.  Be aware
     * that the buffer itself might change if the engine requests a different
     * capacity.
     */
    private void handleUnwrapUnderflow() {
        ByteBuffer buf = mConn.getInBuffer();
        buf.position(mInNetBufferStart);

        // Network buffer size requirements might have changed, so check.
        int netSize = mSSLEngine.getSession().getPacketBufferSize();
        if (netSize > buf.capacity()) {
            // Our network receive buffer is not large enough.  Resize it.
            ByteBuffer newInBuf = allocate(netSize);

            // Preserve the already received, but not unwrapped, packet data.
            // This is just the range from the buf's current position to
            // buf.limit().
            newInBuf.put(buf);
            // The new incoming net buffer is aligned at 0.
            mInNetBufferStart = 0;

            mConn.setInBufferInternal(newInBuf);

            // Now, the buffer is prepared for appending new data
            // from the network.
            return;
        }

        // Our buffer is correctly sized.  The SSL packet is fragmented
        // so that we only have a partial copy of it.  This can be due
        // to two reasons: 1) we haven't received enough data or
        // 2) our buffer is full and not fully utilizing its space.

        if (!buf.hasRemaining()) {
            // This is 2).  Buffer is full and not utilizing its space.
            buf.compact();
            mInNetBufferStart = 0;

            // A compacted buffer is already ready for appending.
            return;
        }

        // Otherwise, prepare the buffer for appending to existing data
        // according to the comments of handleNetRecv.
        buf.position(buf.limit());
        buf.limit(buf.capacity());

        return;
    }

    /**
     * Handles an SSLEngineResult.Status.BUFFER_OVERFLOW when wrapping, which
     * indicates that We don't have enough space in mConn's outgoing
     * buffer for wrapping.
     *
     * @return true if looping should stop because:
     * 1) data needs to be sent to the network or 2) the connection is
     * closed.
     */
    private boolean handleWrapOverflow() {
        ByteBuffer outBuf = mConn.getOutBuffer();

        // Check that the capacity was not changed.
        int netSize = mSSLEngine.getSession().getPacketBufferSize();
        if (outbuf.capacity() < netSize) {
            ByteBuffer newOutBuf = allocate(netSize);

            outBuf.flip();
            newOutBuf.put(outBuf);

            mConn.setOutBufferInternal(newOutBuf);

            // Now, retry with the larger buffer.
            return false;
        }

        // Otherwise, outBuf is simply full.  Stop so that we can send to the
        // network to drain it.
        return true;
    }

    /**
     * Handles receive completion callbacks while in the active state.
     *
     * Called after NonBlockingConnection's recv() method returns data.
     * It unwraps SSL/TLS data from the network connection.
     *
     * buf is not necessarily cleared at the start of each receive, since
     * it might contain a partial SSL/TLS packet.  To handle this, we
     * always use the "Append" (recvAppend/recvAppendPersistent) forms
     * of recv when receiving net data.
     *
     * Thus, it is necessary to manage the net buffer explicitly
     * after draining it.  To accomplish that:
     *
     * - At the start of this method
     *     + buf.position() will be 0.
     *     + mInNetBufferStart points to the actual start position of network
     *       data that needs to be unwrapped based on the last call to
     *       handleNetRecv.
     *     + buf.limit() points to the end of the data read from the
     *       NonBlockingConnection.
     *
     * - At the end of this method, we must ensure the following:
     *     + mInNetBufferStart points to the start of all network data that
     *       is yet to be unwrapped.
     *     + buf.position() is the end of all data that is not yet unwrapped.
     *       This way, new data will be appended.
     *     + buf.limit() is set to capacity.
     */
    private void onActiveNetRecv(AsyncConnection conn, ByteBuffer bufArg) {
        do {
            boolean done = stepActiveUnwrap();
            if (!done) {
                // Events are scheduled or the engine is closed.
                return;
            }
            // Else, we're now handshaking.
            configureHandshakeState();

            done = stepHandshake(mSSLEngine.getHandshakeStatus());
            if (!done) {
                // Step handshake has already scheduled the appropriate events.
                return;
            }
            // Else, we're now active.
            configureActiveState();
        } while (true);
    }

    /**
     * Handles send completion callbacks while in the active state.
     */
    private void onActiveNetSend(AsyncConnection conn) {
        // We've completely sent the out buffer, so it's safe to clear it for
        // further buffer writes.
        mConn.getOutBuffer().clear();

        if (mSendType == SendType.MULTIPLE) {
            onActiveNetSendMultiple();
            return;
        }

        // Otherwise, we have some sort of single buffer payload.

        // Notify the application if all of its data was sent.  This is not
        // always the case, since (potentially partial) data was wrapped.
        if (!mOutAppBuffer.hasRemaining()) {
            SendType newType = performAppSendCallback();
            mSendType = newType;
            switch (newType) {
                case NONE:
                    // No more sends are requested.
                    return;

                case SINGLE:
                case SINGLE_EXTERNAL:
                    // Handle below.
                    break;

                case MULTIPLE:
                    onActiveNetSendMultiple();
                    return;
            }
        }

        wrapActiveSingle();
    }

    private void onActiveNetSendMultiple(AsyncConnection conn) {
        // Notify the application if all of its data was sent.  This is not
        // always the case, since (potentially partial) data was wrapped.
        if (!mOutAppBuffersExternal.hasRemaining()) {
            SendType newType = performAppSendCallback();
            mSendType = newType;
            switch (newType) {
                case NONE:
                    // No more sends are requested.
                    return;

                case SINGLE:
                case SINGLE_EXTERNAL:
                    wrapActiveSingle();
                    return;

                case MULTIPLE:
                    // Handle below.
                    break;
            }
        }

        wrapActiveMultiple();
    }

    private void onHandshakeNetRecv(AsyncConnection conn, ByteBuffer bufArg) {
        boolean done = stepHandshake(mSSLEngine.getHandshakeStatus());
        if (!done) {
            // Events are already scheduled.
            return;
        }

        configureActiveState();
    }

    private void onHandshakeNetSend(AsyncConnection conn) {
        // We finished sending all net data.  Prepare the buffer for more
        // wrapping.
        ByteBuffer outBuf = mConn.getOutBuffer();
        outBuf.clear();

        boolean done = stepHandshake(mSSLEngine.getHandshakeStatus());
        if (!done) {
            // Events are already scheduled.
            return;
        }

        configureActiveState();
    }

    /**
     * This must only be called when a background task is complete.  It
     * is assumed that this runs in the main event thread context.
     *
     * It will reschedule any pending handlers according to the connection
     * state.
     */
    private void onTaskDone() {
        boolean done = stepHandshake(mSSLEngine.getHandshakeStatus());
        if (!done) {
            // Step handshake has already scheduled the appropriate events.
            return;
        }

        configureActiveState();
    }

    /**
     * Calls the application receive callback.  This respects any nested
     * receives.  It is meant to be called in a receive handler to avoid
     * recursion from nested receives.
     *
     * @param appBuf the application buffer to pass to the callback.  This
     * must already be positioned for reading.
     *
     * @return the new receive type issued by the callback or NONE if no nested
     * receive was encountered.
     */
    private RecvType performAppRecvCallback(ByteBuffer appBuf) {
        RecvType currType = mRecvType;
        if (currType == RecvType.NONE) {
            return false;
        }

        // Catch any nested receives.
        mRecvType = RecvType.NONE;
        mAppRecvCallback.onRecv(this, appBuf);
        RecvType newType = mRecvType;
        mRecvType = currType;

        // Prepare the app buffer for future writes.
        appBuf.clear();

        return newType;
    }

    /**
     * Calls the application send callback.  This respects any nested
     * sends.  It is meant to be called in a send handler to avoid
     * recursion during sends.
     *
     * @return the new send type issued by the callback or NONE if no nested
     * receive was encountered.
     */
    private SendType performAppSendCallback() {
        SendType currType = mSendType;
        if (currType == SendType.NONE) {
            return false;
        }

        // Catch any nested sends.
        mSendType = SendType.NONE;
        mAppSendCallback.onSend(this, appBuf);
        SendType newType = mSendType;
        mSendType = currType;

        return newType;
    }

    @Override
    public void recv(OnRecvCallback callback) throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        mRecvType = RecvType.SIMPLE;
        mAppRecvCallback = callback;

        // User methods can only be called while mState == ACTIVE, so we need
        // not worry about overriding a handshaking state.
        //
        // Be sure to use the append form of receive, since the in net buffer
        // might contain partially unwrapped data.
        mConn.recvAppend(mActiveNetRecvCallback);
    }

    @Override
    public void recvPersistent(OnRecvCallback callback) throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        mRecvType = RecvType.PERSISTENT;
        mAppRecvCallback = callback;

        // Be sure to use the append form of receive, since the in net buffer
        // might contain partially unwrapped data.
        mConn.recvAppendPersistent(mActiveNetRecvCallback);
    }

    private void restoreActiveRecv(RecvType recvType) {
        switch (recvType) {
            case NONE:
                return;

            case SIMPLE:
                mConn.recvAppend(mActiveNetRecvCallback);
                break

            case PERSISTENT:
                mConn.recvAppendPersistent(mActiveNetRecvCallback);
                break;
        }
    }

    /**
     * Restores the active send state given the sendType.
     */
    private void restoreActiveSend(SendType sendType) {
        if (sendType == SendType.NONE) {
            // See if we must flush the send buffer despite not having work.
            ByteBuffer outBuf = mConn.getOutBuffer();
            if (outBuf.position() != 0) {
                outBuf.flip();
                mConn.send(mNoneActiveNetSendCallback);
            }
            return;
        }

        // Otherwise, we have some work.
        performActiveWrap();
    }

    public void send(OnSendCallback callback) throws IllegalArgumentException {
        sendSingle(SendType.SINGLE, mOutAppBufferInternal, callback);
    }

    public void send(OnSendCallback callback, ByteBuffer buf) throws IllegalArgumentException {
        sendSingle(SendType.SINGLE, buf, callback);
    }

    public void send(OnSendCallback callback, ByteBuffer[] bufs, long bufsRemaining)
            throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        mSendType = MULTIPLE;
        mAppSendCallback = callback;
        mOutAppBuffersExternal = new ByteBufferArray(bufs);

        wrapActiveMultiple();
    }

    /**
     * It is assumed that this is only called in the ACTIVE state.  (App
     * callbacks are never triggered in the HANDSHAKE state.)
     *
     * @param type
     * @param buf the application buffer holding outgoing data.  It can be
     * null, which signals a send for handshaking purposes.
     * @param callback cannot be null.
     *
     * @throws IllegalArgumentException if callback is null
     */
    private void sendSingle(SendType type, ByteBuffer buf, OnSendCallback callback)
            throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        mSendType = type;
        mAppSendCallback = callback;
        mOutAppBuffer = buf;

        wrapActiveSingle();
    }

    @Override
    public AsyncConnection setOnCloseCallback(AsyncConnection.OnCloseCallback callback) {
        mAppCloseCallback = callback;
        return this;
    }

    @Override
    public AsyncConnection setOnErrorCallback(AsyncConnection.OnErrorCallback callback) {
        mAppErrorCallback = callback;
        return this;
    }

    /**
     * This is only valid before any send or receive.
     *
     * @param isClient true if the connection should act as an SSL client during
     * the handshaking process.
     */
    public void setClientMode(boolean isClient) {
        mSSLEngine.setUseClientMode(isClient);
    }

    /**
     * @param onTaskDoneCallback will be called after all engine tasks
     * are completed.
     *
     * @return true if any tasks were scheduled
     */
    private boolean scheduleHandshakeTasks(Runnable onTaskDoneCallback) {
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
            sSerialWorkerExecutor.execute(mTaskDoneCallback);
        }

        return hasTask;
    }

    /**
     * Performs as many active unwraps as possible.
     *
     * @return true if the active state is paused and handshaking is necessary.
     * false if still active, in which case some sort of event will be
     * scheduled.
     *
     * Engine closure always returns false, since the close callback will be
     * scheduled and triggered.
     */
    private boolean stepActiveUnwrap() {
        // We might still have data in the in-network buffer, so start
        // unwrapping from there, first.
        ByteBuffer buf = mConn.getInBuffer();
        buf.position(mInNetBufferStart);

        boolean done = false;
        do {
            SSLEngineResult result = mSSLEngine.unwrap(buf, mInAppBuffer);
            switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    done = stepActiveUnwrapOverflow();
                    break;

                case BUFFER_UNDERFLOW:
                    // We need more data.
                    handleUnwrapUnderflow();
                    // The net Buffer is now prepared for receiving more data.
                    // Note that the underlying mConn in buffer might change as
                    // a result.
                    mConn.recvAppend(mActiveNetRecvCallback);
                    return false;

                case CLOSED:
                    handleEngineClose();
                    return false;

                case OK:
                    // We've unwrapped a packet.  Update the position
                    mInNetBufferStart = buf.position();
                    break;
            }

            if (result.getHandshakeStatus() != NOT_HANDSHAKING) {
                return true;
            }
        } while (true);

        return false;
    }

    /**
     * @return true if unwrapping should stop.
     */
    private boolean stepActiveUnwrapOverflow() {
        boolean needsDrain = handleUnwrapOverflow();
        if (!needsDrain) {
            return false;
        }

        // Flush the app buffer to the callback.
        ByteBuffer appBuf = mInAppBuffer;
        appBuf.flip();

        RecvType newType = performAppRecvCallback(appBuf);
        switch (newType) {
            case NONE:
                // No more unwrapping should occur.
                mRecvType = newType;
                mConn.cancelRecv();
                return true;

            case SIMPLE:
                if (mRecvType != newType) {
                    mRecvType = newType;

                    // We need to change to a simple NonBlockingConnection
                    // receive, since it was persistent.
                    mConn.recvAppend(mActiveNetRecvCallback);
                }
                // Continue unwrapping.
                return false;

            case PERSISTENT:
                if (mRecvType != newType) {
                    mRecvType = newType;

                    mConn.recvAppendPersistent(mActiveNetRecvCallback);
                }
                // Continue unwrapping.
                return false;
        }
    }

    /**
     * @return true if active stepping is paused and handshaking is necessary.
     * false if still active; an event will be scheduled in this case.  Engine
     * close will return false; the close callback will be triggered.
     */
    private boolean stepActiveWrapSingle() {
        boolean done = false;
        do {
            // Always grab a fresh buffer, since handleStepActiveWrapResult
            // might change the underlying buffers.
            ByteBuffer outBuf = mConn.getOutBuffer();
            SSLEngine result = mSSLEngine.wrap(mOutAppBuffer, outBuf);

            done = handleStepActiveWrapResult(result);

            if (result.getHandshakeStatus() != NOT_HANDSHAKING) {
                // We're now handshaking.
                return true;
            }
        } while (!done);
    }

    /**
     * @return true if active stepping is paused and handshaking is necessary.
     * false if still active; an event will be scheduled in this case.  Engine
     * close will return false; the close callback will be triggered.
     */
    private boolean stepActiveWrapMultiple() {
        ByteBufferArray src = mOutAppBuffersExternal;

        boolean done = false;
        do {
            // Always grab a fresh buffer, since handleStepActiveWrapResult
            // might change the underlying buffers.
            ByteBuffer outBuf = mConn.getOutBuffer();
            SSLEngine result = mSSLEngine.wrap(src.getBuffers(), src.getNonEmptyOffset(),
                    src.length(), outBuf);
            src.update();

            done = handleStepActiveWrapResult(result);

            if (result.getHandshakeStatus() != NOT_HANDSHAKING) {
                // We're now handshaking.
                return true;
            }
        } while (!done);
    }

    /**
     * @return true if handshaking is finished.  False if handshaking is still
     * in progress.  In that case, events will be scheduled to allow for
     * progress.
     */
    private boolean stepHandshake(SSLEngineResult.HandshakeStatus initStatus) {
        SSLEngineResult.HandshakeStatus status = initStatus;

        boolean done = false;
        do {
            switch (status) {
                case NOT_HANDSHAKING:
                case FINISHED:
                    return true;

                case NEED_TASK:
                    if (!scheduleHandshakeTasks(mTasksDoneCallback)) {
                        // No tasks were scheduled.  This should never happen.
                        throw new RuntimeException("no tasks scheduled when expected");
                    }
                    return false;

                case NEED_WRAP:
                    done = stepHandshakeWrap();
                    if (!done) {
                        // Flush the out buffer if non-empty.
                        ByteBuffer outBuf = mConn.getOutBuffer();
                        if (outBuf.position() != 0) {
                            outBuf.flip();
                            mConn.send(mHandshakeNetSendCallback);
                            return false;
                        }
                    }
                    break;

                case NEED_UNWRAP:
                    done = stepHandshakeUnwrap();
                    break;
            }

            status = mSSLEngine.getHandshakeStatus();
        } while (!done);

        return false;
    }

    /**
     * Performs as many handshaking unwraps as possible.  An event will
     * be scheduled to receive any network data as necessary.
     *
     * It is assumed that mState == HANDSHAKE when this method is called.
     *
     * @return true if an event callback was scheduled and stepping should
     * cease.  True will also be returned on engine close.
     */
    private boolean stepHandshakeUnwrap() {
        // We might still have data in the in-network buffer, so start
        // unwrapping from there, first.
        ByteBuffer buf = mConn.getInBuffer();
        buf.position(mInNetBufferStart);

        do {
            SSLEngineResult result = mSSLEngine.unwrap(buf, mInAppBuffer);
            switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    // This should not happen, since we are in a
                    // handshaking phase.
                    throw new RuntimeException("buffer overflow while unwrapping");

                case BUFFER_UNDERFLOW:
                    // We need more data.
                    handleUnwrapUnderflow();
                    // The net Buffer is now prepared for receiving more data.
                    // Note that the underlying mConn buffer might change as
                    // a result; however, we're performing an immediate recv,
                    // so we need not worry about things.
                    mConn.recvAppend(mHandshakeNetRecvCallback);
                    return true;

                case CLOSED:
                    handleEngineClose();
                    return true;

                case OK:
                    // We've unwrapped a packet.  Update the position
                    mInNetBufferStart = buf.position();
                    break;
            }

            if (result.getHandshakeStatus() != NEED_UNWRAP) {
                return false;
            }
        } while (true);

        return false;
    }

    /**
     * Performs as many handshaking wraps as possible.  An event will always
     * be scheduled as a result.
     *
     * It is assumed that mState == HANDSHAKE.
     *
     * @return true if an event callback was scheduled and that handshaking
     * stepping should stop.  On close, the callback is typically executed
     * immediately.  false is returned to signal that another handshake
     * step should occur (e.g., to handle unwraps, etc.).
     */
    private void stepHandshakeWrap() {
        // The outBuf is only flushed on overflow or handshake completion.
        ByteBuffer outBuf = mConn.getOutBuffer();

        do {
            SSLEngine result = mSSLEngine.wrap(mOutAppBuffer, outBuf);
            switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    // This call can change the out buffer if the SSLEngine
                    // demands a change in buffer size.
                    boolean needsSend = handleWrapOverflow();

                    // Grab the fresh buffer, in case it changed.
                    outBuf = mConn.getOutBuffer();

                    if (needsSend) {
                        outBuf.flip();
                        mConn.send(mHandshakeNetSendCallback);
                        return true;
                    }

                    break;

                case BUFFER_UNDERFLOW:
                    throw new RuntimeException("unexpected underflow when handshaking");

                case CLOSED:
                    handleEngineClose();
                    return true;

                case OK:
                    break;
            }

            if (result.getHandshakeStatus() != NEED_WRAP) {
                return false;
            }
        } while (true);

        return false;
    }

    /**
     * Loop over the wrapping steps, starting with the active state.
     * The app send callback will never be called from within.  Instead,
     * it is assumed that the callback is triggered after any wrapped
     * data is flushed to the network and thus in onActiveNetSend().
     */
    private void wrapActiveSingle() {
        do {
            boolean done = stepActiveWrapSingle();
            if (!done) {
                // Events are scheduled or the engine is closed.
                return;
            }
            // Else, we're now handshaking.
            configureHandshakeState();

            done = stepHandshake(mSSLEngine.getHandshakeStatus());
            if (!done) {
                // Step handshake has already scheduled the appropriate events.
                return;
            }
            // Else, we're now active.
            configureActiveState();
        } while (true);
    }

    /**
     * The "Multiple" variant of wrapActiveSingle.  stepActiveWrapMultiple()
     * will be called to wrap buffer arrays provided by the app.
     */
    private void wrapActiveMultiple() {
        do {
            boolean done = stepActiveWrapMultiple();
            if (!done) {
                // Events are scheduled or the engine is closed.
                return;
            }
            // Else, we're now handshaking.
            configureHandshakeState();

            done = stepHandshake(mSSLEngine.getHandshakeStatus());
            if (!done) {
                // Step handshake has already scheduled the appropriate events.
                return;
            }
            // Else, we're now active.
            configureActiveState();
        } while (true);
    }
}
