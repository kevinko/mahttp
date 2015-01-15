// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

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

        PoolEntry<ByteBuffer> entry1 = pool.allocate();
        assertTrue(entry1 != null);
        assertTrue(entry1.get() != null);
        assertEquals(0, pool.getFreeEntryCount());

        PoolEntry<ByteBuffer> entry2 = pool.allocate();
        assertTrue(entry2 != null);
        assertTrue(entry2.get() != null);
        assertEquals(0, pool.getFreeEntryCount());

        //
        // Test below boundary when releasing.
        //

        // Fill entry 1 with something to test reset.
        entry1.get().put((byte) 1);

        assertEquals(null, pool.release(entry1));
        assertEquals(1, pool.getFreeEntryCount());

        // We are reallocating from the pool.
        assertEquals(entry1, pool.allocate());
        assertEquals(0, pool.getFreeEntryCount());
        // Make sure that the entry is reset.
        assertEquals(0, entry1.get().position());

        //
        // Now test above boundary.
        //
        PoolEntry<ByteBuffer> entry3 = pool.allocate();
        assertTrue(entry3 != null);
        assertEquals(0, pool.getFreeEntryCount());

        assertEquals(null, pool.release(entry1));
        assertEquals(1, pool.getFreeEntryCount());
        assertEquals(null, pool.release(entry2));
        assertEquals(2, pool.getFreeEntryCount());
        assertEquals(null, pool.release(entry3));
        assertEquals(2, pool.getFreeEntryCount());
    }
}
