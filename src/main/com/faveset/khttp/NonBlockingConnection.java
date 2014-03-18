// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * We follow a standard protocol for handling connection closes:
 *
 *   - When a close is detected, we notify the user of the NonBlockingConnection
 *   via the OnCloseCallback.  We then expect the user to call close() when
 *   ready.
 */
class NonBlockingConnection {
    /**
     * Called when the connection is closed by the peer.  The callback should
     * call close() to clean up.
     *
     * Socket closes are only detected on read, because send errors can
     * manifest in different ways (various IOExceptions).
     */
    public interface OnCloseCallback {
        void onClose(NonBlockingConnection conn);
    }

    /**
     * Called whenever an unrecoverable error (e.g., IOException) occurs
     * while reading/writing the underlying socket.
     */
    public interface OnErrorCallback {
        void onError(NonBlockingConnection conn, String reason);
    }

    /**
     * Called when new data is received.  buf is the in buffer.
     */
    public interface OnRecvCallback {
        void onRecv(NonBlockingConnection conn, ByteBuffer buf);
    }

    public interface OnSendCallback {
        void onSend(NonBlockingConnection conn);
    }

    private enum SendType {
        INTERNAL,
        INTERNAL_PARTIAL,
        // External ByteBuffers.
        EXTERNAL_SINGLE,
        EXTERNAL_MULTIPLE,
    }

    private SocketChannel mChan;
    private SelectionKey mKey;

    private PoolInterface<ByteBuffer> mPool;

    private PoolEntry<ByteBuffer> mInBufferEntry;
    private PoolEntry<ByteBuffer> mOutBufferInternalEntry;

    private ByteBuffer mInBuffer;

    // This can point to an external or internal buffer.  It is configured
    // by send methods.
    private ByteBuffer mOutBuffer;

    private ByteBuffer mOutBufferInternal;

    private ByteBuffer[] mExternalOutBuffers;
    private long mExternalOutBuffersRemaining;

    private OnCloseCallback mOnCloseCallback;
    private OnErrorCallback mOnErrorCallback;

    // INVARIANT: these callbacks are non-null iff a recv/send is scheduled
    // with the Selector.
    private OnRecvCallback mOnRecvCallback;
    private OnSendCallback mOnSendCallback;

    private SelectorHandler mSelectorHandler = new SelectorHandler() {
        public void onReady(SelectionKey key) {
            onSelect();
        }
    };

    private boolean mIsRecvPersistent;

    private SendType mSendType;

    /**
     * @param inBuf the internal input ByteBuffer to use.
     * @param outBuf the internal output ByteBuffer to use.
     *
     * @throws IOException
     */
    private NonBlockingConnection(Selector selector, SocketChannel chan,
            ByteBuffer inBuf, ByteBuffer outBuf) throws IOException {
        chan.configureBlocking(false);
        mChan = chan;
        mKey = mChan.register(selector, 0);
        mKey.attach(mSelectorHandler);

        mInBuffer = inBuf;
        mOutBufferInternal = outBuf;

        mSendType = SendType.INTERNAL;
    }

    /**
     * The NonBlockingConnection manages registration of Selector interest.
     * A SelectorHandler will be attached to all Selector keys.
     *
     * @bufferSize size in bytes for the send and receive buffers.
     *
     * @throws IOException on I/O error.
     */
    public NonBlockingConnection(Selector selector, SocketChannel chan, int bufferSize) throws IOException {
        this(selector, chan,
                ByteBuffer.allocateDirect(bufferSize),
                ByteBuffer.allocateDirect(bufferSize));
    }

    /**
     * This variant uses the given pool for allocation.
     */
    public NonBlockingConnection(Selector selector, SocketChannel chan,
            PoolInterface<ByteBuffer> pool) throws IOException {
        this(selector, chan, null, null);

        mPool = pool;
        mInBufferEntry = pool.allocate();
        mInBuffer = mInBufferEntry.get();
        mOutBufferInternalEntry = pool.allocate();
        mOutBufferInternal = mOutBufferInternalEntry.get();
    }

