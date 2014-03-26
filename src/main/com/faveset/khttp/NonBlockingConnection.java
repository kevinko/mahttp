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
class NonBlockingConnection implements AsyncConnection {
    private enum SendType {
        INTERNAL,
        INTERNAL_PARTIAL,
        // External ByteBuffers.
        EXTERNAL_SINGLE,
        EXTERNAL_MULTIPLE,
    }

    // This limits the number of consecutive read and write operations that
    // can occur before a Selector select on the connection.  It bounds
    // the stack depth in cases where callbacks immediately trigger active
    // sends.  It only affects sendImmediately() and recvImmediately().
    private static final int sMaxSeqOpCount = 2;

    private SocketChannel mChan;
    private SelectionKey mKey;

    private Pool<ByteBuffer> mPool;

    private PoolEntry<ByteBuffer> mInBufferInternalEntry;
    private PoolEntry<ByteBuffer> mOutBufferInternalEntry;

    // This can point to an external or internal buffer, depending on the
    // recv method.
    private ByteBuffer mInBuffer;

    private ByteBuffer mInBufferInternal;

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

    // Counts immediate reads and writes (sendImmediately, recvImmediately)
    // since the last onSelect operation.  This is used to bound the stack
    // depth, since send and receive methods can trigger callbacks immediately.
    private int mSeqOpCount;

    private SelectorHandler mSelectorHandler = new SelectorHandler() {
        @Override
        public void onReady(SelectionKey key) {
            onSelect();
        }
    };

    // True if the receive buffer is never cleared by the NonBlockingConnection.
    private boolean mIsRecvAppend;
    private boolean mIsRecvPersistent;

    private SendType mSendType;

    /**
     * @param inBuf the internal input ByteBuffer to use.
     * @param outBuf the internal output ByteBuffer to use.
     *
     * @throws IOException
     */
    public NonBlockingConnection(Selector selector, SocketChannel chan,
            ByteBuffer inBuf, ByteBuffer outBuf) throws IOException {
        chan.configureBlocking(false);
        mChan = chan;
        mKey = mChan.register(selector, 0);
        mKey.attach(mSelectorHandler);

        mInBufferInternal = inBuf;
        mOutBufferInternal = outBuf;

        mSendType = SendType.INTERNAL;
    }

    /**
     * The NonBlockingConnection manages registration of Selector interest.
     * A SelectorHandler will be attached to all Selector keys.
     *
     * @param selector
     * @param chan
     * @param bufferSize size in bytes for the send and receive buffers.
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
            Pool<ByteBuffer> pool) throws IOException {
        this(selector, chan, null, null);

        mPool = pool;
        mInBufferInternalEntry = pool.allocate();
        mInBufferInternal = mInBufferInternalEntry.get();
        mOutBufferInternalEntry = pool.allocate();
        mOutBufferInternal = mOutBufferInternalEntry.get();
    }

    /**
     * Cancels the receive handler and read interest on the selection key.
     *
     * @return the cancelled callback, which will be null if not assigned.
     */
    @Override
    public OnRecvCallback cancelRecv() {
        OnRecvCallback result = mOnRecvCallback;

        if (mOnRecvCallback == null) {
            return result;
        }

        // Reset to the initial state.
        mOnRecvCallback = null;
        mIsRecvAppend = false;
        mIsRecvPersistent = false;

        mInBuffer = null;

        int newOps = mKey.interestOps() & ~SelectionKey.OP_READ;
        mKey.interestOps(newOps);

        return result;
    }

    /**
     * Cancels the send handler and write interest on the selection key.
     * The send type will be reset to INTERNAL.
     *
     * @return the cancelled callback, which will be null if not assigned.
     */
    private OnSendCallback cancelSend() {
        OnSendCallback result = mOnSendCallback;

        if (mOnSendCallback == null) {
            return result;
        }

        mOnSendCallback = null;

        int newOps = mKey.interestOps() & ~SelectionKey.OP_WRITE;
        mKey.interestOps(newOps);

        mSendType = SendType.INTERNAL;
        mOutBuffer = null;
        mExternalOutBuffers = null;

        return result;
    }

    /**
     * Closes the underlying channel.  This should be called to clean up
     * resources.
     *
     * @throws IOException if the underlying socket experienced an I/O error.
     */
    @Override
    public void close() throws IOException {
        // This also cancels the key.
        mChan.close();

        // Unregister handlers to avoid reference loops.
        mKey.attach(null);

        // Clean up all possible external references.
        mExternalOutBuffers = null;
        mInBuffer = null;
        mOutBuffer = null;

        if (mPool != null) {
            mInBufferInternalEntry = mPool.release(mInBufferInternalEntry);
            mInBufferInternal = null;
            mOutBufferInternalEntry = mPool.release(mOutBufferInternalEntry);
            mOutBufferInternal = null;
        }

        // Unregister callbacks to avoid reference cycles.
        mOnCloseCallback = null;
        mOnErrorCallback = null;
        mOnRecvCallback = null;
        mOnSendCallback = null;
    }

