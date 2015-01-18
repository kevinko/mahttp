// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import javax.net.ssl.SSLException;

interface SSLState {
    enum OpResult {
        NONE,
        DRAIN_DEST_BUFFER,
        ENGINE_CLOSE,
        SCHEDULE_TASKS,
        SCHEDULE_UNWRAP,
        SCHEDULE_WRAP,
        STATE_CHANGE,
        // This should only be called from stepUnwrap.
        UNWRAP_LOAD_SRC_BUFFER,
    }

    /**
     * Unwraps from src to dest.  src is a network buffer, which may be resized.
     *
     * @throws SSLException
     */
    OpResult stepUnwrap(NetBuffer src, NetBuffer dest) throws SSLException;

    /**
     * Wraps from src to dest.
     *
     * @throws SSLException
     */
    OpResult stepWrap(NetReader src, NetBuffer dest) throws SSLException;
}
