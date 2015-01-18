// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

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