    /**
     * @return the internal recv buffer.
     */
    public ByteBuffer getInBuffer() {
        return mInBufferInternal;
    }

    /**
     * @return the internal send buffer.
     */
    @Override
    public ByteBuffer getOutBuffer() {
        return mOutBufferInternal;
    }

    /**
     * Reads as much data as possible into the buffer and then triggers any
     * callbacks.
     */
    private void handleRead() throws IOException {
        if (mIsRecvPersistent) {
            handleReadPersistent();
            return;
        }

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

        // Save the buffer before cancelling the receive, since cancelRecv()
        // nulls the buffer.
        ByteBuffer buf = mInBuffer;

        // Cancel selector interest first, just in case the callback issues
        // a new recv().
        cancelRecv();

        // INVARIANT: callback is never null.
        callback.onRecv(this, buf);

        // NOTE: we must be careful at this point, because the connection might
        // be closed as a result of the callback.  Thus, return immediately.
    }

    private void handleReadPersistent() throws IOException {
        if (!mIsRecvAppend) {
            mInBuffer.clear();
        }

        int len = mChan.read(mInBuffer);
        if (len == -1) {
            // Remote triggered EOF.  Bubble up to the user for handling.
            if (mOnCloseCallback != null) {
                mOnCloseCallback.onClose(this);
            }
            return;
        }
        if (len == 0) {
            // Wait for another round.
            return;
        }

        // Ensure that the callback accesses the buffer contents from the start.
        mInBuffer.flip();

        // INVARIANT: callback is never null.
        mOnRecvCallback.onRecv(this, mInBuffer);

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

            // callback is never null per the INVARIANT.
            callback.onSend(this);

            // NOTE: the callback might close the connection as a result of
            // the callback.

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

                // callback is never null per the INVARIANT.
                callback.onSend(this);

                // NOTE: the callback might close the connection.
            }

