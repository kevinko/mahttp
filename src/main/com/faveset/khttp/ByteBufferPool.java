// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * A ByteBufferPool manages a collection of ByteBuffers by dynamically
 * allocating them during a series string and buffer writes.
 */
class ByteBufferPool {
    private boolean mIsDirect;

    private int mBufSize;
    private List<ByteBuffer> mBufs;

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
        mBufs = new ArrayList<ByteBuffer>();
    }

    private ByteBuffer allocate() {
        if (mIsDirect) {
            return ByteBuffer.allocateDirect(mBufSize);
        }
        return ByteBuffer.allocate(mBufSize);
    }

    /**
     * Closes the pool to future writes and returns an array for consumption
     * by a Channel.  The pool will then be reset.
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
        if (mCurrBuf != null) {
            mCurrBuf.flip();
            mCurrBuf = null;
        }

        mBufs.add(buf.duplicate());
    }

    /**
     * Writes s to the pool, allocating a new internal ByteBuffer if necessary.
     */
    public void writeString(String s) {
        if (s.length() == 0) {
            return;
        }

        // s is always non-empty at this point, so we will need a buffer.
        if (mCurrBuf == null) {
            mCurrBuf = allocate();
            mBufs.add(mCurrBuf);
        }

        // offset into s.
        int offset = 0;
        int sLen = s.length();
        do {
            // sLen > 0 at this point.
            int remLen = mCurrBuf.remaining();
            if (remLen == 0) {
                if (mCurrBuf != null) {
                    mCurrBuf.flip();
                }

                mCurrBuf = allocate();
                mBufs.add(mCurrBuf);

                remLen = mCurrBuf.remaining();
            }

            if (sLen <= remLen) {
                // The remainder of s fits the current buffer.
                String writeStr = s.substring(offset);
                Strings.write(writeStr, mCurrBuf);
                break;
            }

            // Otherwise, write what we can (remLen bytes).
            int endIndex = offset + remLen;
            String writeStr = s.substring(offset, endIndex);
            Strings.write(writeStr, mCurrBuf);

            offset = endIndex;
            sLen -= remLen;
        } while (sLen > 0);
    }
}
