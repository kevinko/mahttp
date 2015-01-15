// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.khttp;

import java.nio.ByteBuffer;

class HeapByteBufferFactory implements ByteBufferFactory {
    private static HeapByteBufferFactory sFactory;

    /**
     * Single accessor for a shared ByteBufferFactory.  The returned factory is NOT thread-safe.
     */
    public static synchronized ByteBufferFactory get() {
        if (sFactory == null) {
            sFactory = new HeapByteBufferFactory();
        }
        return sFactory;
    }

    @Override
    public ByteBuffer make(int size) {
        return ByteBuffer.allocate(size);
    }
}
