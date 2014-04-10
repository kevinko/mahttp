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
 */

// TODO: fix send callbacks (esp. in handshake wraps), like with onActiveNetRecv.
// it might not be necessary to have the send checks, if you handle send states
// directly in the send() method.  however, we use it in handlers so that we can
// switch between the various send/receive types and handle things immediately.
// TODO: fix sending.  you are not handling handshakes and continuations.
class SSLNonBlockingConnection implements AsyncConnection {
    private enum State {
        // Actively sending data.
        ACTIVE,
        // closeImmediately has been reached.  Once closed, a connection
        // remains closed.
        CLOSED,
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
     * @param status
     * @param onreadyTask this will be scheduled for running in the event
     * thread after any queued tasks are completed.  It should be used for
     * initiating handshake wrap or unwrap events, depending on the context.
     * For optimal performance, this must not block.
     *
     * @return false if handshaking is awaiting completion of a task (i.e.,
     * true if event handling can proceed).  In this case, the caller should
     * suspend all events.  onReadyTask will be called in the event loop's
     * thread context when ready.
     */
    private boolean checkHandshake(SSLEngineResult.HandshakeStatus status, Runnable onReadyTask) {
        switch (status) {
            case NEED_TASK:
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
                    sSerialWorkerExecutor.execute(onReadyTask);
                }

                return false;

            case NEED_UNWRAP:
                unwrapHandshake();
                return true;

            case NEED_WRAP:
                wrapHandshake();
                return true;

            case FINISHED:
            case NOT_HANDSHAKING:
                return true;
        }
    }

    /**
     * This performs a graceful closure.  Final close-related handshaking will
     * need to proceed via wrap/unwrap SSLEngine calls.
     */
    @Override
    public void close() throws IOException {
        mSSLEngine.closeInbound();
        mSSLEngine.closeOutbound();

        wrapHandshake();
    }

    /**
     * Closes the connection immediately without attempting to handshake the
     * closure.  This should normally be called when the engine enters
     * the CLOSED state or if the NonBlockingConnection is closed.
     *
     * mIsClosed will be set as a result.
     */
    private void closeImmediately() throws IOException {
        if (mIsClosed) {
            return;
        }

        mIsClosed = true;

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
        // TODO
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
    private void handleNetRecv(AsyncConnection conn, ByteBuffer buf) {
        // It is assumed that the app callback always fully drains its
        // buffer argument.
        mInAppBuffer.clear();

        if (!unwrap(mUnwrapHandshakeTask)) {
            // handleNetRecv() is possibly called as part of a persistent
            // receive.  Stop receiving until the handshake task is signaled.
            mConn.cancelRecv();
            return;
        }

        // Stop non-persistent receives.  mRecvType always holds the current
        // receive type, which will allow any receives scheduled within
        // a callback to persist.
        if (mRecvType == RecvType.NONE) {
            mConn.cancelRecv();
        }
    }

    /**
     * Handles send completion of outgoing application data by calling the
     * application's send callback.
     *
     * This takes care to handle any sends that may be nested in the callback.
     *
     * @param conn the AsyncConnection passed to handleNetSend.
     * @param appSendCallback must not be null.
     */
    private void handleNetSendDone(AsyncConnection conn,
            AsyncConnection.OnSendCallback appSendCallback) {
        // We're done sending everything requested by the app.  Signal
        // the callback.

        // See if the callback performs any nested sends.
        SendType currSendType = mSendType;

        appSendCallback.onSend(this);

        SendType newSendType = mSendType;

        if (newSendType == SendType.MULTIPLE) {
            wrapMultiple();
            return;
        }

        if (newSendType == SendType.NONE) {
            // No callback occurred.  See if we need to attempt wrapping.
            // TODO
            if (mSSLEngine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                // We can stop right here.
                cancelSend();
                return;
            }

            wrapHandshake();
            return;
        }

        // Otherwise, we have some sort of single send.
        wrapSingle();
        return;
    }

    /**
     * Continues wrapping and sending until all application data in
     * mOutAppBuffer has been sent.
     */
    private void handleNetSend(AsyncConnection conn) {
        // We're done sending everything in the outgoing network buffer.
        mConn.getOutBuffer().clear();

        if (mSendType == SendType.MULTIPLE) {
            handleNetSendMultiple(conn);
            return;
        }
        // TODO: do you need to check for any pending tasks?

        // handleNetSend() occurs whenever an outgoing network buffer is
        // flushed.  The network buffer might contain handshake data or
        // wrapped outgoing app data.  In the latter case, we must issue
        // the send callback if all app data is sent to indicate send
        // completion.
        if (mAppSendCallback != null && !mOutAppBuffer.hasRemaining()) {
            handleNetSendDone(conn, mAppSendCallback);
            return;
        }

        wrapSingle();
    }

    private void handleNetSendMultiple(AsyncConnection conn) {
        if (mAppSendCallback != null && srcArray.remaining() == 0) {
            SendType currSendType = mSendType;

            mAppSendCallback.onSend(this);

            SendType newSendType = mSendType;

            if (newSendType != SendType.MULTIPLE && newSendType != SendType.NONE) {
                wrapSingle();
                return;
            }
            // Else, we have no send or a multiple send.

            if (newSendType == SendType.NONE) {
                // We don't have any further application sends.  See if
                // the engine still needs to unwrap.
                if (mSSLEngine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    // We can stop right here.
                    return;
                }
            }
            return;
        }

        wrapMultiple();
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

        onActiveNetSendSingle();
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
                    onActiveNetSendSingle();
                    return;

                case MULTIPLE:
                    // Handle below.
                    break;
            }
        }

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

