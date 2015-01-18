// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.nio.ByteBuffer;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * A ByteBufferArrayBuilder manages a collection of ByteBuffers by dynamically
 * allocating them during a series of string and buffer writes.
 *
 * All String contents will be converted to UTF-8 format, which is common
 * for HTML.
 */
class ByteBufferArrayBuilder {
    /**
     * An Inserter points to a specific place in the ByteBufferArrayBuilder and
     * lets one insert a new sequence of data after the pointer.  All write
     * methods are cumulative and will append to the new sequence of data
     * at the insertion point.
     */
    class Inserter {
        private ListIterator<ByteBuffer> mIter;
        private ByteBuffer mRemainingBuf;

        private Inserter(ListIterator<ByteBuffer> iter) {
            mIter = iter;
        }

        private Inserter(ListIterator<ByteBuffer> iter, ByteBuffer remainingBuf) {
            mIter = iter;
            mRemainingBuf = remainingBuf;
        }

        /**
         * This must be called when done writing to the Inserter to finalize
         * insertion operations.
         */
        public void close() {
            if (mRemainingBuf != null) {
                mRemainingBuf.flip();

                // Update the byte counter.
                mRemaining += mRemainingBuf.remaining();

                mRemainingBuf = null;
            }
        }

        /**
         * Adds buf to the new sequence starting at the Inserter.
         *
         * One must call close() to finalize all operations.
         */
        public void writeBuffer(ByteBuffer buf) {
            mRemainingBuf = ByteBufferArrayBuilder.this.insertBuffer(mIter, mRemainingBuf, buf);
        }

        /**
         * Adds s to the new sequence starting at the Inserter.
         *
         * One must call close() to finalize all operations.
         *
         * s will be converted to UTF-8 when written to the ByteBuffer.
         */
        public void writeString(String s) {
            mRemainingBuf = ByteBufferArrayBuilder.this.insertString(mIter, mRemainingBuf, s);
        }
    }

    private Pool<ByteBuffer> mByteBufferPool;
    // This tracks each PoolEntry so that we can release the ByteBuffer
    // back to the pool.
    private List<PoolEntry<ByteBuffer>> mPoolEntries;

    private List<ByteBuffer> mBufs;

    // This tracks all remaining counts except for mCurrBuf (i.e., mBufs).
    private long mRemaining;

    // null when a new buffer should be added to the list.  Otherwise,
    // it points to the current buffer that is being used for writing at the
    // tail of the list.
    private ByteBuffer mCurrBuf;

    /**
     * The caller must close() the returned ByteBufferArrayBuilder when no
     * longer needed.
     *
     * @param bufSize size of each ByteBuffer to allocate
     * @param isDirect true if direct buffers should be used
     */
    public ByteBufferArrayBuilder(int bufSize, boolean isDirect) {
        this(new NullByteBufferPool(bufSize, isDirect));
    }

    /**
     * Creates a ByteBufferArrayBuilder that uses the given ByteBuffer pool.
     *
     * The caller must close() the returned ByteBufferArrayBuilder when no
     * longer needed.
     */
    public ByteBufferArrayBuilder(Pool<ByteBuffer> pool) {
        mByteBufferPool = pool;
        mPoolEntries = new ArrayList<PoolEntry<ByteBuffer>>();

        mBufs = new LinkedList<ByteBuffer>();
    }

    private ByteBuffer allocate() {
        PoolEntry<ByteBuffer> entry = mByteBufferPool.allocate();
        mPoolEntries.add(entry);
        return entry.get();
    }

    /**
     * Returns an array for consumption by a Channel.  The returned buffers
     * will have total remaining bytes of remaining().
     *
     * After build(), the builder state will be indeterminate.  One must call
     * clear() or close() to clear the state and also to free up resources
     * before discarding or reusing the builder.
     */
    public ByteBuffer[] build() {
        if (mCurrBuf != null) {
            mCurrBuf.flip();
        }

        ByteBuffer[] result = mBufs.toArray(new ByteBuffer[0]);

        return result;
    }

    /**
     * Resets the builder to the initial state so that it can be reused.
     *
     * This must be called to release resources.  It may be used
     * interchangeably with close().
     */
    public void clear() {
        for (PoolEntry<ByteBuffer> entry : mPoolEntries) {
            mByteBufferPool.release(entry);
        }
        mPoolEntries.clear();

        mCurrBuf = null;
        mBufs.clear();
        mRemaining = 0;
    }

    /**
     * The caller must call this to release all (pooled) resources.
     *
     * The builder will be cleared and can be reused.  It may be used
     * interchangeably with clear().
     */
    public void close() {
        clear();
    }

    /**
     * The caller must close() the Inserter when done before calling any other
     * write method, even if the Inserter is not used (e.g., with try-finally).
     * This allows the inserter to continue from a prior remainder without
     * wasting buffer space.
     *
     * @return an Inserter for inserting data at the back of the list.
     */
    public Inserter insertBack() {
        Inserter inserter = new Inserter(mBufs.listIterator(mBufs.size()), mCurrBuf);
        mCurrBuf = null;
        return inserter;
    }

