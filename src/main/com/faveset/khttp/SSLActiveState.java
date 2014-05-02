// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

/**
 * This provides methods for actively sending and receiving data (not handshaking).
 */
class SSLActiveState {
    private NonBlockingConnection mConn;
    private SSLEngine mSSLEngine;

    public SSLActiveState(NonBlockingConnection conn, SSLEngine engine) {
        mConn = conn;
        mSSLEngine = engine;
    }

    /**
     * @param buf
     * @param newBufSize the new buffer size that is needed by the SSLEngine.
     *
     * @return false if the buffer was not empty and should be drained.
     * Otherwise, the buffer was resized (or cleared) and any operations
     * should be retried.
     */
    private static boolean handleBufferOverflow(NetBuffer buf, int newBufSize) {
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

    /**
     * @param src
     * @param dest
     *
     * @return true if an SSLEngine state change (i.e., handshaking)
     * is needed.  false if no state change but a callback was scheduled
     * on conn (possibly connection close).
     */
    public OpResult stepUnwrap(NetReader src, NetBuffer dest) {
        do {
            SSLEngineResult result = src.unwrap(mSSLEngine, dest);
            switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    // dest is the (unwrapped) application buffer.
                    int newBufSize = mSSLEngine.getSession().getApplicationBufferSize();
                    if (!handleBufferOverflow(dest, newBufSize)) {
                        // dest was not empty.  Drain it first.
                        return OpResult.DRAIN_DEST_BUFFER;
                    }

                    // Otherwise, the buffer was resized.  Continue looping
                    // to retry.
                    break;

                case BUFFER_UNDERFLOW:
                    // We need more data in the (src) network buffer to unwrap.
                    return OpResult.LOAD_SRC_BUFFER;

                case CLOSED:
                    return ENGINE_CLOSE;

                case OK:
                    src.updateRead();

                    // We'll keep reading as much as possible before draining
                    // the destination buffer.
                    break;
            }

            if (result.getHandshakeStatus() != NOT_HANDSHAKING) {
                return OpResult.STATE_CHANGE;
            }
        } while (true);
    }

    public OpResult stepWrap(NetReader src, NetBuffer dest) {
        do {
            SSLEngineResult result = src.wrap(mSSLEngine, dest);
            switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    // dest is destined for the network, hence packet buffer.
                    int newBufSize = mSSLEngine.getSession().getPacketBufferSize();
                    if (!handleBufferOverflow(dest, newBufSize)) {
                        // dest was empty.
                        return OpResult.DRAIN_DEST_BUFFER;
                    }
                    // Otherwise, the dest buffer was resized.  Continue
                    // looping to retry.
                    break;

                case BUFFER_UNDERFLOW:
                    // We're out of src data to wrap, so we're done wrapping
                    // for now.
                    return OpResult.DRAIN_DEST_BUFFER;

                case CLOSED:
                    return OpResult.ENGINE_CLOSE;

                case OK:
                    src.updateRead();

                    // Keep trying to wrap as much as possible.
                    break;
            }

            if (result.getHandshakeStatus() != NOT_HANDSHAKING) {
                return OpResult.STATE_CHANGE;
            }
        } while (true);
    }
}
