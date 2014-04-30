// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

/**
 * This is a wrapper for a ByteBuffer that provides additional state for tracking
 * position over multiple network operations.
 */
class NetBuffer {
    private enum State {
        APPEND,
        READ,
    }

    private State mState;

    // Tracks the starting position of the last unread data.  This persists
    // so that we can resume reading from the start of the buffer despite a
    // mix of appending and reading.
    private int mStartPos;

    protected ByteBuffer mBuf;

    /**
     * This wraps and manages buf.  The NetBuffer will be prepared for
     * appending.
     *
     * @param buf
     */
    public NetBuffer(ByteBuffer buf) {
        mState = APPEND;
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
    public ByteBuffer getByteBuffer() {
        return mBuf;
    }

    /**
     * Prepares the buffer for a network channel append.  The buffer will
     * be flipped so that its position will be the limit and the new limit
     * will be its capacity.
     */
    public void prepareAppend() {
        if (mState == APPEND) {
            return;
        }

        // Flip the buffer so that new writes will appear at the end of the
        // buffer.
        mBuf.position(mBuf.limit());
        mBuf.limit(mBuf.capacity());

        mState = APPEND;
    }

    /**
     * This should be called after a network channel append completes.
     * It prepares (flips) the buffer for reading from the start of unread data.
     */
    public void prepareRead() {
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
        } else {
            // Otherwise, READ.  We are already positioned to copy
            // unread data.
        }

        newBuf.put(mBuf);
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
     * Updates read position pointers based on the ByteBuffer's state.
     * This must be called after reading from the ByteBuffer to mark the
     * starting position of unread data.
     */
    public void updateRead() {
        if (mState != READ) {
            return;
        }

        mStartPos = mBuf.position();
    }
}
