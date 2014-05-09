// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngineResult;

/**
 * This is a wrapper for a ByteBuffer that provides additional state for tracking
 * position over multiple network operations.
 */
class NetBuffer implements NetReader {
    private enum State {
        APPEND,
        READ,
    }

    private State mState;

    // Tracks the starting position of the last unread data.  This persists
    // so that we can resume reading from the start of the buffer despite a
    // mix of appending and reading.
    private int mStartPos;

    private ByteBuffer mBuf;

    /**
     * Wraps a ByteBuffer that is positioned for appending data to buf's current position.
     *
     * @param buf must already be positioned for appending.
     */
    public static NetBuffer makeAppendBuffer(ByteBuffer buf) {
        return new NetBuffer(APPEND, buf);
    }

    /**
     * Wraps a ByteBuffer that is positioned for a read.
     *
     * @param buf must already be flipped for reading.
     */
    public static NetBuffer makeReadBuffer(ByteBuffer buf) {
        NetBuffer netBuf = new NetBuffer(READ, buf);
        netBuf.mStartPos = buf.position();
        return netBuf;
    }

    /**
     * This wraps and manages buf.  It is assumed that buf is positioned according to state.
     *
     * @param state
     * @param buf
     */
    private NetBuffer(State state, ByteBuffer buf) {
        mState = state;
        mBuf = buf;
    }

    /**
     * Clears the underlying buffer and related pointers maintained by the class.
     */
    public void clear() {
        mBuf.clear();
        mStartPos = 0;
    }

    /**
     * @return the underlying ByteBuffer.
     */
    protected ByteBuffer getByteBuffer() {
        return mBuf;
    }

    /**
     * @return true if the underlying buffer is cleared (ByteBuffer.clear()).
     */
    public boolean isCleared() {
        return (mBuf.position() == 0 && mBuf.limit() == mBuf.capacity());
    }

    /**
     * @return true if the underlying buffer is empty with respect to its
     * current state (read/append).
     */
    @Override
    public boolean isEmpty() {
        if (mState == READ) {
            return !(mbuf.hasRemaining());
        }
        return (mBuf.position() == mStartPos);
    }

    /**
     * @param size requested size.
     *
     * @return true if the underlying buffer must be resized to meet the given
     * size requirement.
     */
    public boolean needsResize(int size) {
        return (mBuf.capacity() < size);
    }

    /**
     * Prepares the buffer for a network channel append.  The buffer will
     * be flipped so that its position will be the limit and the new limit
     * will be its capacity.
     */
    private void prepareAppend() {
        if (mState == APPEND) {
            return;
        }

        // Flip the buffer so that new writes will appear at the end of the
        // read buffer.
        mBuf.position(mBuf.limit());
        mBuf.limit(mBuf.capacity());

        mState = APPEND;
    }

    /**
     * This should be called after a network channel append completes.
     * It prepares (flips) the buffer for reading from the start of unread data.
     */
    private void prepareRead() {
        if (mState == READ) {
            return;
        }

        mBuf.flip();

        // Adjust to the start of unread data.
        mBuf.position(mStartPos);

        mState = READ;
    }

    /**
     * Safe resize that preserves data:
     *
     * If reading, the data will be compacted from the current position.
     * If appending, the data will be compacted from the start of unread buffer
     * data.
     *
     * @param factory factory for allocating a new buffer.
     * @param size size in bytes for the resized buffer
     */
    public void resize(ByteBufferFactory factory, int size) {
        if (mBuf.capacity() == size) {
            return;
        }

        ByteBuffer newBuf = factory.make(size);
        mStartPos = 0;

        if (mState == APPEND) {
            mBuf.flip();
            mBuf.position(mStartPos);

            newBuf.put(mBuf);
        } else {
            // Otherwise, READ.  We are already positioned to copy unread data.
            newBuf.put(mBuf);

            // Adjust newBuf's pointers for future reads from the newly copied data.
            newBuf.flip();
        }

        mBuf = newBuf;
    }

    /**
     * Resizes the underlying ByteBuffer given the size.
     * No attempt is made to preserve data, so the caller should take care
     * to make sure that the buffer is empty before resizing.
     *
     * @param factory
     * @param size
     */
    public void resizeUnsafe(ByteBufferFactory factory, int size) {
        if (mBuf.capacity() == size) {
            return;
        }
        mBuf = factory.make(size);
        mStartPos = 0;
    }

    /**
     * SSL unwraps the contents of this buffer into dest.
     *
     * This method is safe and will prepare the current buffer for reading
     * and the dest buffer for appending.  See unwrapUnsafe() if this check
     * is not necessary.
     *
     * @param engine
     * @param dest
     */
    @Override
    public SSLEngineResult unwrap(SSLEngine engine, NetBuffer dest) {
        prepareRead();
        dest.prepareAppend();

        return engine.unwrap(mBuf, dest.mBuf);
    }

    /**
     * Unsafe variant of unwrap that does not prepare this or dest.
     *
     * @param engine
     * @param dest
     */
    public SSLEngineResult unwrapUnsafe(SSLEngine engine, NetBuffer dest) {
        return engine.unwrap(mBuf, dest.mBuf);
    }

    /**
     * Updates read position pointers based on the ByteBuffer's state.
     * This must be called after reading from the ByteBuffer to mark the
     * starting position of unread data.
     */
    @Override
    public void updateRead() {
        if (mState != READ) {
            return;
        }

        mStartPos = mBuf.position();
    }

    /**
     * Wraps the contents of this into dest.  "this" will be prepared
     * for reading, and dest will be prepared for appending.  See wrapUnsafe()
     * if this check is not needed.
     *
     * @param engine
     * @param dest
     */
    @Override
    public SSLEngineResult wrap(SSLEngine engine, NetBuffer dest) {
        prepareRead();
        dest.prepareAppend();

        return mSSLEngine.wrap(mBuf, dest);
    }

    /**
     * Variant of wrap that doesn't prepare "this" or dest.
     *
     * @param engine
     * @param dest
     */
    public SSLEngineResult wrapUnsafe(SSLEngine engine, NetBuffer dest) {
        return mSSLEngine.wrap(mBuf, dest);
    }
}
