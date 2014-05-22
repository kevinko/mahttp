// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

/**
 * This is a wrapper for a ByteBuffer that provides additional state for tracking
 * position over multiple network operations.
 */
class NetBuffer implements NetReader {
    protected enum State {
        APPEND,
        READ,
    }

    private State mState;

    // Tracks the starting position of the last unread data.  This persists
    // so that we can resume reading from the start of the buffer.  It is updated
    // explicitly by updateRead().
    private int mReadStartPos;

    private ByteBuffer mBuf;

    /**
     * Wraps a ByteBuffer that is initially positioned for appending data to buf's current position.
     *
     * @param buf must already be positioned for appending.
     */
    public static NetBuffer makeAppendBuffer(ByteBuffer buf) {
        return new NetBuffer(State.APPEND, buf);
    }

    /**
     * Wraps a ByteBuffer that is initially positioned for a read.
     *
     * @param buf must already be flipped for reading.
     */
    public static NetBuffer makeReadBuffer(ByteBuffer buf) {
        return new NetBuffer(State.READ, buf);
    }

    /**
     * This wraps and manages buf.  It is assumed that buf is initially positioned according to
     * state.
     *
     * @param state
     * @param buf
     */
    protected NetBuffer(State state, ByteBuffer buf) {
        mState = state;
        mBuf = buf;

        if (state == State.READ) {
            mReadStartPos = buf.position();
        }
    }

    /**
     * Clears the underlying buffer and related pointers maintained by the class.
     */
    public void clear() {
        mBuf.clear();
        mReadStartPos = 0;

        if (mState == State.READ) {
            // Make sure that remaining() is empty so that reads will be empty as well.
            mBuf.limit(0);
        }
    }

    /**
     * Compacts the contents of the buffer so that they all start at the front of the buffer.
     */
    public void compact() {
        if (mState == State.APPEND) {
            // Effect a flipRead().
            mBuf.flip();
            mBuf.position(mReadStartPos);
        }

        mBuf.compact();
        // ByteBuffer compact leaves mBuf positioned for appending.

        mReadStartPos = 0;

        if (mState == State.READ) {
            // Reposition for reading.
            mBuf.flip();
            mBuf.position(mReadStartPos);
        }
    }

    /**
     * Prepares the buffer for a network channel append.  The buffer will
     * be flipped so that its position will be the limit and the new limit
     * will be its capacity.
     *
     * This method is normally called prior to passing the underlying buffer to a channel that will
     * write to the buffer.
     *
     * Nothing will be done if the buffer is already in the APPEND state.
     */
    public void flipAppend() {
        if (mState == State.APPEND) {
            return;
        }

        // Flip the buffer so that new writes will appear at the end of the
        // read buffer.
        mBuf.position(mBuf.limit());
        mBuf.limit(mBuf.capacity());

        mState = State.APPEND;
    }

    /**
     * This should be called after a network channel append completes before reading data from the
     * buffer.  It flips the buffer for reading from the start of unread data, as determined by
     * the last call to updateRead().
     *
     * This method is normally called after a channel has written data to the underlying ByteBuffer
     * and before reading data from the underlying ByteBuffer.
     *
     * Nothing will be done if the buffer is already in the read state.
     */
    public void flipRead() {
        if (mState == State.READ) {
            return;
        }

        mBuf.flip();
        mBuf.position(mReadStartPos);

        mState = State.READ;
    }

    /**
     * This returns the underlying ByteBuffer.
     *
     * Use this or backwards compatibility with channel operations.  One must take care to ensure
     * that read and append states are properly maintained (see
     * setRead/setAppend/flipRead/flipAppend).
     *
     * @return the underlying ByteBuffer.
     */
    protected ByteBuffer getByteBuffer() {
        return mBuf;
    }

    /**
     * @return true if the buffer's contents are all at the start of the buffer.
     */
    public boolean isCompacted() {
        // We only worry about the initial read position.  limit is meaningless in this context,
        // since flipAppend() will always set the limit to capacity.
        int pos = mBuf.position();

        if (mState == State.APPEND) {
            // Imitate a flipRead().
            pos = mReadStartPos;
        }

        return (pos == 0);
    }

