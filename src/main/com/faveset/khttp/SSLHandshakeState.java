// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.khttp;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

class SSLHandshakeState extends SSLBaseState {
    private SSLEngine mSSLEngine;

    /**
     * @param factory the factory for allocating new ByteBuffers (when resizing)
     * @param engine
     */
    public SSLHandshakeState(ByteBufferFactory factory, SSLEngine engine) {
        super(factory);

        mSSLEngine = engine;
    }

    /**
     * @param status
     *
     * @return NONE if no primary operation occurs because of status
     */
    private OpResult handleUnwrapHandshakeStatus(SSLEngineResult.HandshakeStatus status) {
        switch (status) {
            case NEED_TASK:
                return OpResult.SCHEDULE_TASKS;

            case NEED_UNWRAP:
                break;

            case NEED_WRAP:
                return OpResult.SCHEDULE_WRAP;

            case FINISHED:
            case NOT_HANDSHAKING:
                return OpResult.STATE_CHANGE;
        }

        return OpResult.NONE;
    }

    @Override
    public OpResult stepUnwrap(NetBuffer src, NetBuffer dest) throws SSLException {
        do {
            SSLEngineResult result = src.unwrap(mSSLEngine, dest);
            SSLEngineResult.HandshakeStatus handshakeStatus = result.getHandshakeStatus();

            switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    // When handshaking, no application data is unwrapped.
                    // Thus, this should never happen.  Instead, this would indicate some sort
                    // of change in state.  Break out to the subsequent switch to handle things.
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

                    OpResult handshakeResult = handleUnwrapHandshakeStatus(handshakeStatus);
                    if (handshakeResult != OpResult.NONE) {
                        // Give precedence to any handshake ops while waiting for the upcoming
                        // network read.
                        return handshakeResult;
                    }

                    return OpResult.UNWRAP_LOAD_SRC_BUFFER;

                case CLOSED:
                    return OpResult.ENGINE_CLOSE;

                case OK:
                    src.updateRead();

                    // Be greedy, and continue unwrapping.
                    break;
            }

            OpResult handshakeResult = handleUnwrapHandshakeStatus(handshakeStatus);
            if (handshakeResult != OpResult.NONE) {
                return handshakeResult;
            }
        } while (true);
    }

    @Override
    public OpResult stepWrap(NetReader src, NetBuffer dest) throws SSLException {
        do {
            SSLEngineResult result = src.wrap(mSSLEngine, dest);
            switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    // NOTE: This can also be triggered by the SSLEngine when it wants to flush
                    // all outgoing handshake data (before FINISHED).  Curiously, this does not
                    // happen when a NEED_UNWRAP occurs.

                    // dest is an outgoing network buffer; hence, use packet size.
                    int newBufSize = mSSLEngine.getSession().getPacketBufferSize();
                    if (!resizeAppendBuffer(dest, newBufSize)) {
                        // dest was not empty.
                        return OpResult.DRAIN_DEST_BUFFER;
                    }

                    // Otherwise, dest was resized.  Continue looping to retry.
                    break;

                case BUFFER_UNDERFLOW:
                    // This shouldn't happen when handshaking unless we've dropped out of the
                    // handshake state, because handshaking does not wrap application data.  Let
                    // the subsequent handshake switch() handle things.
                    break;

                case CLOSED:
                    // Drain any residual handshaking data.
                    if (!dest.isEmpty()) {
                        return OpResult.DRAIN_DEST_BUFFER;
                    }

                    return OpResult.ENGINE_CLOSE;

                case OK:
                    // We might have changed out of the handshaking state so just note the read.
                    // This is vacuous (though it consumes some ops) if we are still handshaking.
                    src.updateRead();

                    // Be greedy, and continue wrapping.
                    break;
            }

            switch (result.getHandshakeStatus()) {
                case NEED_TASK:
                    return OpResult.SCHEDULE_TASKS;

                case NEED_UNWRAP:
                    // Flush any wrapped data before waiting for a response.  This ensures progress.
                    // Otherwise, it is possible for both the SSLEngine and the peer to be waiting.
                    // In such a case, unwrapping will continue after send completion.
                    if (!dest.isEmpty()) {
                        return OpResult.DRAIN_DEST_BUFFER;
                    }

                    return OpResult.SCHEDULE_UNWRAP;

                case NEED_WRAP:
                    // We're already wrapping.
                    break;

                case FINISHED:
                case NOT_HANDSHAKING:
                    return OpResult.STATE_CHANGE;
            }
        } while (true);
    }
}
