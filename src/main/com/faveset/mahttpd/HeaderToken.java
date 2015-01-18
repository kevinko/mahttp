// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

/**
 * This class holds various token values that may appear within HTTP headers.
 */
class HeaderToken {
    public static final String CHUNKED = "chunked";
    public static final String CLOSE = "close";
    // This is for HTTP/1.0 compatibility.
    public static final String KEEP_ALIVE = "Keep-Alive";
}
