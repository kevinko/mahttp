// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ByteBufferPoolTest {
    @Test
    public void test() {
        ByteBufferPool pool = new ByteBufferPool(1024, false, 2);

        ByteBufferPool.Entry entry1 = pool.allocate();
        assertTrue(entry1 != null);
        assertTrue(entry1.getByteBuffer() != null);
        assertEquals(0, pool.getFreeBufferCount());

        ByteBufferPool.Entry entry2 = pool.allocate();
        assertTrue(entry2 != null);
        assertTrue(entry2.getByteBuffer() != null);
        assertEquals(0, pool.getFreeBufferCount());

        // Test below boundary when releasing.
        assertEquals(null, pool.release(entry1));
        assertEquals(1, pool.getFreeBufferCount());

        assertEquals(entry1, pool.allocate());
        assertEquals(0, pool.getFreeBufferCount());

        // Now test above boundary.
        ByteBufferPool.Entry entry3 = pool.allocate();
        assertTrue(entry3 != null);
        assertEquals(0, pool.getFreeBufferCount());

        assertEquals(null, pool.release(entry1));
        assertEquals(1, pool.getFreeBufferCount());
        assertEquals(null, pool.release(entry2));
        assertEquals(2, pool.getFreeBufferCount());
        assertEquals(null, pool.release(entry3));
        assertEquals(2, pool.getFreeBufferCount());
    }
}
