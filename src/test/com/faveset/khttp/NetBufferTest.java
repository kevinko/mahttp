// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NetBufferTest {
    @Test
    public void test() {
        ByteBuffer buf = ByteBuffer.allocate(1024);

        NetBuffer netBuf = NetBuffer.makeAppendBuffer(buf);

        assertTrue(netBuf.isCleared());
        assertTrue(netBuf.isEmpty());

        assertTrue(!netBuf.needsResize(100));
        assertTrue(!netBuf.needsResize(1024));
        assertTrue(netBuf.needsResize(1025));

        buf.limit(1);
        assertTrue(!netBuf.isCleared());

        buf.limit(buf.capacity());
        assertTrue(netBuf.isCleared());

        buf.put((byte) 0);
        assertTrue(!netBuf.isCleared());
        assertTrue(!netBuf.isEmpty());

        buf.put((byte) 0);

        // Test preservation when resizing.
        ByteBufferFactory factory = new HeapByteBufferFactory();
        netBuf.resize(factory, 2048);
        buf = netBuf.getByteBuffer();

        assertEquals(2048, buf.capacity());
        assertEquals(2, buf.position());

        netBuf.prepareRead();

        try {
            assertTrue(!netBuf.isEmpty());
            assertEquals(0, buf.get());

            assertTrue(!netBuf.isEmpty());
            assertEquals(0, buf.get());

            assertTrue(netBuf.isEmpty());
        } finally {
            netBuf.updateRead();
        }

        // Now, test appending, which should continue from where we finished reading.
        netBuf.prepareAppend();

        assertEquals(2, buf.position());

        assertTrue(netBuf.isEmpty());

        buf.put((byte) 0);

        netBuf.prepareRead();

        // We should resume reading from the last unread position (2).
        assertEquals(2, buf.position());
        assertEquals(0, buf.get());
        assertTrue(netBuf.isEmpty());

        netBuf.resizeUnsafe(factory, 4096);
        assertTrue(netBuf.isCleared());
    }
}
