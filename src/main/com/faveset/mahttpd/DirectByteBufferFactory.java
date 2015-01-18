// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

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
