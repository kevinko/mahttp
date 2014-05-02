// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

abstract class SSLBaseState implements SSLState {
    /**
     * @param buf the overflowed buffer
     * @param newBufSize the new buffer size that is needed by the SSLEngine.
     *
     * @return false if the buffer was not empty and should be drained.
     * Otherwise, the buffer was resized (or cleared) and any operations
     * should be retried.
     */
    protected static boolean resizeOverflowedBuffer(NetBuffer buf, int newBufSize) {
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
            throw RuntimeException("expected buffer resize but not possible");
        }

        buf.resizeUnsafe(newBufSize);

        return true;
    }

    public abstract OpResult stepUnwrap(NetReader src, NetBuffer dest);

    public abstract OpResult stepWrap(NetReader src, NetBuffer dest);
}
