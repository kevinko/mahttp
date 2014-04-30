// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

/**
 * This extension updates an associated NonBlockingConnection's out buffer
 * when the buffer is resized.
 */
class NonBlockingConnectionOutNetBuffer extends NetBuffer {
    private NonBlockingConnection mConn;

    public NonBlockingConnectionOutNetBuffer(NonBlockingConnection conn,
            ByteBuffer buf) {
        super(buf);

        mConn = conn;
    }

    @Override
    public void resize(ByteBufferFactory factory, int size) {
        super.resize(factory, size);
        mConn.setOutBufferInternal(mBuf);
    }

    @Override
    public void resizeUnsafe(ByteBufferFactory factory, int size) {
        super.resizeUnsafe(factory, size);
        mConn.setOutBufferInternal(mBuf);
    }
}
