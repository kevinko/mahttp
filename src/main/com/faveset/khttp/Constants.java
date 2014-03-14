// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

class Constants {
    // Each ByteBuffer is 4KB, and there are two fixed buffers per connection
    // (in and out).
    public static int HTTP_CONNECTION_BUFFER_POOL_SIZE = 256;

    public static int NON_BLOCKING_CONNECTION_BUFFER_SIZE = 4096;

    // True if direct ByteBuffers should be used for all non-connection
    // related ByteBuffers.
    public static boolean USE_DIRECT_BUFFERS = false;
}
