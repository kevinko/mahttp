// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

/**
 * This extension updates an associated NonBlockingConnection's out buffer
 * when the buffer is resized.
 *
 * It uses the connection's internal ByteBuffer initially.
 */
class NonBlockingConnectionOutNetBuffer extends NetBuffer {
    private NonBlockingConnection mConn;

    /**
     * conn's buffer must be cleared (or newly allocated).
     */
    public NonBlockingConnectionOutNetBuffer(NonBlockingConnection conn) {
        super(conn.getOutBuffer());

        mConn = conn;
    }

    @Override
    public void resize(ByteBufferFactory factory, int size) {
        super.resize(factory, size);
        mConn.setOutBufferInternal(getByteBuffer());
    }

    @Override
    public void resizeUnsafe(ByteBufferFactory factory, int size) {
        super.resizeUnsafe(factory, size);
        mConn.setOutBufferInternal(getByteBuffer());
    }
}
