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

class SSLNonBlockingConnection implements AsyncConnection {
    private enum RecvType {
        NONE,
        // Handshaking only receive.  This is not persistent.
        HANDSHAKE,
        SIMPLE,
        PERSISTENT
    }

    private enum SendType {
        NONE,
        // Handshaking only send.  This uses the NonBlockingConnection internal
        // buffer.
        HANDSHAKE,
        // App data using the internal buffer.
        SINGLE,
        // A send that fires the callback whenever data is sent.
        SINGLE_PARTIAL,
        MULTIPLE,
    }

    // Virtually all browsers support TLSv1.
    private static final String sSSLProtocol = "TLS";

    // This is used for executing SSLEngine blocking tasks in a dedicated
    // thread.  It is guaranteed to process tasks in serial order.
    private static Executor sSerialWorkerExecutor =
        Executors.newSingleThreadExecutor();

    private NonBlockingConnection mConn;

    private SSLContext mSSLContext;
    private SSLEngine mSSLEngine;

    // This is always true after closeImmediately() is called.
    private boolean mIsClosed;

    // True if direct ByteBuffers should be used.
    private boolean mIsDirect;

    // This is used for queueing up an action to the main event loop from
    // a worker thread.
    private SelectTaskQueue mSelectTaskQueue;

    // The type of receive requested by the application or NONE if no
    // receive is active.
    private boolean mRecvType;

    // The type of send requested by the application.  This is NONE if no
    // send is active.
    private SendType mSendType;

    // Start position for the incoming net buffer.  This allows for lazy
    // compaction and partial SSL/TLS packets.  The net buffer itself is
    // mConn's internal in buffer.
    private int mInNetBufferStart;

    // The internal in buffer that holds plaintext unwrapped from the SSLEngine.
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
    private ByteBuffer[] mOutAppBuffersExternal;

    private AsyncConnection.OnCloseCallback mAppCloseCallback;

    private AsyncConnection.OnErrorCallback mAppErrorCallback;

    // NOTE: this may be null, in case a handshaking unwrap is scheduled.
    private AsyncConnection.OnRecvCallback mAppRecvCallback;

    // NOTE: this may be null, in case a handshaking wrap is scheduled.
    private AsyncConnection.OnSendCallback mAppSendCallback;

    // This is signaled on abrupt connection close by NonBlockingConnection.
    private AsyncConnection.OnCloseCallback mNetCloseCallback =
        new AsyncConnection.OnCloseCallback() {
            @Override
            public void onClose(AsyncConnection conn) {
                handleNetClose(conn);
            }
        };

    // This is signaled by an error in NonBlockingConnection.
    private AsyncConnection.OnErrorCallback mNetErrorCallback =
        new AsyncConnection.OnErrorCallback() {
            @Override
            public void onError(AsyncConnection conn, String reason) {
                handleNetError(conn, reason);
            }
        };