    /**
     * Cancels the receive handler and read interest on the selection key.
     *
     * @return the cancelled callback, which will be null if not assigned.
     */
    public OnRecvCallback cancelRecv() {
        OnRecvCallback result = mOnRecvCallback;

        if (mOnRecvCallback == null) {
            return result;
        }

        mOnRecvCallback = null;
        mIsRecvPersistent = false;

        int newOps = mKey.interestOps() & ~SelectionKey.OP_READ;
        mKey.interestOps(newOps);

        return result;
    }

    /**
     * Cancels the send handler and write interest on the selection key.
     * The send type will be reset to INTERNAL.
     */
    private void cancelSend() {
        if (mOnSendCallback == null) {
            return;
        }

        mOnSendCallback = null;

        int newOps = mKey.interestOps() & ~SelectionKey.OP_WRITE;
        mKey.interestOps(newOps);

        mSendType = SendType.INTERNAL;
        mOutBuffer = null;
    }

    /**
     * Closes the underlying channel.  This should be called to clean up
     * resources.
     *
     * @throws IOException if the underlying socket experienced an I/O error.
     */
    public void close() throws IOException {
        mChan.close();
        mKey.cancel();

        if (mPool != null) {
            mInBufferEntry = mPool.release(mInBufferEntry);
            mInBuffer = null;
            mOutBufferInternalEntry = mPool.release(mOutBufferInternalEntry);
            mOutBufferInternal = null;
        }
    }

    public ByteBuffer getInBuffer() {
        return mInBuffer;
    }

    /**
     * @return the internal send buffer.
     */
    public ByteBuffer getOutBuffer() {
        return mOutBufferInternal;
    }

    /**
     * @return the SelectionKey for the connection.
     */
    public SelectionKey getSelectionKey() {
        return mKey;
    }

    /**
     * Reads as much data as possible into the buffer and then triggers any
     * callbacks.
     */
    private void handleRead() throws IOException {
        // Callbacks are triggered after every non-empty read.  Thus,
        // it's safe to clear the buffer here for new data (rather than after
        // the callback).
        //
        // In the common case, mInBuffer will not be cleared prior to this call,
        // so it will not be wasted.
        //
        // Exceptions include the two len cases below the read, which are rare.
        mInBuffer.clear();

        int len = mChan.read(mInBuffer);
        if (len == -1) {
            // Remote triggered EOF.  Bubble up to the user for handling.
            if (mOnCloseCallback != null) {
                mOnCloseCallback.onClose(this);
            }
            return;
        }
        if (len == 0) {
            // Even though we use a selector, it is possible for a 0 length
            // read, as the selection is merely a hint.
            //
            // Wait for another round.
            return;
        }

        // Ensure that the callback accesses the buffer contents from the start.
        mInBuffer.flip();

        // Save the callback so that it will persist even through cancelling.
        OnRecvCallback callback = mOnRecvCallback;

        if (!mIsRecvPersistent) {
            // Cancel selector interest first to yield to the callback.
            cancelRecv();
        }

        if (callback != null) {
            callback.onRecv(this, mInBuffer);
        }

        // NOTE: we must be careful at this point, because the connection might
        // be closed as a result of the callback.  Thus, return immediately.
    }

