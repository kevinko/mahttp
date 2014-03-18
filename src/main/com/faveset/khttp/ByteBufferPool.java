// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

class ByteBufferPool extends Pool<ByteBuffer> {
    private static final int sDefaultMaxCount = 128;

    // Size of each ByteBuffer to allocate.
    private int mBufSize;

    private boolean mIsDirect;

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
        super(maxCount);

        mBufSize = bufSize;
        mIsDirect = isDirect;
    }

    @Override
    protected ByteBuffer allocateValue() {
        if (mIsDirect) {
            return ByteBuffer.allocateDirect(mBufSize);
        }
        return ByteBuffer.allocate(mBufSize);
    }
}
