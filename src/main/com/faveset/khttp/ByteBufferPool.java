// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

class ByteBufferPool {
    /**
     * An Entry maintains a reference to a ByteBuffer and is
     * uniquely hashable.  Unlike with a ByteBuffer, its hashCode is not
     * content-dependent.
     */
    static class Entry {
        private int mTag;

        private ByteBuffer mBuf;

        private Entry(int tag, ByteBuffer buf) {
            mTag = tag;
            mBuf = buf;
        }

        public ByteBuffer getByteBuffer() {
            return mBuf;
        }

        @Override
        public int hashCode() {
            return mTag;
        }
    }

    private static final int sDefaultMaxCount = 128;

    // Size of each ByteBuffer to allocate.
    private int mBufSize;

    private boolean mIsDirect;

    // Used for generating unique tags for each entry's hashCode.
    // Since Java is a GCed language, we need not worry about wraparound.
    // If a duplication occurs, the buffer Set will only hold one
    // of the entries.  The other will be released and treated as a normal
    // reference, which will cause a negligible amount of GC churn.
    private int mTagCount;

    // The maximum number of buffers to maintain.
    private int mMaxCount;

    // Tracks all free buffers.  A free buffer is an allocated and then released
    // buffer.
    private Set<Entry> mFreeBuffers;

    /**
     * Defaults will be chosen for the min and max count.
     *
     * @param bufSize the size of each buffer to allocate in bytes.
     * @param isDirect true if direct ByteBuffers are desired.
     */
    public ByteBufferPool(int bufSize, boolean isDirect) {
        this(bufSize, isDirect, sDefaultMaxCount);
    }

    /**
     * @param bufSize the size of each buffer to allocate in bytes.
     * @param isDirect true if direct ByteBuffers are desired.
     * @param maxCount the maximum number of buffers to maintain before letting
     * the GC take over.
     */
    public ByteBufferPool(int bufSize, boolean isDirect, int maxCount) {
        mBufSize = bufSize;
        mIsDirect = isDirect;

        mMaxCount = maxCount;

        mFreeBuffers = new HashSet<Entry>(mMaxCount);
    }

    public Entry allocate() {
        Entry entry;
        if (mFreeBuffers.size() == 0) {
            // Allocate a new buffer.
            ByteBuffer buf;
            if (mIsDirect) {
                buf = ByteBuffer.allocateDirect(mBufSize);
            } else {
                buf = ByteBuffer.allocate(mBufSize);
            }

            entry = new Entry(mTagCount, buf);
            mTagCount++;
        } else {
            Iterator<Entry> iter = mFreeBuffers.iterator();
            entry = iter.next();
            iter.remove();
        }

        return entry;
    }

    // Used for testing.
    int getFreeBufferCount() {
        return mFreeBuffers.size();
    }

    /**
     * This returns null so that one can clear the reference to entry when
     * releasing:
     *
     *   entry = pool.release(entry);
     *
     * @return null
     */
    public Entry release(Entry entry) {
        if (mFreeBuffers.size() < mMaxCount) {
            // Keep the buffer so that we can reach the desired thresholds.
            mFreeBuffers.add(entry);
        }
        // At this point, mFreeBuffers.size() <= mMaxCount.

        return null;
    }
}
