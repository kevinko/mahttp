// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

class Constants {
    // Each ByteBuffer is 4KB, and there are two fixed buffers per connection
    // (in and out).
    public static int BYTE_BUFFER_POOL_SIZE = 256;

    // Size of each direct ByteBuffer in bytes.
    //
    // This is used as the internal NonBlockingConnection buffer.
    //
    // It is also used when assembling the response.
    //
    // Most web servers set the max total header length for an HTTP request
    // to 4-8KB, so we'll just choose a standard page size as the fundamental
    // unit.
    public static int BYTE_BUFFER_SIZE = 4096;
}