            // Otherwise, the selector is still waiting for send opportunities.
            return;
        }

        // Save the callback to persist through cancel.
        OnSendCallback callback = mOnSendCallback;

        // Cancel the selector interest first so that the callback's
        // actions can take precedence.
        cancelSend();

        // callback is never null per the INVARIANT.
        callback.onSend(this);

        // NOTE: the callback might close the connection.
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
        // This is a fresh select operation, so reset the op count.
        mSeqOpCount = 0;

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
     * Configures the connection for receiving data using the internal
     * in buffer.  The callback will be called when new data is received.
     *
     * This is not persistent.
     *
     * NOTE: the buffer that is passed to the callback (the internal in buffer)
     * is only guaranteed for the life of the callback.
     *
     * The in buffer will also be cleared when the recv is performed.
     *
     * @param callback must not be null
     *
     * @throws IllegalArgumentException if callback is null.
     */
    @Override
    public void recv(OnRecvCallback callback) throws IllegalArgumentException {
        mInBuffer = mInBufferInternal;
        mInBuffer.clear();

        recvImpl(callback, false);
    }

    /**
     * This uses the internal in buffer by default.
     * @throws IllegalArgumentException if callback is null.
     */
    public void recvAppend(OnRecvCallback callback) throws IllegalArgumentException {
        recvAppend(callback, mInBufferInternal);
    }

    /**
     * A variant of recv() that appends to buf but does not clear it.  The
     * user must take care to manage buf beforehand and within callback.
     *
     * This is not persistent.
     *
     * @param callback must not be null
     * @param buf may be the internal ByteBuffer.
     * @throws IllegalArgumentException if callback is null.
     */
    public void recvAppend(OnRecvCallback callback, ByteBuffer buf) throws IllegalArgumentException {
        mInBuffer = buf;
        recvImpl(callback, false, true);
    }

    /**
     * This uses the internal in buffer by default.
     */
    public void recvAppendPersistent(OnRecvCallback callback) throws IllegalArgumentException {
        recvAppendPersistent(callback, mInBufferInternal);
    }

    /**
     * A persistent version of recvAppend.  The callback will remain scheduled
     * until the recv is cancelled with cancelRecv.
     *
     * The buffer will not be managed by NonBlockingConnection.  It
     * is the responsibility of the callback to drain the buffer as needed.
     *
     * @throws IllegalArgumentException if callback is null.
     */
    public void recvAppendPersistent(OnRecvCallback callback,
            ByteBuffer buf) throws IllegalArgumentException {
        mInBuffer = buf;

        recvImpl(callback, true, true);
    }

    /**
     * mIsRecvAppend will be false.
     *
     * @throws IllegalArgumentException if callback is null.
     */
    private void recvImpl(OnRecvCallback callback, boolean isPersistent) throws IllegalArgumentException {
        recvImpl(callback, isPersistent, false);
    }

    /**
     * @throws IllegalArgumentException if callback is null.
     */
    private void recvImpl(OnRecvCallback callback,
            boolean isPersistent, boolean isAppend) throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        if (mOnRecvCallback == null) {
            int newOps = mKey.interestOps() | SelectionKey.OP_READ;
            mKey.interestOps(newOps);
        }

        mOnRecvCallback = callback;
        mIsRecvAppend = isAppend;
        mIsRecvPersistent = isPersistent;

        // To minimize latency, try receiving immediately.
        recvImmediately();
    }

    /**
     * Attempts to receive immediately, while respecting mSeqOpCount to restrict
     * stack-depth.  Receive-related fields must already be prepared.
     *
     * The error callback will be called on error.
     */
    private void recvImmediately() {
        mSeqOpCount++;
        if (mSeqOpCount > sMaxSeqOpCount) {
            return;
        }

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
     *
     * The internal buffer will be cleared on each read from the network.
     *
     * @throws IllegalArgumentException if callback is null.
     */
    @Override
    public void recvPersistent(OnRecvCallback callback) throws IllegalArgumentException {
        mInBuffer = mInBufferInternal;
        // mInBuffer will be cleared in handleReadPersistent.

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
     *
     * @throws IllegalArgumentException if callback is null.
     */
    @Override
    public void send(OnSendCallback callback) throws IllegalArgumentException {
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
     *
     * @throws IllegalArgumentException if callback is null.
     */
    @Override
    public void send(OnSendCallback callback, ByteBuffer buf) throws IllegalArgumentException {
        sendImpl(SendType.EXTERNAL_SINGLE, buf, callback);
    }

    /**
     * A variant of send() that sends an array of ByteBuffers.  The callback
     * is not persistent.
     *
     * @param bufsRemaining is the total number of bytes remaining for bufs.
     * Set to 0 to calculate automatically.
     *
     * @throws IllegalArgumentException if callback is null.
     */
    @Override
    public void send(OnSendCallback callback, ByteBuffer[] bufs,
            long bufsRemaining) throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        mSendType = SendType.EXTERNAL_MULTIPLE;
        mExternalOutBuffers = bufs;

        if (bufsRemaining == 0) {
            for (int ii = 0; ii < bufs.length; ii++) {
                bufsRemaining += bufs[ii].remaining();
            }
        }

        if (bufsRemaining == 0) {
            // We're done.
            callback.onSend(this);
            return;
        }

        mExternalOutBuffersRemaining = bufsRemaining;

        registerSendCallback(callback);

        // To minimize latency, try sending immediately.
        sendImmediately();
    }

    /**
     * Attempts to send immediately, while respecting mSeqOpCount to restrict
     * stack-depth.  Send-related fields must already be prepared.
     *
     * The error callback will be called on error.
     */
    private void sendImmediately() {
        mSeqOpCount++;
        if (mSeqOpCount > sMaxSeqOpCount) {
            return;
        }

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
     *
     * @throws IllegalArgumentException if callback is null.
     */
    private void sendImpl(SendType type, ByteBuffer buf,
            OnSendCallback callback) throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException();
        }

        mSendType = type;
        mOutBuffer = buf;

        if (!mOutBuffer.hasRemaining()) {
            // We're done.
            callback.onSend(this);
            return;
        }

        registerSendCallback(callback);

        // To minimize latency, try sending immediately.
        sendImmediately();
    }

    /**
     * Replaces the internal in buffer.
     */
    public void setInBufferInternal(ByteBuffer buf) {
        if (mInBufferInternalEntry != null) {
            mInBufferInternalEntry = mPool.release(mInBufferInternalEntry);
        }
        mInBufferInternal = buf;
    }

    /**
     * Replaces the internal out buffer.
     */
    public void setOutBufferInternal(ByteBuffer buf) {
        if (mOutBufferInternalEntry != null) {
            mOutBufferInternalEntry = mPool.release(mOutBufferInternalEntry);
        }
        mOutBufferInternal = buf;
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
    @Override
    public void sendPartial(OnSendCallback callback) {
        sendImpl(SendType.INTERNAL_PARTIAL, mOutBufferInternal, callback);
    }

    /**
     * Configures the callback that will be called when the connection is
     * closed.
     *
     * @return this for chaining
     */
    @Override
    public AsyncConnection setOnCloseCallback(OnCloseCallback callback) {
        mOnCloseCallback = callback;
        return this;
    }

    /**
     * Configures the callback that will be called when an unrecoverable
     * network error is encountered.
     *
     * @return this for chaining
     */
    @Override
    public AsyncConnection setOnErrorCallback(OnErrorCallback callback) {
        mOnErrorCallback = callback;
        return this;
    }

    /**
     * @return the underlying SocketChannel
     */
    @Override
    public SocketChannel socketChannel() {
        return mChan;
    }
}
