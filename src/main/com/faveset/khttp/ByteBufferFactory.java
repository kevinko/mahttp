// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

interface ByteBufferFactory {
    ByteBuffer make(int size);
}
