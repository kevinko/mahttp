// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.khttp;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

/**
 * This provides methods for actively sending and receiving data (not handshaking).
 */
class SSLActiveState extends SSLBaseState {
    private SSLEngine mSSLEngine;

    /**
     * @param factory the factory for allocating new ByteBuffers (when resizing)
     * @param engine
     */
    public SSLActiveState(ByteBufferFactory factory, SSLEngine engine) {
        super(factory);

        mSSLEngine = engine;
    }

    /**
     * @param src
     * @param dest
     *
     * @return true if an SSLEngine state change (i.e., handshaking)
     * is needed.  false if no state change but a callback was scheduled
     * on conn (possibly connection close).
     */
    @Override
    public OpResult stepUnwrap(NetBuffer src, NetBuffer dest) throws SSLException {
        do {
            SSLEngineResult result = src.unwrap(mSSLEngine, dest);
            switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    // dest is the (unwrapped) application buffer.
                    int newBufSize = mSSLEngine.getSession().getApplicationBufferSize();
                    if (!resizeAppendBuffer(dest, newBufSize)) {
                        // dest was not empty.  Drain it first.
                        return OpResult.DRAIN_DEST_BUFFER;
                    }

                    // Otherwise, the buffer was resized.  Continue looping
                    // to retry.
                    break;

                case BUFFER_UNDERFLOW:
                    // Try to append as much as possible to the source buffer before resizing and
                    // compacting.
                    if (src.isFull()) {
                        // Try compacting first to make room.
                        int netBufSize = mSSLEngine.getSession().getPacketBufferSize();
                        if (!resizeOrCompactSourceBuffer(src, netBufSize)) {
                            // src is already appropriately sized and compacted.
                            throw new RuntimeException(
                                    "buffer is correctly sized and compacted.  This should not happen.");
                        }
                        // Otherwise, we've resized and can load more data.
                    }

                    // Try loading more data into src (the network buffer) to unwrap.
                    if (!dest.isEmpty()) {
                        // First, drain what we have to the app layer, since network ops will take
                        // time.
                        return OpResult.DRAIN_DEST_BUFFER;
                    }

                    if (result.getHandshakeStatus() !=
                            SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                        // We might now be handshaking, so react accordingly.
                        break;
                    }

                    return OpResult.UNWRAP_LOAD_SRC_BUFFER;

                case CLOSED:
                    // Let the handshaking state deal with closure if requested, since the SSLEngine
                    // generates handshaking messages on graceful closure.  Otherwise, fall-back
                    // to a direct close.
                    if (result.getHandshakeStatus() !=
                            SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                        break;
                    }
                    return OpResult.ENGINE_CLOSE;

                case OK:
                    src.updateRead();

                    // We'll keep reading as much as possible before draining
                    // the destination buffer.
                    break;
            }

            if (result.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                if (!dest.isEmpty()) {
                    // First, drain what we have to the app layer, since SSLHandshakeState currently
                    // assumes that BUFFER_OVERFLOW (and thus DRAIN_DEST_BUFFER) cannot happen.
                    //
                    // TODO: you could update that assumption and eliminate this case.
                    return OpResult.DRAIN_DEST_BUFFER;
                }

                return OpResult.STATE_CHANGE;
            }
        } while (true);
    }

    /**
     * As a general protocol, we return DRAIN_DEST_BUFFER whenever we can wrap no further.  This
     * ensures that all wrapped data is flushed.  We assume that the caller will handle the case
     * when dest is empty after wrapping.
     */
    @Override
    public OpResult stepWrap(NetReader src, NetBuffer dest) throws SSLException {
        do {
            SSLEngineResult result = src.wrap(mSSLEngine, dest);
            switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    // dest is destined for the network, hence packet buffer.
                    int newBufSize = mSSLEngine.getSession().getPacketBufferSize();
                    if (!resizeAppendBuffer(dest, newBufSize)) {
                        // dest was not empty.
                        return OpResult.DRAIN_DEST_BUFFER;
                    }
                    // Otherwise, the dest buffer was resized.  Continue
                    // looping to retry.
                    break;

                case BUFFER_UNDERFLOW:
                    // We're out of src data to wrap, so we're done wrapping for now.  Since this
                    // is application data, trigger any send callbacks as soon as possible, hence
                    // drain the dest buffer.
                    //
                    // NOTE: this does not seem to be triggered by wrap, but handle it sanely just
                    // in case.
                    return OpResult.DRAIN_DEST_BUFFER;

                case CLOSED:
                    // Let the handshaking state deal with closure if requested.  Otherwise,
                    // fall-back to a direct close.
                    if (result.getHandshakeStatus() !=
                            SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                        break;
                    }
                    return OpResult.ENGINE_CLOSE;

                case OK:
                    src.updateRead();

                    // See if we simply do not have any more data to unwrap, in which case we
                    // should pass the unwrapped result to the application before continuing.
                    if (src.isEmpty()) {
                        // This also handles the case when the dest is empty.
                        return OpResult.DRAIN_DEST_BUFFER;
                    }

                    // Else, keep trying to wrap as much as possible.
                    break;
            }

            if (result.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                return OpResult.STATE_CHANGE;
            }
        } while (true);
    }
}
