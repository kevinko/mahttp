package com.faveset.khttp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

class NonBlockingConnection {
    /**
     * Called when the connection is closed by the peer.  The callback should
     * call close().
     */
    public interface OnCloseCallback {
        void onClose(NonBlockingConnection conn);
    }

    /**
     * Called when new data is received.  buf is the in buffer.
     */
    public interface OnRecvCallback {
        void onRecv(NonBlockingConnection conn, ByteBuffer buf);
    }

    /**
     * buf is the out buffer that can be used for writing more data.
     */
    public interface OnSendCallback {
        void onSend(NonBlockingConnection conn, ByteBuffer buf);
    }

    private SocketChannel mChan;
    private SelectionKey mKey;

    private ByteBuffer mInBuffer;
    private ByteBuffer mOutBuffer;

    private OnCloseCallback mOnCloseCallback;

    // INVARIANT: these callbacks are non-null iff a recv/send is scheduled
    // with the Selector.
    private OnRecvCallback mOnRecvCallback;
    private OnSendCallback mOnSendCallback;

    private boolean mIsPartialSend;

    private boolean mIsRecvPersistent;

    /**
     * @bufferSize size in bytes for the send and receive buffers.
     */
    public NonBlockingConnection(Selector selector, SocketChannel chan, int bufferSize) throws IOException {
        chan.configureBlocking(false);
        mChan = chan;
        mKey = mChan.register(selector, 0);

        mInBuffer = ByteBuffer.allocateDirect(bufferSize);
        mOutBuffer = ByteBuffer.allocateDirect(bufferSize);
    }

    /**
     * Cancels the receive handler and read interest on the selection key.
     */
    public void cancelRecv() {
        if (mOnRecvCallback == null) {
            return;
        }

        mOnRecvCallback = null;
        mIsRecvPersistent = false;

        int newOps = mKey.interestOps() & ~SelectionKey.OP_READ;
        mKey.interestOps(newOps);
    }

    /**
     * Cancels the send handler and write interest on the selection key.
     */
    private void cancelSend() {
        if (mOnSendCallback == null) {
            return;
        }

        mOnSendCallback = null;

        int newOps = mKey.interestOps() & ~SelectionKey.OP_WRITE;
        mKey.interestOps(newOps);
    }

    /**
     * Closes the underlying channel.  This should be called to clean up
     * resources.
     */
    public void close() throws IOException {
        mChan.close();
        mKey.cancel();
    }

    public ByteBuffer getInBuffer() {
        return mInBuffer;
    }

    public ByteBuffer getOutBuffer() {
        return mOutBuffer;
    }

    /**
     * Reads as much data as possible into the buffer and then triggers any
     * callbacks.
     */
    private void handleRead() throws IOException {
        int len = mChan.read(mInBuffer);
        if (len == -1) {
            // Remote triggered EOF.
            if (mOnCloseCallback != null) {
                mOnCloseCallback.onClose(this);
            }
            return;
        }
        if (mOnRecvCallback != null) {
            mOnRecvCallback.onRecv(this, mInBuffer);
        }
        if (!mIsRecvPersistent) {
            cancelRecv();
        }
    }

    /**
     * Writes data from the buffer to the channel.  Triggers a callback on
     * partial send.  Otherwise, the callback will be triggered only when the
     * buffer is exhausted.
     */
    private void handleWrite() throws IOException {
        int len = mChan.write(mOutBuffer);

        if (mOutBuffer.hasRemaining()) {
            if (mIsPartialSend) {
                if (mOnSendCallback != null) {
                    mOnSendCallback.onSend(this, mOutBuffer);
                }

                cancelSend();
            }
            return;
        }

        if (mOnSendCallback != null) {
            mOnSendCallback.onSend(this, mOutBuffer);
        }
        cancelSend();
    }

    // This will be called when the Selector selects the key managed by the
    // connection.
    public void onSelect(SelectionKey key) throws IOException {
        if (!key.isValid()) {
            return;
        }

        if (key.isReadable()) {
            handleRead();
        }

        if (key.isWritable()) {
            handleWrite();
        }
    }

    /**
     * Configures the connection for receiving data.  The callback will be
     * called when new data is received.
     */
    public void recv(OnRecvCallback callback) {
        recvImpl(callback, false);
    }

    /**
     * A persistent version of recv.  The callback will remain scheduled
     * until the recv is cancelled with cancelRecv.
     */
    public void recvPersistent(OnRecvCallback callback) {
        recvImpl(callback, true);
    }

    private void recvImpl(OnRecvCallback callback, boolean isPersistent) {
        if (mOnRecvCallback == null) {
            int newOps = mKey.interestOps() | SelectionKey.OP_READ;
            mKey.interestOps(newOps);
        }

        mOnRecvCallback = callback;
        mIsRecvPersistent = isPersistent;
    }

    /**
     * Schedules the contents of the out buffer for sending.  Callback will
     * be called when the buffer is completely drained.  The buffer will not
     * be compacted, cleared, or otherwise modified.  The callback is not
     * persistent.
     */
    public void send(OnSendCallback callback) {
        sendImpl(callback, false);
    }

    /**
     * Schedules the contents of the out buffer for sending.  Callback will
     * be called as soon data has been sent from the buffer.  This is not
     * persistent.
     */
    public void sendPartial(OnSendCallback callback) {
        sendImpl(callback, true);
    }

    private void sendImpl(OnSendCallback callback, boolean isPartial) {
        if (mOnSendCallback == null) {
            int newOps = mKey.interestOps() | SelectionKey.OP_WRITE;
            mKey.interestOps(newOps);
        }

        mOnSendCallback = callback;
        mIsPartialSend = isPartial;
    }

    /**
     * Configures the callback that will be called when the connection is
     * closed.
     */
    public NonBlockingConnection setOnCloseCallback(OnCloseCallback callback) {
        mOnCloseCallback = callback;
        return this;
    }
}
