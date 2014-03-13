// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

class NullByteBufferPool extends NullPool<ByteBuffer> {
    // Size of each ByteBuffer to allocate.
    private int mBufSize;

    private boolean mIsDirect;

    /**
     * @param bufSize the size of each buffer to allocate in bytes.
     * @param isDirect true if direct ByteBuffers are desired.
     */
    public NullByteBufferPool(int bufSize, boolean isDirect) {
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
