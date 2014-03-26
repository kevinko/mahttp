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

class SSLNonBlockingConnection implements AsyncConnection {
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
    // compaction.
    private int mInNetBufferStart;

    // The internal in buffer that holds plaintext unwrapped from the SSLEngine.
    // Unlike with NonBlockingConnection, we do not allow an app-configurable
    // in buffer.  This is necessary, because the SSLEngine dictates the
    // minimum app buffer size for unwrapping.
    private ByteBuffer mInAppBuffer;

    // Points to the active out buffer.
    // INVARIANT: non-null when a send() is scheduled.
    private ByteBuffer mOutAppBuffer;

    // The internal out buffer that applications can use.  This will hold data
    // from the user that is directed to the network.
    private ByteBuffer mOutAppBufferInternal;

    private AsyncConnection.OnCloseCallback mAppCloseCallback;

    private AsyncConnection.OnErrorCallback mAppErrorCallback;

    // INVARIANT: This is always non-NULL when a recv() is scheduled.
    private AsyncConnection.OnRecvCallback mAppRecvCallback;

    private AsyncConnection.OnSendCallback mAppSendCallback;

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

        mSSLEngine.closeInbound();
        mSSLEngine.closeOutbound();
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
     * We always use the "Append" (recvAppend/recvAppendPersistent) forms
     * of recv.  Thus, it is necessary to manage the net buffer explicitly
     * after draining it.
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
        // Since app buffer sizes are fixed integer sizes, this will never wrap
        // around.
        int unwrapCount = 0;
        do {
            // This unwraps a single SSL/TLS packet.
            SSLEngineResult result = mSSLEngine.unwrap(buf, mInAppBuffer);
            switch (result.getStatus()) {
                case SSLEngineResult.Status.BUFFER_OVERFLOW:
                    unwrapCount = processSSLBufferOverflow(buf, unwrapCount);
                    break;

                case SSLEngineResult.Status.BUFFER_UNDERFLOW:
                    unwrapCount = processSSLBufferUnderflow(buf, unwrapCount);
                    break;

                case SSLEngineResult.Status.CLOSED:
                    unwrapCount = processSSLBufferClosed(buf, unwrapCount);
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
    }

    /**
     * Handles an SSLEngineResult.Status.BUFFER_OVERFLOW.
     *
     * @param unwrapCount the number of successful unwraps since the last
     * time mAppRecvCallback was called.
     *
     * @return the new unwrapCount, since the last time mAppRecvCallback
     * was called.  This is negative if looping should stop because: 1) more
     * data is needed from the network or 2) the connection is closed.  In that
     * case, mInAppBuffer will be drained.
     */
    private int processSSLBufferOverflow(ByteBuffer buf, int unwrapCount) {
        // We don't have enough space in mInAppBuffer for unwrapping.

        // See if the app buffer is completely empty, in which case
        // we need to resize it.
        if (mInAppBuffer.position() == 0) {
            if (mInAppBuffer.limit() == mInAppBuffer.capacity()) {
                // The buffer is empty.  The engine must need
                // a larger buffer.  Resize it.
                int appSize = mSSLEngine.getSession().getApplicationBufferSize();
                mInAppBuffer = allocate(appSize);
            } else {
                // The buffer is empty but shorter for whatever
                // reason.  This shouldn't happen, but clear it
                // just in case it does.
                mInAppBuffer.clear();
            }

            // Try unwrapping with the resized buffer.
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
        } else if (!mIsRecvPersistent && !mCallbackHasRecv) {
            // Non-persistent receives must stop after the callback.
            return -1;
        } else {
            // Prepare for another unwrap attempt, which should
            // now succeed with an empty app buffer.
            mInAppBuffer.clear();
        }

        return unwrapCount;
    }

    /**
     * Handles an SSLEngineResult.Status.BUFFER_UNDERFLOW.
     *
     * @param unwrapCount the number of successful unwraps since the last
     * time mAppRecvCallback was called.
     *
     * @return the new unwrapCount, since the last time mAppRecvCallback
     * was called.  This is always negative to indicate that looping should
     * stop because: 1) more data is needed from the network or 2) the
     * connection is closed.  mInAppBuffer will be drained.
     */
    private int processSSLBufferUnderflow(ByteBuffer buf, int unwrapCount) {
        if (unwrapCount > 0) {
            // Flush the buffered application data.  There's no
            // need to clear the app buffer, since we're done
            // unwrapping until new net data arrives.
            mInAppBuffer.flip();

            // Maintain INVARIANT.
            mCallbackHasRecv = false;
            mAppRecvCallback.onRecv(this, mInAppBuffer);

            // NOTE: the callback might close the connection at this
            // point.
            if (mIsClosed) {
                return -1;
            } else if (!mIsRecvPersistent && !mCallbackHasRecv) {
                // Handle non-persistent receives.
                return -1;
            }
        }

        // Buffer size requirements might have changed, so check.
        int netSize = mSSLEngine.getSession().getPacketBufferSize();
        if (netSize > buf.capacity()) {
            // Our network receive buffer is not large enough.  Resize it.
            ByteBuffer newInBuf = allocate(netSize);

            // Preserve the already received packet data.
            buf.flip();
            // Ensure that we use the right starting point.
            buf.position(mInNetBufferStart);

            newInBuf.put(buf);
            // The new incoming net buffer is aligned at 0.
            mInNetBufferStart = 0;

            mConn.setInBufferInternal(newInBuf);
            // Now, continue appending net data into the buffer.
        } else {
            // Our buffer is correctly sized.  The SSL packet is fragmented
            // so that we only have a partial copy of it.  This can be due
            // to two reasons: 1) we haven't received enough data or
            // 2) our buffer is full and not fully utilizing its space.

            if (!buf.hasRemaining()) {
                // This is 2).  Buffer is full and not utilizing its space.
                buf.compact();
                mInNetBufferStart = 0;
            }
        }

        // More data is needed from the network.
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
    private int processSSLClosed(ByteBuffer buf, int unwrapCount) {
        // Flush any data, first.
        if (unwrapCount > 0) {
            // Flush the buffered application data.  There's no
            // need to zero unwrapCount or clear the buffer,
            // since done == true.
            mInAppBuffer.flip();

            // Maintain INVARIANT.
            mCallbackHasRecv = false;
            mAppRecvCallback.onRecv(this, mInAppBuffer);

            // NOTE: the callback might close the connection at this
            // point.
            //
            // We also don't care about rescheduled receives, since the
            // connection is shutting down.
        }

        // Bubble up the close.
        if (mAppOnCloseCallback != null) {
            mAppOnCloseCallback.onClose(this);
        }

        // Connection is closed.
        return -1;
    }

    @Override
    public void recv(OnRecvCallback callback) {
        mInNetBufferStart = 0;

        // Maintain INVARIANT.
        mCallbackHasRecv = true;

        mIsRecvPersistent = false;
        mAppRecvCallback = callback;

        mConn.recv(mNetRecvCallback);
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
}
