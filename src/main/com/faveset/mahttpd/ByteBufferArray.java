// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.nio.ByteBuffer;

class ByteBufferArray {
    private ByteBuffer[] mBufs;

    // Points to the index of the first non-empty buffer in the sequence.
    // It will be mBufs.length if all buffers are empty.
    private int mNonEmptyOffset;

    // Remainder for the first non-empty buffer since the last update.
    private int mNonEmptyRemainder;

    private int mRemaining;

    public ByteBufferArray(ByteBuffer[] bufs) {
        mBufs = bufs;

        updateAll();
    }

    public ByteBuffer[] getBuffers() {
        return mBufs;
    }

    /**
     * @return the number of non-empty ByteBuffers in the array returned by getByteBuffers(),
     * starting from the offset returned by getNonEmptyOffset().
     */
    public int getNonEmptyLength() {
        return (mBufs.length - mNonEmptyOffset);
    }

    /**
     * @return the offset of the first non-empty buffer since the last update().
     * This is equal to the length of the ByteBuffer array if no such buffer
     * exists.
     */
    public int getNonEmptyOffset() {
        return mNonEmptyOffset;
    }

    public boolean hasRemaining() {
        return (mRemaining != 0);
    }

    /**
     * @return the number of ByteBuffers in the underlying array.
     */
    public int length() {
        return mBufs.length;
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

        updateAll();
    }

    /**
     * This should be called whenever the underlying ByteBuffer array is
     * drained (perhaps partially) in a sequential manner.
     */
    public void update() {
        ByteBuffer[] bufs = mBufs;
        final int bufsLen = bufs.length;

        if (mNonEmptyOffset >= bufsLen) {
            // We're done.  No need for updates.
            return;
        }

        // First, check the possibly partial remainder at the non-empty offset.
        int rem = bufs[mNonEmptyOffset].remaining();
        if (rem == mNonEmptyRemainder) {
            // No change.
            return;
        } else if (rem > mNonEmptyRemainder) {
            // Something is wrong, since this cannot happen when draining.
            // Just update everything.
            updateAll();
            return;
        }

        int consumed = mNonEmptyRemainder - rem;

        mNonEmptyRemainder = rem;

        if (rem == 0) {
            // Scan the rest of the buffers for the new non-empty offset.
            int ii;
            for (ii = mNonEmptyOffset + 1; ii < bufsLen; ii++) {
                rem = bufs[ii].remaining();
                if (rem != 0) {
                    // We've reached a stopping point.
                    mNonEmptyRemainder = rem;
                    break;
                }
                // rem == 0.
                consumed += bufs[ii].limit();
            }
            mNonEmptyOffset = ii;
        }

        mRemaining -= consumed;
        return;
    }

    /**
     * Iterates over all buffers to initialize the ByteBufferArray fields.
     */
    private void updateAll() {
        int totalRem = 0;

        ByteBuffer[] bufs = mBufs;

        mNonEmptyOffset = 0;
        mNonEmptyRemainder = 0;

        final int len = bufs.length;
        for (int ii = mNonEmptyOffset; ii < len; ii++) {
            final int rem = bufs[ii].remaining();

            if (totalRem == 0 && rem != 0) {
                mNonEmptyOffset = ii;
                mNonEmptyRemainder = rem;
            }
            totalRem += rem;
        }

        mRemaining = totalRem;
    }
}