    private void onActiveNetSendSingle() {
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

        mInNetBufferStart = 0;

        // Maintain INVARIANT.
        mCallbackHasRecv = true;

        mIsRecvPersistent = false;
        mAppRecvCallback = callback;

        mConn.recv(mNetRecvCallback);
    }

    @Override
    public void recvPersistent(OnRecvCallback callback) throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        mInNetBufferStart = 0;

        // Maintain INVARIANT.
        mCallbackHasRecv = true;

        mIsRecvPersistent = true;
        mAppRecvCallback = callback;

        mConn.recvPersistent(mNetRecvCallback);
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
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        sendSingle(SendType.SINGLE, mOutAppBufferInternal, callback);
    }

    public void send(OnSendCallback callback, ByteBuffer buf) throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        sendSingle(SendType.SINGLE, buf, callback);
    }

    public void send(OnSendCallback callback, ByteBuffer[] bufs, long bufsRemaining) throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        mSendType = MULTIPLE;
        mAppSendCallback = callback;
        mOutAppBuffersExternal = new ByteBufferArray(bufs);

        wrapMultiple();
    }

    /**
     * @param type
     * @param buf the application buffer holding outgoing data.  It can be
     * null, which signals a send for handshaking purposes.
     * @param callback can be null, which will signal a send specifically for
     * SSLEngine handshaking purposes.
     */
    private void sendSingle(SendType type, ByteBuffer buf, OnSendCallback callback) {
        mSendType = type;
        mAppSendCallback = callback;
        mOutAppBuffer = buf;

        wrapSingle();
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
     * Unwraps the contents of mConn's in buffer from the starting position
     * mInNetBufferStart.
     *
     * mInNetBufferStart will be updated as unwrapping proceeds.
     *
     * @param onReadyTask will be called in the event loop when SSL handshaking
     * is ready to proceed.
     *
     * @return false if event handling should be paused because of SSL
     * handshaking.
     */
    private boolean unwrap(Runnable onReadyTask) {
        ByteBuffer buf = mConn.getInBuffer();
        buf.position(mInNetBufferStart);

        // We continually build up mInAppBuffer by unwrapping until we get
        // CLOSE/UNDERFLOW/OVERFLOW.  Then, issue the callback to maximize the
        // amount of data passed to the callback.

        // Tracks the number of successfully completed (OK) unwraps.  Set
        // to < 0 to stop unwrapping.
        //
        // Since the app buffer is of some integer size, this counter will never
        // hit any integer limits.
        int unwrapCount = 0;
        do {
            // This unwraps a single SSL/TLS packet.
            SSLEngineResult result = mSSLEngine.unwrap(buf, mInAppBuffer);
            switch (result.getStatus()) {
                case SSLEngineResult.Status.BUFFER_OVERFLOW:
                    unwrapCount = unwrapBufferOverflow(buf, unwrapCount);
                    break;

                case SSLEngineResult.Status.BUFFER_UNDERFLOW:
                    unwrapCount = unwrapBufferUnderflow(buf, unwrapCount);
                    break;

                case SSLEngineResult.Status.CLOSED:
                    unwrapCount = unwrapClosed(buf, unwrapCount);
                    break;

                case SSLEngineResult.Status.OK:
                    // Continue unwrapping, since we want to minimize
                    // the number of callbacks to the application
                    // by maximizing buffer usage.
                    unwrapCount++;

                    // The engine has updated buf's pointers, since we've read
                    // a packet, so track the changes.
                    mInNetBufferStart = buf.position();

                    if (!checkHandshake(result.getHandshakeStatus(), onReadyTask)) {
                        // Processing cannot proceed because of handshaking.
                        return false;
                    }
                    break;
            }
        } while (unwrapCount >= 0);

        return true;
    }

    /**
     * Flushes mInAppBuffer if unwrapCount is positive by calling
     * mAppRecvCallback.  Any receive methods called by the callback will
     * affect mRecvType.
     *
     * mInAppBuffer will be cleared (i.e., prepared for another unwrap) if 0
     * is returned.
     *
     * @return the new unwrapCount (0) or a negative value if unwrapping
     * should stop because of connection close or a non-persistent receive.
     */
    private int unwrapFlushInAppBuffer(int unwrapCount) {
        if (unwrapCount <= 0) {
            return unwrapCount;
        }

        // Otherwise, mInAppBuffer contains unwrapped data.  Try
        // to drain it before attempting to unwrap again.

        // Always position the buffer at the start of the data
        // for the callback.
        mInAppBuffer.flip();

        // Save mRecvType, since the callback may change it indirectly.
        RecvType currRecvType = mRecvType;

        // Detect any nested calls to a recv() method.
        mRecvType = RecvType.NONE;

        // This possibly updates mRecvType if a recv() method is called.
        mAppRecvCallback.onRecv(this, mInAppBuffer);
        // NOTE: the callback might close the connection.  However, close()
        // is graceful and involves further handshaking, so we need not worry
        // about stopping immediately here.

        // mRecvType might be updated by recv().
        RecvType newRecvType = mRecvType;
        mRecvType = currRecvType;

        if (currRecvType == RecvType.PERSISTENT) {
            // Let any nested recv() call take priority.
            if (newRecvType != RecvType.NONE) {
                mRecvType = newRecvType;
            }
            // Otherwise, continue unwrapping, since the receive is persistent.
        } else {
            // See if a recv() call occurred within the callback, which would
            // require that we continue unwrapping for another interval.

            if (newRecvType == RecvType.NONE) {
                // We've finished one round of receives and nothing further
                // is requested by the application.
                return -1;
            }

            // Otherwise, some sort of receive was requested by the callback.
            mRecvType = newRecvType;
        }

        // Prepare for another unwrap attempt.
        mInAppBuffer.clear();

        // We've flushed the app buffer via the callback.  Thus, reset the
        // unwrap count.
        return 0;
    }

    /**
     * Handles an SSLEngineResult.Status.BUFFER_UNDERFLOW.  Here, we lack
     * sufficient data in the network in buffer to decode an SSL/TSL packet.
     *
     * @param unwrapCount the number of successful unwraps since the last
     * time mAppRecvCallback was called.
     *
     * @return the new unwrapCount, since the last time mAppRecvCallback
     * was called.  This is always negative to indicate that looping should
     * stop because: 1) more data is needed from the network or 2) the
     * connection is closed.  mInAppBuffer will be drained.
     */
    private int unwrapBufferUnderflow(ByteBuffer buf, int unwrapCount) {
        unwrapFlushInAppBuffer(unwrapCount);
        if (mIsClosed) {
            // We can terminate early, since we'll no longer be using the
            // engine now that the connection is done.
            return -1;
        }

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

            // Now, the buffer is prepared according to the description of
            // handleNetRecv.  Continue appending net data into the buffer.
            return -1;
        }

        // Our buffer is correctly sized.  The SSL packet is fragmented
        // so that we only have a partial copy of it.  This can be due
        // to two reasons: 1) we haven't received enough data or
        // 2) our buffer is full and not fully utilizing its space.

        if (!buf.hasRemaining()) {
            // This is 2).  Buffer is full and not utilizing its space.
            buf.compact();
            mInNetBufferStart = 0;

            // A compacted buffer is properly prepared according to the
            // description of handleNetRecv.
            return -1;
        }

        // Otherwise, prepare the buffer for appending to existing data
        // according to the comments of handleNetRecv.
        buf.position(buf.limit());
        buf.limit(buf.capacity());

        return -1;
    }

    /**
     * Handles an SSLEngineResult.Status.CLOSED.
     *
     * @param unwrapCount the number of successful unwraps since the last
     * time mAppRecvCallback was called.
     *
     * @return the new unwrapCount, since the last time mAppRecvCallback
     * was called.  This is always negative to indicate that looping should
     * stop because the connection is closed.  mInAppBuffer will be drained.
     */
    private int unwrapClosed(ByteBuffer buf, int unwrapCount) {
        // Flush any data, first.  Ignore any receives that might occur
        // within the callback, since we're shutting down.
        unwrapFlushInAppBuffer(unwrapCount);

        // Bubble up the close.
        if (mAppOnCloseCallback != null) {
            mAppOnCloseCallback.onClose(this);
        }

        // Connection is closed.
        return -1;
    }

    /**
     * Attempts to unwrap handshaking data.  This does nothing if a receive
     * is already scheduled, since the receive will implicitly handle
     * handshaking.
     */
    private void unwrapHandshake() {
        if (mRecvType != RecvType.NONE) {
            // A receive is in progress, which will take care of handshaking.
            return;
        }

        mInAppBuffer.clear();
        mAppRecvCallback = null;
        mRecvType = RecvType.HANDSHAKE;

        if (unwrap(mWrapHandshakeTask)) {
            // Handshaking isn't paused, so it's safe to reconfigure the
            // persistent receive for future unwrapping.
            mConn.recvPersistent(mNetRecvCallback);
        }
    }

    /**
     * Attempts to wrap handshaking data from the SSLEngine, and send it to the
     * network.
     *
     * This does nothing if a send is already in progress, because the send
     * will automatically wrap handshaking information.
     */
    private void wrapHandshake() {
        if (mSendType != SendType.NONE) {
            // A send is already in progress.  Do nothing, since the send
            // will take care of handshaking.
            return;
        }

        mSendType = SendType.HANDSHAKE;
        mAppSendCallback = null;
        // Prepare empty outgoing application data to signal that we just want
        // to attempt handshaking.
        mOutAppBuffer.clear();

        wrapSingle();
    }

    /**
     * @param onReadyTask will be called in the event loop when SSL handshaking
     * is ready to proceed.
     *
     * @return false if event handling should be paused because of SSL
     * handshaking.
     */
    private void wrapMultiple(Runnable onReadyTask) {
        // Prepare the outgoing network buffer.
        ByteBuffer outBuf = mConn.getOutBuffer();
        outBuf.clear();

        ByteBufferArray src = mOutAppBuffersExternal;

        boolean done = false;
        do {
            ByteBuffer[] srcBufs = src.getBuffers();
            SSLEngineResult result = mSSLEngine.wrap(srcBufs,
                    src.getNonEmptyOffset(), srcBufs.length, outBuf);
            switch (result.getStatus()) {
                case SSLEngineResult.Status.BUFFER_OVERFLOW:
                    done = wrapOverflow(outBuf, wrapCount);
                    break;

                case SSLEngineResult.Status.BUFFER_UNDERFLOW:
                    // We're done wrapping.  Send everything to the network.
                    done = true;
                    break;

                case SSLEngineResult.Status.CLOSED:
                    // Bubble up the close.
                    if (mAppOnCloseCallback != null) {
                        mAppOnCloseCallback.onClose(this);
                    }

                    closeImmediately();

                    return;

                case SSLEngineResult.Status.OK:
                    if (!checkHandshake(result.getHandshakeStatus(), onReadyTask)) {
                        // Handshaking wants to pause the send.  Flush what
                        // we have to the network.
                        // TODO: this isn't quite right, since you don't want
                        // to trigger the done callback in handleNetSend.
                        // Check... probably need to assign a particular send
                        // state that will wait for handshake completion
                        // while preserving the callback.
                        done = true;

                        // TODO: you need to perform a continuation since
                        // callback needs to be preserved, etc. wrapHandshake
                    }
                    break;
            }

            srcArray.update();
        } while (!done);

        // Flush the out buffer to the network.
        outBuf.flip();
        mConn.send(mNetSendCallback);

        return true;
    }

    private void wrapSingle() {
        // TODO: check handshake?
        if (!checkHandshake(mSSLEngine.getHandshakeStatus(), mWrapSingle)) {
            return;
        }

        // We need to wrap as much of the remainder as possible and
        // schedule a new send.  Because the SSLEngine can generate handshaking
        // data, we must always attempt a wrap, even if we've flushed all
        // data generated by the application in mOutAppBuffer.

        boolean done = false;
        do {
            ByteBuffer outBuf = mConn.getOutBuffer();
            SSLEngine result = mSSLEngine.wrap(mOutAppBuffer, outBuf);
            switch (result.getStatus()) {
                case SSLEngineResult.Status.BUFFER_OVERFLOW:
                    done = wrapOverflow();
                    break;

                case SSLEngineResult.Status.BUFFER_UNDERFLOW:
                    // This can happen if the SSL is not handshaking and
                    // no more app data exists in mOutAppBuffer.  We're done
                    // wrapping data in this case.  We'll break out of the
                    // loop to try and send any wrapped data.
                    done = true;
                    break;

                case SSLEngineResult.Status.CLOSED:
                    // Bubble up the close.
                    if (mAppOnCloseCallback != null) {
                        mAppOnCloseCallback.onClose(this);
                    }

                    // The engine has signaled completion, so there is nothing
                    // left to do.
                    closeImmediately();

                    return;

                case SSLEngineResult.Status.OK:
                    if (!checkHandshake(result.getHandshakeStatus(), mWrapSingle)) {
                        done = true;
                    }
                    // TODO
                    // You need to flush network data and yet still wait for the task to complete.
                    Runnable onReadyTask = mWrapHandshake;
                    if (outBuf.position() != 0) {
                        // We have data to flush.
                        onReadyTask = 
                        if (!checkHandshake(result.getHandshakeStatus())) {
                        }
                    } else {
                    }
                    if (!checkHandshake(result.getHandshakeStatus())) {
                        // We need to wait for task completion.
                        //
                        // Flush any pending data to the network in the
                        // meanwhile.
                    }

                    // Else, continue wrapping.
                    break;
            }
        } while (!done);

        // Queue the outBuf for sending.
        outBuf.flip();
        mConn.send(mNetSendCallback);
    }
}
