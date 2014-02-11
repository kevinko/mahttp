// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

/**
 * Note that all implementations may be initialized statically and thus
 * should be stateless.
 */
interface StateHandler {
    /**
     * Performs a step of the state machine.
     *
     * @return true if the state is complete.  False if more data is needed
     * in buf to proceed.
     */
    boolean handleState(NonBlockingConnection conn, ByteBuffer buf, HandlerState state) throws InvalidRequestException;
}
