// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.khttp;

import javax.net.ssl.SSLException;

abstract class SSLBaseState implements SSLState {
    private ByteBufferFactory mFactory;

    /**
     * @param factory the factory for allocating new ByteBuffers.
     */
    protected SSLBaseState(ByteBufferFactory factory) {
        mFactory = factory;
    }

    /**
     * @param buf the overflowed append buffer
     * @param newBufSize the new buffer size that is needed by the SSLEngine.
     *
     * @return false if the buffer was not empty and should be drained.
     * Otherwise, the buffer was resized (or cleared) and any operations
     * should be retried.
     */
    protected boolean resizeAppendBuffer(NetBuffer buf, int newBufSize) {
        if (!buf.isEmpty()) {
            return false;
        }

        if (!buf.isCleared()) {
            buf.clear();

            // Retry now that more space is available.
            return true;
        }

        // At this point, the buffer is empty and cleared.
        // A resize must be necessary.
        if (!buf.needsResize(newBufSize)) {
            throw new RuntimeException("expected buffer resize but not possible");
        }

        buf.resizeUnsafe(mFactory, newBufSize);

        return true;
    }

    /**
     * Resizes or compacts the given source NetBuffer that is normally loaded with data to be
     * unwrapped.
     *
     * @param buf
     * @param newBufSize the buffer size required by the SSLEngine.
     *
     * @return true if buf was resized or compacted as a result of this method.  In this case, the caller
     * should try loading more data into the buffer.  Otherwise, returns false if the buf is
     * already resized and compacted.  In that case, the caller should typically try loading more data into
     * the buffer; a check should also be performed to see if the buffer is already full, which is a runtime
     * error that should never happen.
     */
    protected boolean resizeOrCompactSourceBuffer(NetBuffer buf, int newBufSize) {
        if (buf.needsResize(newBufSize)) {
            // The resize operation also compacts data.
            buf.resize(mFactory, newBufSize);
            return true;
        }

        // Otherwise, compact the buffer.
        if (buf.isCompacted()) {
            return false;
        }

        buf.compact();
        return true;
    }

    public abstract OpResult stepUnwrap(NetBuffer src, NetBuffer dest) throws SSLException;

    public abstract OpResult stepWrap(NetReader src, NetBuffer dest) throws SSLException;
}