    /**
     * Writes data from the buffer to the channel.  Triggers a callback on
     * partial send.  Otherwise, the callback will be triggered only when the
     * buffer is exhausted.
     */
    private void handleWrite() throws IOException {
        if (mSendType == SendType.EXTERNAL_MULTIPLE) {
            long len = mChan.write(mExternalOutBuffers);
            mExternalOutBuffersRemaining -= len;
            if (mExternalOutBuffersRemaining > 0) {
                // We need to continue later.
                return;
            }

            // Otherwise, we're done.  Save the callback so that it will persist
            // through the cancel.
            OnSendCallback callback = mOnSendCallback;

            // Since sends are not persistent, cancel the selector first, in
            // case the callback decides to reconfigure a send.
            cancelSend();

            if (callback != null) {
                callback.onSend(this);
            }

            return;
        }

        int len = mChan.write(mOutBuffer);
        if (len == 0) {
            // The selection key hint was incorrect.  Just wait for another
            // round.
            return;
        }

        if (mOutBuffer.hasRemaining()) {
            if (mSendType == SendType.INTERNAL_PARTIAL) {
                // Save the callback to persist through cancel.
                OnSendCallback callback = mOnSendCallback;

                // Cancel the selector interest first so that the callback's
                // actions can take precedence.
                cancelSend();

                if (callback != null) {
                    callback.onSend(this);
                }
            }

            // Otherwise, the selector is still waiting for send opportunities.
            return;
        }

        // Save the callback to persist through cancel.
        OnSendCallback callback = mOnSendCallback;

        // Cancel the selector interest first so that the callback's
        // actions can take precedence.
        cancelSend();

        if (callback != null) {
            callback.onSend(this);
        }
    }

    /**
     * This should be called when the Selector selects the key managed by the
     * connection.  We enforce this by associating a SelectorHandler with each
     * key.
     *
     * The key need not be removed from the ready set during each iteration
     * of the Selector's select loop.
     */
    private void onSelect() {
        try {
            SelectionKey key = mKey;

            // We need to check the interest ops, because Selectors will
            // preserve their existing ready bits if the key is already in
            // the ready set.  By checking here, we eliminate the need
            // for one to remove the key from the set at every
            // select iteration, which is more expensive than a bitwise op.
            int ops = key.interestOps();
            if (key.isValid() &&
                    (ops & SelectionKey.OP_READ) != 0 &&
                    key.isReadable()) {
                handleRead();
            }

            if (key.isValid() &&
                    (ops & SelectionKey.OP_WRITE) != 0 &&
                    key.isWritable()) {
                handleWrite();
            }
        } catch (IOException e) {
            if (mOnErrorCallback != null) {
                mOnErrorCallback.onError(this, e.toString());
            }
        }
    }

    /**
     * Configures the connection for receiving data.  The callback will be
     * called when new data is received.
     *
     * This is not persistent.
     *
     * NOTE: the buffer that is passed to the callback (the internal in buffer)
     * is only guaranteed for the life of the callback.
     *
     * The in buffer will also be cleared when the recv is performed.
     */
    public void recv(OnRecvCallback callback) {
        recvImpl(callback, false);
    }

    private void recvImpl(OnRecvCallback callback, boolean isPersistent) {
        if (mOnRecvCallback == null) {
            int newOps = mKey.interestOps() | SelectionKey.OP_READ;
            mKey.interestOps(newOps);
        }

        mOnRecvCallback = callback;
        mIsRecvPersistent = isPersistent;

        // To minimize latency, try receiving immediately.
        try {
            handleRead();
        } catch (IOException e) {
            if (mOnErrorCallback != null) {
                mOnErrorCallback.onError(this, e.toString());
            }
        }
    }

    /**
     * A persistent version of recv.  The callback will remain scheduled
     * until the recv is cancelled with cancelRecv.
     */
    public void recvPersistent(OnRecvCallback callback) {
        recvImpl(callback, true);
    }

    /**
     * Schedules the selector to listen for write opportunities and assigns
     * mOnSendCallback.
     */
    private void registerSendCallback(OnSendCallback callback) {
        if (mOnSendCallback == null) {
            int newOps = mKey.interestOps() | SelectionKey.OP_WRITE;
            mKey.interestOps(newOps);
        }

        mOnSendCallback = callback;
    }

