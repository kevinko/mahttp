// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

class DirectByteBufferFactory implements ByteBufferFactory {
    @Override
    public ByteBuffer make(int size) {
        return ByteBuffer.allocateDirect(size);
    }
}