    /**
     * A cleared append buffer has position 0 and limit == capacity.  Note that being cleared is a
     * superset of being empty.  A cleared buffer is both empty and has a limit at full capacity
     * when appending, which is equivalent to the state after a ByteBuffer.clear().
     *
     * A read buffer is temporarily flipped to append state before evaluating the condition.
     *
     * @return true if the underlying buffer is cleared for a subsequent append.
     */
    public boolean isCleared() {
        int pos = mBuf.position();
        int limit = mBuf.limit();

        if (mState == State.READ) {
            // Imitate a flipAppend().
            pos = mBuf.limit();
            limit = mBuf.capacity();
        }

        return (pos == 0 && limit == mBuf.capacity());
    }

    /**
     * An empty NetBuffer holds no content when reading.
     *
     * @return true if the underlying buffer is empty for any future read.
     */
    @Override
    public boolean isEmpty() {
        if (mState == State.READ) {
            return !(mBuf.hasRemaining());
        }

        // Imitate a flipRead().
        int pos = mReadStartPos;
        int limit = mBuf.position();
        return (pos >= limit);
    }

    /**
     * @return true if the buffer is full with respect to future appends
     */
    public boolean isFull() {
        int pos = mBuf.position();
        int limit = mBuf.limit();

        if (mState == State.READ) {
            // Imitate flipAppend().
            pos = mBuf.limit();
            limit = mBuf.capacity();
        }
        return (pos == limit);
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

        if (mState == State.APPEND) {
            mBuf.flip();
            mBuf.position(mReadStartPos);

            newBuf.put(mBuf);

            // newBuf is now positioned for future appends.
        } else {
            // Otherwise, READ.  We are already positioned to copy unread data.
            newBuf.put(mBuf);

            // Adjust newBuf's pointers for future reads of the newly copied data.
            newBuf.flip();
        }

        mBuf = newBuf;
        mReadStartPos = 0;
    }

    /**
     * Resizes the underlying ByteBuffer to the given size.
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
        mReadStartPos = 0;

        if (mState == State.READ) {
            // The newly resized buffer is empty.
            mBuf.limit(0);
        }
    }

    /**
     * Sets the read position of the buffer to the last unread position based on the last call to
     * updateRead().  This only takes effect if the NetBuffer is in read mode.
     */
    public void rewindRead() {
        if (mState != State.READ) {
            return;
        }

        mBuf.position(mReadStartPos);
    }

    /**
     * Marks the buffer for appending.  It will not be flipped.
     *
     * This is useful for adjusting a buffer that is automatically flipped by a connection
     * callback.
     */
    public void setAppend() {
        mState = State.APPEND;
    }

    /**
     * Marks the buffer for reading.  It will neither be flipped nor rewound.
     *
     * This is useful for adjusting a buffer that is automatically flipped by a connection
     * callback.
     */
    public void setRead() {
        mState = State.READ;
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
     * @throws SSLException
     */
    @Override
    public SSLEngineResult unwrap(SSLEngine engine, NetBuffer dest) throws SSLException {
        flipRead();
        dest.flipAppend();

        return engine.unwrap(mBuf, dest.mBuf);
    }

    /**
     * Unsafe variant of unwrap that does not attempt to flip this or dest.
     *
     * @param engine
     * @param dest
     * @throws SSLException
     */
    public SSLEngineResult unwrapUnsafe(SSLEngine engine, NetBuffer dest) throws SSLException {
        return engine.unwrap(mBuf, dest.mBuf);
    }

    /**
     * Updates read position pointers based on the underlying ByteBuffer's state.
     * This must be called after reading directly from the underyling ByteBuffer to mark the
     * starting position of unread data.
     *
     * It only takes effect if the NetBuffer is in a read state.
     */
    @Override
    public void updateRead() {
        if (mState != State.READ) {
            return;
        }

        mReadStartPos = mBuf.position();
    }

    /**
     * Wraps the contents of this into dest.  "this" will be prepared
     * for reading, and dest will be prepared for appending.  See wrapUnsafe()
     * if this check is not needed.
     *
     * @param engine
     * @param dest
     * @throws SSLException
     */
    @Override
    public SSLEngineResult wrap(SSLEngine engine, NetBuffer dest) throws SSLException {
        flipRead();
        dest.flipAppend();

        return engine.wrap(mBuf, dest.getByteBuffer());
    }

    /**
     * Variant of wrap that doesn't prepare "this" or dest.
     *
     * @param engine
     * @param dest
     * @throws SSLException
     */
    public SSLEngineResult wrapUnsafe(SSLEngine engine, NetBuffer dest) throws SSLException {
        return engine.wrap(mBuf, dest.getByteBuffer());
    }
}
