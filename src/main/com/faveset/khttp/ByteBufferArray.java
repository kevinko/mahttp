// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

class ByteBufferArray {
    private ByteBuffer[] mBufs;

    // Points to the index of the first non-empty buffer in the sequence.
    private int mNonEmptyOffset;

    private int mRemaining;

    public ByteBufferArray(ByteBuffer[] bufs) {
        mBufs = bufs;

        update();
    }

    public ByteBuffer[] getBuffers() {
        return mBufs;
    }

    /**
     * @return the offset of the first non-empty buffer since the last update().
     */
    public int getNonEmptyOffset() {
        return mNonEmptyOffset;
    }

    /**
     * @return the number of remaining bytes in the buffer array since the last
     * update().
     */
    public int remaining() {
        return mRemaining;
    }

    /**
     * This must be called whenever new data is added to the buffer array.
     */
    public void reset() {
        mNonEmptyOffset = 0;

        update();
    }

    /**
     * This should be called whenever the underlying ByteBuffer array is
     * drained (perhaps partially) in a sequential manner.
     */
    public void update() {
        int totalRem = 0;

        ByteBuffer[] bufs = mBufs;

        final int len = bufs.length;
        for (int ii = mNonEmptyOffset; ii < len; ii++) {
            int rem = bufs[ii].remaining();
            if (totalRem == 0 && rem != 0) {
                mNonEmptyOffset = ii;
            }
            totalRem += rem;
        }

        mRemaining = totalRem;
    }
}
