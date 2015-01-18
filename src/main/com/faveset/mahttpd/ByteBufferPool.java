// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.nio.ByteBuffer;

class ByteBufferPool extends BasePool<ByteBuffer> {
    private static final int sDefaultMaxCount = 128;

    // This is for the singleton pool.
    private static ByteBufferPool sByteBufferPool;

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

    // Returns a shared singleton direct ByteBuffer pool with
    // Constants.BYTE_BUFFER_POOL_SIZE buffers, each of size
    // Constants.BYTE_BUFFER_SIZE bytes.
    //
    // NOTE: the returned pool is not thread-safe.
    public static synchronized ByteBufferPool get() {
        if (sByteBufferPool == null) {
            sByteBufferPool = new ByteBufferPool(Constants.BYTE_BUFFER_SIZE, true,
                    Constants.BYTE_BUFFER_POOL_SIZE);
        }
        return sByteBufferPool;
    }

    @Override
    protected void resetValue(ByteBuffer v) {
        v.clear();
    }
}
