// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

/**
 * Note that all implementations may be initialized statically and thus
 * should be stateless.
 */
interface StateHandler {
    /**
     * Performs a step of the state machine when reading new data from the
     * connection.
     *
     * @param buf holds unread data from the connection.
     *
     * @return true if the state is complete.  False if more data is needed
     * in buf to proceed.
     *
     * @throws InvalidRequestException
     */
    boolean handleState(NonBlockingConnection conn, ByteBuffer buf, HandlerState state) throws InvalidRequestException;
}