    /**
     * Schedules the contents of the out buffer for sending.  Callback will
     * be called when the buffer is completely drained.  The buffer will not
     * be compacted, cleared, or otherwise modified.  The callback is not
     * persistent.
     */
    public void send(OnSendCallback callback) {
        sendImpl(SendType.INTERNAL, mOutBufferInternal, callback);
    }

    /**
     * A variant of send that uses the contents of buf instead of the built-in
     * buffer.  (This is useful for sending MappedByteBuffers.)  The callback
     * is not persistent.
     *
     * IMPORTANT: One must not manipulate the buffer while a send is in
     * progress, since the contents are not copied.
     *
     * @param callback will be called on completion.
     */
    public void send(OnSendCallback callback, ByteBuffer buf) {
        sendImpl(SendType.EXTERNAL_SINGLE, buf, callback);
    }

    /**
     * A variant of send() that sends an array of ByteBuffers.  The callback
     * is not persistent.
     *
     * @param bufsRemaining is the total number of bytes remaining for bufs.
     * Set to 0 to calculate automatically.
     */
    public void send(OnSendCallback callback, ByteBuffer[] bufs, long bufsRemaining) {
        mSendType = SendType.EXTERNAL_MULTIPLE;
        mExternalOutBuffers = bufs;

        if (bufsRemaining == 0) {
            for (int ii = 0; ii < bufs.length; ii++) {
                bufsRemaining += bufs[ii].remaining();
            }
        }

        if (bufsRemaining == 0) {
            // We're done.
            if (callback != null) {
                callback.onSend(this);
            }
            return;
        }

        mExternalOutBuffersRemaining = bufsRemaining;

        registerSendCallback(callback);

        // To minimize latency, try sending immediately.
        try {
            handleWrite();
        } catch (IOException e) {
            if (mOnErrorCallback != null) {
                mOnErrorCallback.onError(this, e.toString());
            }
        }
    }

    /**
     * Schedules the contents of the given buffer for sending.  Callback will
     * be called when the buffer is completely drained (immediately if the
     * buffer is empty).  The buffer will not be compacted, cleared, or
     * otherwise modified.  The callback is not persistent.
     *
     * @param type the SendType to use
     * @param buf
     * @param callback
     */
    private void sendImpl(SendType type, ByteBuffer buf, OnSendCallback callback) {
        mSendType = type;
        mOutBuffer = buf;

        if (!mOutBuffer.hasRemaining()) {
            // We're done.
            if (callback != null) {
                callback.onSend(this);
            }
            return;
        }

        registerSendCallback(callback);

        // To minimize latency, try sending immediately.
        try {
            handleWrite();
        } catch (IOException e) {
            if (mOnErrorCallback != null) {
                mOnErrorCallback.onError(this, e.toString());
            }
        }
    }

    /**
     * Schedules the contents of the out buffer for sending.  Callback will
     * be called as soon data has been sent from the buffer.  This is not
     * persistent.  As such, the sendPartial will typically be
     * rescheduled in the callback.
     *
     * If a send is called during the callback, it will take priority over
     * whatever remains in the internal buffer.
     */
    public void sendPartial(OnSendCallback callback) {
        sendImpl(SendType.INTERNAL_PARTIAL, mOutBufferInternal, callback);
    }

    /**
     * Configures the callback that will be called when the connection is
     * closed.
     *
     * @return this for chaining
     */
    public NonBlockingConnection setOnCloseCallback(OnCloseCallback callback) {
        mOnCloseCallback = callback;
        return this;
    }

    /**
     * Configures the callback that will be called when an unrecoverable
     * network error is encountered.
     *
     * @return this for chaining
     */
    public NonBlockingConnection setOnErrorCallback(OnErrorCallback callback) {
        mOnErrorCallback = callback;
        return this;
    }

    /**
     * @return the underlying SocketChannel
     */
    public SocketChannel socketChannel() {
        return mChan;
    }
}
