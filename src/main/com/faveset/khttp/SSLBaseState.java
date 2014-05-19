// Copyright 2014, Kevin Ko <kevin@faveset.com>

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

    public abstract OpResult stepUnwrap(NetReader src, NetBuffer dest) throws SSLException;

    public abstract OpResult stepWrap(NetReader src, NetBuffer dest) throws SSLException;
}
