// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

class DirectByteBufferFactory implements ByteBufferFactory {
    private static DirectByteBufferFactory sFactory;

    /**
     * Single accessor for a shared ByteBufferFactory.  The returned factory is NOT thread-safe.
     */
    public static synchronized ByteBufferFactory get() {
        if (sFactory == null) {
            sFactory = new DirectByteBufferFactory();
        }
        return sFactory;
    }

    @Override
    public ByteBuffer make(int size) {
        return ByteBuffer.allocateDirect(size);
    }
}
