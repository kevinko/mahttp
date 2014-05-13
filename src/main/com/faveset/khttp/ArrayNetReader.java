// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

class ArrayNetReader implements NetReader {
    private ByteBufferArray mBufArray;

    public ArrayNetReader(ByteBuffer[] bufs) {
        this(new ByteBufferArray(bufs));
    }

    public ArrayNetReader(ByteBufferArray bufArray) {
        mBufArray = bufArray;
    }

    @Override
    public boolean isEmpty() {
        return (!mBufArray.hasRemaining());
    }

    @Override
    public SSLEngineResult unwrap(SSLEngine engine, NetBuffer dest) throws SSLException {
        dest.prepareAppend();
        return unwrapUnsafe(engine, dest);
    }

    public SSLEngineResult unwrapUnsafe(SSLEngine engine, NetBuffer dest) throws SSLException {
        ByteBuffer[] srcBufs = mBufArray.getBuffers();
        ByteBuffer destBuf = dest.getByteBuffer();

        do {
            ByteBuffer currBuf = srcBufs[mBufArray.getNonEmptyOffset()];

            SSLEngineResult result = engine.unwrap(currBuf, destBuf);
            mBufArray.update();

            if (!mBufArray.hasRemaining() ||
                    result.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING ||
                    result.getStatus() != SSLEngineResult.Status.OK) {
                return result;
            }

            // Otherwise, we unwrapped some data with an OK result; continue unwrapping.
        } while (true);
    }

    public void updateRead() {
        // This is a NOP, since we update the buf array after every wrap/unwrap.
    }

    public SSLEngineResult wrap(SSLEngine engine, NetBuffer dest) throws SSLException {
        dest.prepareAppend();
        return wrapUnsafe(engine, dest);
    }

    public SSLEngineResult wrapUnsafe(SSLEngine engine, NetBuffer dest) throws SSLException {
        SSLEngineResult result = engine.wrap(mBufArray.getBuffers(),
                mBufArray.getNonEmptyOffset(), mBufArray.getNonEmptyLength(),
                dest.getByteBuffer());
        mBufArray.update();
        return result;
    }
}
