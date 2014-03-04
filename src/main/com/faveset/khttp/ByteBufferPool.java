// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * A ByteBufferPool manages a collection of ByteBuffers by dynamically
 * allocating them during a series string and buffer writes.
 */
class ByteBufferPool {
    /**
     * An Inserter points to a specific place in the ByteBufferPool and
     * lets one insert a new sequence of data after the pointer.  All write
     * methods are cumulative and will append to the new sequence of data
     * at the insertion point.
     */
    class Inserter {
        private ListIterator<ByteBuffer> mIter;

        private Inserter(ListIterator<ByteBuffer> iter) {
            mIter = iter;
        }

        /**
         * Adds buf to the new sequence starting at the Inserter.
         */
        public void writeBuffer(ByteBuffer buf) {
            ByteBufferPool.this.insertBuffer(mIter, buf);
        }

        /**
         * Adds s to the new sequence starting at the Inserter.
         */
        public void writeString(String s) {
            ByteBufferPool.this.insertString(mIter, s);
        }
    }

    private boolean mIsDirect;

    private int mBufSize;
    private List<ByteBuffer> mBufs;

    // This tracks all remaining counts except for mCurrBuf (i.e., mBufs).
    private long mRemaining;

    // null when a new buffer should be added to the list.  Otherwise,
    // it points to the current buffer that is being used for writing at the
    // tail of the list.
    private ByteBuffer mCurrBuf;

    /**
     * @param bufSize the size of each ByteBuffer to allocate.
     * @param isDirect true if a direct ByteBuffer should be allocated.
     */
    public ByteBufferPool(int bufSize, boolean isDirect) {
        mIsDirect = isDirect;
        mBufSize = bufSize;
        mBufs = new LinkedList<ByteBuffer>();
    }

    private ByteBuffer allocate() {
        return allocate(mBufSize);
    }

    /**
     * @param size in bytes for the new ByteBuffer
     */
    private ByteBuffer allocate(int size) {
        if (mIsDirect) {
            return ByteBuffer.allocateDirect(size);
        }
        return ByteBuffer.allocate(size);
    }

    /**
     * Closes the pool to future writes and returns an array for consumption
     * by a Channel.  The returned buffers will have total remaining bytes
     * of remaining().  After build(), the pool will be reset (remaining() will
     * be cleared).
     */
    public ByteBuffer[] build() {
        if (mCurrBuf != null) {
            mCurrBuf.flip();
        }

        ByteBuffer[] result = mBufs.toArray(new ByteBuffer[0]);

        clear();

        return result;
    }

    /**
     * Resets the pool to the initial state.
     */
    public void clear() {
        mCurrBuf = null;
        mBufs.clear();
        mRemaining = 0;
    }

    /**
     * @param iter
     * @param buf will be added after iter.
     */
    private void insertBuffer(ListIterator<ByteBuffer> iter, ByteBuffer buf) {
        if (iter.nextIndex() == mBufs.size()) {
            // This is the tail.
            writeBuffer(buf);
            return;
        }

        if (buf.remaining() == 0) {
            return;
        }

        // Otherwise, we're not at the end of the list.  We don't need to
        // modify mCurrBuf, since we're not touching it.
        iter.add(buf.duplicate());

        // Commit the count, since it's added as a whole.
        mRemaining += buf.remaining();
    }

    private void insertString(ListIterator<ByteBuffer> iter, String s) {
        if (iter.nextIndex() == mBufs.size()) {
            // This is the tail.
            writeString(s);
            return;
        }

        ByteBuffer remBuf = insertString(iter, null, s);

        if (remBuf != null) {
            // Always commit the remainder, since we are inserting mid-list.
            remBuf.flip();
            mRemaining += remBuf.remaining();
        }
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
                Strings.write(writeStr, currBuf);

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
            Strings.write(writeStr, currBuf);

            offset = endIndex;
            sLen -= remLen;
        } while (sLen > 0);

        return currBuf;
    }

    /**
     * The Inserter is invalidated once the ByteBufferPool is modified
     * by the pool's writeBuffer(), writeString() methods.
     *
     * @return an Inserter for inserting data at the front of the list.
     */
    public Inserter insertFront() {
        return new Inserter(mBufs.listIterator());
    }

    /**
     * Returns the total number of bytes remaining for all buffers within the
     * pool.
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
        if (buf.remaining() == 0) {
            return;
        }

        if (mCurrBuf != null) {
            mCurrBuf.flip();

            mRemaining += mCurrBuf.remaining();

            mCurrBuf = null;
        }

        mBufs.add(buf.duplicate());

        // Update the count, since buf is added as a whole.
        mRemaining += buf.remaining();
    }

    /**
     * Writes s to the pool, allocating a new internal ByteBuffer if necessary.
     */
    public void writeString(String s) {
        // Just add to the tail.
        mCurrBuf = insertString(mBufs.listIterator(mBufs.size()), mCurrBuf, s);
    }
}