    /**
     * @param iter
     * @param currBuf the remaining ByteBuffer with which to continue
     * appending data to.  It will be committed to the total byte count as
     * necessary.  One may pass null if no remainder buffer currently exists.
     * @param buf will be added after iter.
     *
     * @return the remainder buffer after adding all of s to the insertion
     * point or null if no partially filled buffer remains.  If non-null, the
     * buffer will already be inserted and will be guaranteed to have
     * free space for future writes.
     */
    private ByteBuffer insertBuffer(ListIterator<ByteBuffer> iter,
            ByteBuffer currBuf, ByteBuffer buf) {
        if (buf.remaining() == 0) {
            // We have no work.
            return currBuf;
        }

        if (currBuf != null) {
            // Commit the remainder buf.
            currBuf.flip();
            mRemaining += currBuf.remaining();
            currBuf = null;
        }

        // Otherwise, we're not at the end of the list.  We don't need to
        // modify currBuf, since we're not touching it.
        iter.add(buf.duplicate());

        // Commit the count, since it's added as a whole.
        mRemaining += buf.remaining();

        return currBuf;
    }

    /**
     * The Inserter is invalidated once the ByteBufferArrayBuilder is modified
     * by the builder's writeBuffer(), writeString() methods.
     *
     * The caller should take care to close() the Inserter when done.
     *
     * @return an Inserter for inserting data at the front of the list.
     */
    public Inserter insertFront() {
        return new Inserter(mBufs.listIterator());
    }

    /**
     * @param insertIter the insertion point.  Buffers will be added to
     * the iterator.
     * @param currBuf the ByteBuffer with which to continue appending data to.
     * It will be added to the insertion point when it becomes full.  One may
     * pass null to have a new ByteBuffer allocated internally.
     * @param s the String to insert.
     * @return the remainder buffer after adding all of s to the insertion
     * point or null if no partially filled buffer remains.  If non-null, the
     * buffer will already be inserted and will be guaranteed to have
     * free space for future writes.
     */
    private ByteBuffer insertString(ListIterator<ByteBuffer> insertIter,
            ByteBuffer currBuf, String s) {
        if (s.length() == 0) {
            return currBuf;
        }

        // s is always non-empty at this point, so we will need a buffer.
        if (currBuf == null) {
            currBuf = allocate();
            insertIter.add(currBuf);
        }

        // offset into s.
        int offset = 0;
        int sLen = s.length();
        do {
            // sLen > 0 at this point.
            int remLen = currBuf.remaining();
            if (remLen == 0) {
                // We need a new buffer.  Commit currBuf to the insertion point.
                currBuf.flip();
                mRemaining += currBuf.remaining();

                // Ready a new currBuf.
                currBuf = allocate();
                insertIter.add(currBuf);

                remLen = currBuf.remaining();
            }

            if (sLen <= remLen) {
                // The remainder of s fits the current buffer.
                String writeStr = s.substring(offset);
                Strings.writeUTF8(writeStr, currBuf);

                if (sLen == remLen) {
                    // Commit the buffer, since it is full.
                    currBuf.flip();
                    mRemaining += currBuf.remaining();
                    currBuf = null;
                }

                break;
            }

            // Otherwise, write what we can (remLen bytes).
            int endIndex = offset + remLen;
            String writeStr = s.substring(offset, endIndex);
            Strings.writeUTF8(writeStr, currBuf);

            offset = endIndex;
            sLen -= remLen;
        } while (sLen > 0);

        return currBuf;
    }

    /**
     * Returns the total number of bytes remaining for all buffers within the
     * builder.
     */
    public long remaining() {
        long count = mRemaining;
        if (mCurrBuf != null) {
            // Factor in mCurrBuf.  mCurrBufs are always allocated within this
            // class and increase from position 0.
            count += mCurrBuf.position();
        }
        return count;
    }

    /**
     * Adds buf to the list of buffers to write.  This will finalize the
     * current buffer (if not null) to preserve ordering.  Thus, some memory
     * will be wasted if a subsequent writeString() occurs, because a new
     * buffer would be allocated.
     *
     * @param buf must be positioned for subsequent reads.
     */
    public void writeBuffer(ByteBuffer buf) {
        mCurrBuf = insertBuffer(mBufs.listIterator(mBufs.size()), mCurrBuf, buf);
    }

    /**
     * Writes s to the builder, allocating a new internal ByteBuffer if
     * necessary.
     *
     * s will be converted to UTF-8 when written to the ByteBuffer.
     */
    public void writeString(String s) {
        // Just add to the tail.
        mCurrBuf = insertString(mBufs.listIterator(mBufs.size()), mCurrBuf, s);
    }
}
