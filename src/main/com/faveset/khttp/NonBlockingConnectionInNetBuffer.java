// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

/**
 * This extension updates an associated NonBlockingConnection's in buffer
 * when the buffer is resized.
 *
 * It uses the connection's internal ByteBuffer initially.
 */
class NonBlockingConnectionInNetBuffer extends NetBuffer {
    private NonBlockingConnection mConn;

    public NonBlockingConnectionInNetBuffer(NonBlockingConnection conn) {
        super(conn.getInBuffer());

        mConn = conn;
    }

    @Override
    public void resize(ByteBufferFactory factory, int size) {
        super.resize(factory, size);
        mConn.setInBufferInternal(getByteBuffer());
    }

    @Override
    public void resizeUnsafe(ByteBufferFactory factory, int size) {
        super.resizeUnsafe(factory, size);
        mConn.setInBufferInternal(getByteBuffer());
    }
}
