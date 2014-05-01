// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;

interface NetReader {
    boolean isEmpty();

    SSLEngineResult unwrap(SSLEngine engine, NetBuffer dest);
    SSLEngineResult unwrapUnsafe(SSLEngine engine, NetBuffer dest);

    void updateRead();

    SSLEngineResult wrap(SSLEngine engine, NetBuffer dest);
    SSLEngineResult wrapUnsafe(SSLEngine engine, NetBuffer dest);
}