    // NonBlockingConnection's receive handler.
    private AsyncConnection.OnRecvCallback mNetRecvCallback =
        new AsyncConnection.OnRecvCallback() {
            @Override
            public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                handleNetRecv(conn, buf);
            }
        };

    // NonBlockingConnection's send handler.
    private AsyncConnection.OnSendCallback mNetSendCallback =
        new AsyncConnection.OnRecvCallback() {
            @Override
            public void onSend(AsyncConnection conn) {
                handleNetSend(conn);
            }
        };

    // Used for scheduling unwrapHandshake.
    private Runnable mUnwrapHandshakeTask = new Runnable() {
        @Override
        public void run() {
            unwrapHandshake();
        }
    }

    // Used for scheduling wrapHandshake.
    private Runnable mWrapHandshakeTask = new Runnable() {
        @Override
        public void run() {
            wrapHandshake();
        }
    }

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
        // We'll assign the internal in and out buffers using sizes from the
        // SSLSession.
        mConn = new NonBlockingConnection(selector, chan, null, null);
        mConn.setOnCloseCallback(mNetCloseCallback);
        mConn.setOnErrorCallback(mNetErrorCallback);

        mSSLContext = SSLContext.getInstance(sSSLProtocol);
        mSSLEngine = mSSLContext.createSSLEngine();

        // NOTE: This must be assigned before calling allocate().
        mIsDirect = isDirect;

        mSelectTaskQueue = taskQueue;

        mRecvType = RecvType.NONE;
        mSendType = SendType.NONE;

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
     * suspend all events.  onReadyTask will be called in the event loop
     * when ready.
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
     * Continues wrapping and sending until all application data in
     * mOutAppBuffer has been sent.
     */
    private void handleNetSend(AsyncConnection conn) {
        if (mSendType == SendType.MULTIPLE) {
            handleNetSendMultiple(conn);
            return;
        }

        // handleNetSend() occurs whenever an outgoing network buffer is
        // flushed.  The network buffer might contain handshake data or
        // wrapped outgoing app data.  In the latter case, we must issue
        // the send callback if all app data is sent to indicate send
        // completion.
        if (mAppSendCallback != null && !mOutAppBuffer.hasRemaining()) {
            // See if the callback performs any nested sends.
            SendType currSendType = mSendType;

            mAppSendCallback.onSend(this);

            SendType newSendType = mSendType;

            if (newSendType == SendType.MULTIPLE) {
                wrapMultiple(conn);
                return;
            }

            if (newSendType == SendType.NONE) {
                // No callback occurred.  See if we need to attempt
                // wrapping.
                if (mSSLEngine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    // We can stop right here.
                    return;
                }
            }
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
        mOutAppBuffersExternal = bufs;

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
     * Handles an SSLEngineResult.Status.BUFFER_OVERFLOW, which indicates that
     * We don't have enough space in mInAppBuffer for unwrapping.
     *
     * @param unwrapCount the number of successful unwraps since the last
     * time mAppRecvCallback was called.
     *
     * @return the new unwrapCount, since the last time mAppRecvCallback
     * was called.  This is negative if looping should stop because: 1) more
     * data is needed from the network or 2) the connection is closed.  In that
     * case, mInAppBuffer will be drained.
     */
    private int unwrapBufferOverflow(ByteBuffer buf, int unwrapCount) {
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
            return unwrapCount;
        }

        // Otherwise, mInAppBuffer contains unwrapped data.  Try
        // to drain it before attempting to unwrap again.
        return unwrapFlushInAppBuffer(unwrapCount);
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
     * TODO: this needs to handle continuations when the outbuffer gets full.
     *
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

        ByteBuffer[] srcs = mOutAppBuffersExternal;
        ByteBufferArray srcArray = new ByteBufferArray(srcs);

        boolean done = false;
        do {
            SSLEngineResult result = mSSLEngine.wrap(srcs,
                    srcArray.getNonEmptyOffset(), srcs.length, outBuf);
            switch (result.getStatus()) {
                case SSLEngineResult.Status.BUFFER_OVERFLOW:
                    done = wrapOverflow(outBuf, wrapCount);
                    break;

                case SSLEngineResult.Status.BUFFER_UNDERFLOW:
                    // We're done sending.
                    cancelSend();
                    return;

                case SSLEngineResult.Status.CLOSED:
                    // Bubble up the close.
                    if (mAppOnCloseCallback != null) {
                        mAppOnCloseCallback.onClose(this);
                    }

                    closeImmediately();

                    return;

                case SSLEngineResult.Status.OK:
                    // TODO: check handshaking.
                    // you can still flush the partial out buffer.
                    if (!checkHandshake(result.getHandshakeStatus(), onReadyTask)) {
                        // Handshaking wants to pause the send.
                        return false;
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

    /**
     * Handles an SSLEngineResult.Status.BUFFER_OVERFLOW when wrapping, which
     * indicates that We don't have enough space in buf for wrapping.
     *
     * @param outBuf the outgoing network buffer.
     *
     * @return true if looping should stop because:
     * 1) data needs to be sent to the network or 2) the connection is
     * closed.
     */
    private boolean wrapOverflow(ByteBuffer outBuf) {
        // Check that the capacity was not changed.
        int netSize = mSSLEngine.getSession().getPacketBufferSize();
        if (outbuf.capacity() < netSize) {
            outBuf = allocate(netSize);
            mConn.setOutBufferInternal(outBuf);

            // Now, retry with the larger buffer.
            return false;
        }

        // Otherwise, stop so that we can send to the network to drain
        // outBuf.
        return true;
    }

    private void wrapSingle() {
        // We need to wrap as much of the remainder as possible and
        // schedule a new send.  Because the SSLEngine can generate handshaking
        // data, we must always attempt a wrap, even if we've flushed all
        // data generated by the application in mOutAppBuffer.
        ByteBuffer outBuf = mConn.getOutBuffer();
        outBuf.clear();

        boolean done = false;
        do {
            SSLEngine result = mSSLEngine.wrap(mOutAppBuffer, outBuf);
            switch (result.getStatus()) {
                case SSLEngineResult.Status.BUFFER_OVERFLOW:
                    done = wrapOverflow(outBuf);
                    break;

                case SSLEngineResult.Status.BUFFER_UNDERFLOW:
                    // This can happen if the SSL is not handshaking and
                    // no more input app data exists.  We're done sending
                    // in this case.  Thus, stop immediately, without
                    // requeuing a send.
                    cancelSend();
                    return;

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
                    break;
            }
        } while (!done);

        // Queue the outBuf for sending.
        outBuf.flip();
        mConn.send(mNetSendCallback);
    }
}
