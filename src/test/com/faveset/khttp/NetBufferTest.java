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

        buf.put((byte) 0);

        // Read the first element to offset the buffer.
        netBuf.flipRead();
        try {
            assertTrue(!netBuf.isEmpty());
            assertEquals(0, buf.get());
        } finally {
            netBuf.updateRead();
        }

        netBuf.flipAppend();
        assertTrue(!netBuf.isCompacted());
        assertEquals(3, buf.position());

        // Test preservation when resizing.  This should compact the underlying buffer.
        ByteBufferFactory factory = new HeapByteBufferFactory();
        netBuf.resize(factory, 2048);
        buf = netBuf.getByteBuffer();

        assertTrue(netBuf.isCompacted());
        assertEquals(2048, buf.capacity());
        // The underlying buffer has been compacted.
        assertEquals(2, buf.position());

        netBuf.flipRead();

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
        netBuf.flipAppend();

        assertEquals(2, buf.position());

        assertTrue(netBuf.isEmpty());

        buf.put((byte) 0);

        netBuf.flipRead();

        // We should resume reading from the last unread position (2).
        assertEquals(2, buf.position());
        assertEquals(0, buf.get());
        assertTrue(netBuf.isEmpty());

        netBuf.resizeUnsafe(factory, 4096);
        buf = netBuf.getByteBuffer();
        assertTrue(netBuf.isCleared());

        // Test clearing.
        netBuf.flipAppend();
        buf.put((byte) 0);

        netBuf.flipRead();
        assertTrue(!netBuf.isEmpty());
        netBuf.clear();
        assertTrue(netBuf.isEmpty());
    }

    @Test
    public void testCompaction() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        NetBuffer netBuf = NetBuffer.makeAppendBuffer(buf);
        assertTrue(!netBuf.isFull());

        buf.put((byte) 0);
        assertTrue(!netBuf.isFull());
        buf.put((byte) 0);
        assertTrue(!netBuf.isFull());
        buf.put((byte) 0);
        assertTrue(!netBuf.isFull());
        buf.put((byte) 0);
        assertTrue(netBuf.isFull());

        // Read the first element to offset the buffer.
        netBuf.flipRead();
        try {
            assertTrue(!netBuf.isEmpty());
            assertEquals(0, buf.get());
        } finally {
            netBuf.updateRead();
        }
        // The buffer is still full, since it is not compacted.
        assertTrue(netBuf.isFull());

        netBuf.flipAppend();

        assertTrue(netBuf.isFull());
        assertTrue(!netBuf.isCompacted());

        assertEquals(4, buf.position());

        // Now, compact.
        netBuf.compact();

        assertTrue(netBuf.isCompacted());
        // Compaction frees a space.
        assertTrue(!netBuf.isFull());

        assertEquals(3, buf.position());
    }
}
