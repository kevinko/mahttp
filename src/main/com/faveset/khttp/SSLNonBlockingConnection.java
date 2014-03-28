// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;;
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
    private enum SendType {
        INTERNAL,
        INTERNAL_PARTIAL,
        EXTERNAL_SINGLE,
        EXTERNAL_MULTIPLE
    }

    // Virtually all browsers support TLSv1.
    private static final String sSSLProtocol = "TLS";

    private NonBlockingConnection mConn;

    // This is always true after close() is called.
    private boolean mIsClosed;

    // True if direct ByteBuffers should be used.
    private boolean mIsDirect;

    // INVARIANT: This is always cleared before a callback.  recv() methods will
    // set this to true so that they can be detected from within a callback.
    private boolean mCallbackHasRecv;

    // True if a persistent receive is desired.
    private boolean mIsRecvPersistent;

    private SSLContext mSSLContext;
    private SSLEngine mSSLEngine;

    // Start position for the incoming net buffer.  This allows for lazy
    // compaction and partial SSL/TLS packets.
    private int mInNetBufferStart;

    // The internal in buffer that holds plaintext unwrapped from the SSLEngine.
    // Unlike with NonBlockingConnection, we do not allow an app-configurable
    // in buffer.  This is necessary, because the SSLEngine dictates the
    // minimum app buffer size for unwrapping.
    private ByteBuffer mInAppBuffer;

    // The type of send requested by the application.
    private SendType mSendType;

    // Points to the active out buffer when mSendType is some sort of SINGLE
    // buffer.
    //
    // INVARIANT: non-null when a SINGLE send() is scheduled.
    private ByteBuffer mOutAppBuffer;

    // The internal out buffer that applications can use.  This will hold data
    // from the user that is directed to the network.
    private ByteBuffer mOutAppBufferInternal;

    private ByteBuffer[] mExternalOutAppBuffers;

    // TODO
    private AsyncConnection.OnCloseCallback mAppCloseCallback;

    // TODO
    private AsyncConnection.OnErrorCallback mAppErrorCallback;

    // INVARIANT: This is always non-NULL when a recv() is scheduled.
    private AsyncConnection.OnRecvCallback mAppRecvCallback;

    private AsyncConnection.OnSendCallback mAppSendCallback;

    // This signals an abrupt connection close.
    private AsyncConnection.OnCloseCallback mNetCloseCallback =
        new AsyncConnection.OnCloseCallback() {
            @Override
            public void onClose(AsyncConnection conn) {
                handleNetClose(conn);
            }
        };

    private AsyncConnection.OnErrorCallback mNetErrorCallback =
        new AsyncConnection.OnErrorCallback() {
            @Override
            public void onError(AsyncConnection conn, String reason) {
                handleNetError(conn, reason);
            }
        };

    private AsyncConnection.OnRecvCallback mNetRecvCallback =
        new AsyncConnection.OnRecvCallback() {
            @Override
            public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                handleNetRecv(conn, buf);
            }
        };

    private AsyncConnection.OnSendCallback mNetSendCallback =
        new AsyncConnection.OnRecvCallback() {
            @Override
            public void onSend(AsyncConnection conn) {
                handleNetSend(conn);
            }
        };

    /**
     * @param selector
     * @param chan
     * @param isDirect true if direct ByteBuffers should be allocated
     * @throws IOException
     * @throws NoSuchAlgorithmException if SSL could not be configured
     * due to lack of security algorithm support on the platform.
     */
    public SSLNonBlockingConnection(Selector selector, SocketChannel chan,
            boolean isDirect) throws IOException, NoSuchAlgorithmException {
        // We'll assign the internal in and out buffers using sizes from the
        // SSLSession.
        mConn = new NonBlockingConnection(selector, chan, null, null);

        mIsDirect = isDirect;

        mSSLContext = SSLContext.getInstance(sSSLProtocol);
        mSSLEngine = mSSLContext.createSSLEngine();

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

        mSendType = SendType.SINGLE;
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
    public void OnRecvCallback cancelRecv() {
        mConn.cancelRecv();

        OnRecvCallback result = mAppRecvCallback;
        mAppRecvCallback = null;

        return result;
    }

    @Override
    public void close() throws IOException {
        if (mIsClosed) {
            return;
        }

        mIsClosed = true;

        // Clear possible external references.
        mOutAppBuffer = null;

        // TODO: you should call wrap to flush handshaking data.  (maybe
        // attempt to send over the nonblockingconnection and then cleanup
        // with a dedicated callback.)
        mSSLEngine.closeInbound();
        mSSLEngine.closeOutbound();

        mConn.close();
    }

    /**
     * Flushes mInAppBuffer if unwrapCount is positive by calling
     * mAppRecvCallback.  This respects the mCallbackHasRecv INVARIANT.
     *
     * mInAppBuffer will be cleared (i.e., prepared for another unwrap) if 0
     * is returned.
     *
     * @return the new unwrapCount (0) or a negative value if unwrapping
     * should stop because of connection close or a non-persistent receive.
     */
    private int flushInAppBuffer(int unwrapCount) {
        if (unwrapCount <= 0) {
            return unwrapCount;
        }

        // Otherwise, mInAppBuffer contains unwrapped data.  Try
        // to drain it before attempting to unwrap again.
        unwrapCount = 0;

        // Always position the buffer at the start of the data
        // for the callback.
        mInAppBuffer.flip();

        // Per the INVARIANT, the callback is always non-NULL
        // when a recv() is scheduled.
        mCallbackHasRecv = false;

        mAppRecvCallback.onRecv(this, mInAppBuffer);
        // NOTE: the callback might close the connection.

        if (mIsClosed) {
            // We're done.
            return -1;
        }

        if (!mIsRecvPersistent && !mCallbackHasRecv) {
            // Non-persistent receives must stop after the callback if a
            // receive wasn't called again within the callback.
            return -1;
        }

        // Else, prepare for another unwrap attempt.
        mInAppBuffer.clear();

        return unwrapCount;
    }

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

        // Rewind to the last known start of the buf.
        buf.position(mInNetBufferStart);

        // We continually build up mInAppBuffer by unwrapping until we get
        // CLOSE/UNDERFLOW/OVERFLOW.  Then, issue the callback to maximize the
        // amount of data passed to the callback.
        //
        // NOTE: buf (mInNetBuffer) might contain a partial SSL/TLS packet
        // at this point.

        // Tracks the number of successfully completed (OK) unwraps.  Set
        // to < 0 to stop unwrapping.
        //
        // Since the app buffer is of some integer size, this will never
        // hit any integer limits.
        int unwrapCount = 0;
        do {
            // This unwraps a single SSL/TLS packet.
            SSLEngineResult result = mSSLEngine.unwrap(buf, mInAppBuffer);
            switch (result.getStatus()) {
                case SSLEngineResult.Status.BUFFER_OVERFLOW:
                    unwrapCount = unwrapSSLBufferOverflow(buf, unwrapCount);
                    break;

                case SSLEngineResult.Status.BUFFER_UNDERFLOW:
                    unwrapCount = unwrapSSLBufferUnderflow(buf, unwrapCount);
                    break;

                case SSLEngineResult.Status.CLOSED:
                    unwrapCount = unwrapSSLClosed(buf, unwrapCount);
                    break;

                case SSLEngineResult.Status.OK:
                    // Continue unwrapping, since we want to minimize
                    // the number of callbacks to the application
                    // by maximizing buffer usage.
                    unwrapCount++;

                    // The engine has updated buf's pointers, since we've read
                    // a packet, so track the changes.
                    mInNetBufferStart = buf.position();
                    break;
            }
        } while (unwrapCount >= 0);

        // Handle non-persistent receives.  Allow any receives scheduled within
        // a callback to persist.
        if (!mIsRecvPersistent && !mCallbackHasRecv) {
            mConn.cancelRecv();
        }
    }

    /**
     * Continues wrapping and sending until all application data in
     * mOutAppBuffer has been sent.
     */
    private void handleNetSend(AsyncConnection conn) {
        if (mSendType == SendType.EXTERNAL_MULTIPLE) {
            handleNetSendMultiple(conn);
            return;
        }

        if (!mOutAppBuffer.hasRemaining()) {
            // We're done wrapping and sending all data from the application.
            // Notify the caller.  This callback is always non-null.
            mAppSendCallback.onSend(this);
            return;
        }

        // Otherwise, we need to wrap as much of the remainder as possible and
        // schedule a new send.
        ByteBuffer outBuf = mConn.getOutBuffer();
        outBuf.clear();

        boolean done = false;
        do {
            SSLEngineResult result = mSSLEngine.wrap(mOutAppBuffer, outBuf);
            switch (result.getStatus()) {
                case SSLEngineResult.Status.BUFFER_OVERFLOW:
                    done = wrapSSLBufferOverflow(outBuf, wrapCount);
                    break;

                case SSLEngineResult.Status.BUFFER_UNDERFLOW:
                    // This should never happen with wrap, since there's
                    // always enough source data.
                    //
                    // Handle this gracefully by assuming that mOutAppBuffer
                    // is now empty and that wrapping should stop.
                    done = true;
                    break;

                case SSLEngineResult.Status.CLOSED:
                    // Bubble up the close.
                    if (mAppOnCloseCallback != null) {
                        mAppOnCloseCallback.onClose(this);
                    }

                    done = true;
                    break;

                case SSLEngineResult.Status.OK:
                    break;
            }

            if (!mOutAppBuffer.hasRemaining()) {
                break;
            }
        } while (!done);

        if (mIsClosed) {
            // There's no point sending when closed.  Just quit.
            return;
        }

        // Queue the outBuf for sending.
        outBuf.flip();
        mConn.send(mNetSendCallback);
    }

    private void handleNetSendMultiple(AsyncConnection conn) {
        ByteBuffer[] srcs = mExternalOutAppBuffers.
        ByteBufferArray srcArray = new ByteBufferArray(srcs);

        if (srcArray.remaining() == 0) {
            // We're done.  The callback is always non-null.
            mAppSendCallback.onSend(this);
            return;
        }

        ByteBuffer outBuf = mConn.getOutBuffer();
        outBuf.clear();

        boolean done = false;
        do {
            SSLEngineResult result = mSSLEngine.wrap(srcs,
                    srcArray.getNonEmptyOffset(), srcs.length, outBuf);
            switch (result.getStatus()) {
                case SSLEngineResult.Status.BUFFER_OVERFLOW:
                    done = wrapSSLBufferOverflow(outBuf, wrapCount);
                    break;

                case SSLEngineResult.Status.BUFFER_UNDERFLOW:
                    // Handle this gracefully by assuming that srcBuffers
                    // is now empty and that wrapping should stop.
                    done = true;
                    break;

                case SSLEngineResult.Status.CLOSED:
                    // Bubble up the close.
                    if (mAppOnCloseCallback != null) {
                        mAppOnCloseCallback.onClose(this);
                    }

                    done = true;
                    break;

                case SSLEngineResult.Status.OK:
                    break;
            }

            srcArray.update();
            if (srcArray.remaining() == 0) {
                // Nothing more to wrap.
                break;
            }
        } while (!done);

        if (mIsClosed) {
            return;
        }

        // Flush the out buffer.
        outBuf.flip();
        mConn.send(mNetSendCallback);
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
    private int unwrapSSLBufferOverflow(ByteBuffer buf, int unwrapCount) {
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
        return flushInAppBuffer(unwrapCount);
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
    private int unwrapSSLBufferUnderflow(ByteBuffer buf, int unwrapCount) {
        flushInAppBuffer(unwrapCount);
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
    private int unwrapSSLClosed(ByteBuffer buf, int unwrapCount) {
        // Flush any data, first.  Ignore any receives that might occur
        // within the callback, since we're shutting down.
        flushInAppBuffer(unwrapCount);

        // Bubble up the close.
        if (mAppOnCloseCallback != null) {
            mAppOnCloseCallback.onClose(this);
        }

        // Connection is closed.
        return -1;
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
    public void recvPersistent(OnRecvCallback callback) {
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

    @Override
    public void setOnCloseCallback(AsyncConnection.OnCloseCallback callback) {
        mAppCloseCallback = callback;
    }

    @Override
    public void setOnErrorCallback(AsyncConnection.OnErrorCallback callback) {
        mAppErrorCallback = callback;
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
     * Handles an SSLEngineResult.Status.BUFFER_OVERFLOW when wrapping, which
     * indicates that We don't have enough space in buf for wrapping.
     *
     * @param outBuf the outgoing network buffer.
     * @param wrapCount the number of successful wraps since the last
     * time mAppSendCallback was called.
     *
     * @return true if looping should stop because:
     * 1) data needs to be sent to the network or 2) the connection is
     * closed.
     */
    private boolean wrapSSLBufferOverflow(ByteBuffer outBuf, int wrapCount) {
        // Check that the capacity was not changed.
        int netSize = mSSLEngine.getSession().getPacketBufferSize();
        if (outbuf.capacity() < netSize) {
            outBuf = allocate(netSize);
            mConn.setOutBufferInternal(outBuf);

            // Now, retry with the larger buffer.
            return false;
        }

        // Otherwise, stop so that we can sending to the network to drain
        // outBuf.
        return true;
    }
}
