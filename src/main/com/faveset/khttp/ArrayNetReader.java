// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

class ArrayNetReader implements NetReader {
    private ByteBufferArray mBufArray;

    public ArrayNetBuffer(ByteBuffer[] bufs) {
        this(new ByteBufferArray(bufs));
    }

    public ArrayNetBuffer(ByteBufferArray bufArray) {
        mBufArray = bufArray;
    }

    public boolean isEmpty() {
        return !mBufArray.hasRemaining();
    }

    public SSLEngineResult unwrap(SSLEngine engine, NetBuffer dest) {
        dest.prepareAppend();

        SSLEngineResult result = engine.unwrap(mBufArray.getBuffers(), dest);
        mBufArray.update();
        return result;
    }

    public SSLEngineResult unwrapUnsafe(SSLEngine engine, NetBuffer dest) {
        SSLEngineResult result = engine.unwrap(mBufArray.getBuffers(), dest);
        mBufArray.update();
        return result;
    }

    public void updateRead() {
        // This is a NOP, since we update the buf array after every wrap/unwrap.
    }

    public SSLEngineResult wrap(SSLEngine engine, NetBuffer dest) {
        dest.prepareAppend();

        SSLEngineResult result = engine.wrap(mBufArray.getBuffers(), dest);
        mBufArray.update();

        return result;
    }

    public SSLEngineResult wrapUnsafe(SSLEngine engine, NetBuffer dest) {
        SSLEngineResult result = engine.wrap(mBufArray.getBuffers(), dest);
        mBufArray.update();
        return result;
    }
}
