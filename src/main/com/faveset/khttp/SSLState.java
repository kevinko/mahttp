// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

interface SSLState {
    enum OpResult {
        NONE,
        DRAIN_DEST_BUFFER,
        ENGINE_CLOSE,
        LOAD_SRC_BUFFER,
        SCHEDULE_TASKS,
        SCHEDULE_UNWRAP,
        SCHEDULE_WRAP,
        STATE_CHANGE,
    }

    /**
     * Unwraps from src to dest.
     */
    OpResult stepUnwrap(NetReader src, NetBuffer dest);

    /**
     * Wraps from src to dest.
     */
    OpResult stepWrap(NetReader src, NetBuffer dest);
}
