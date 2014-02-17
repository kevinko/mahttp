// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ByteBufferPoolTest {
    @Test
    public void testBuffers() {
        // Use heap buffers when testing so that Helper.compare will work.
        ByteBufferPool pool = new ByteBufferPool(4, false);
        pool.writeString("hello");

        ByteBuffer buf = Helper.makeByteBuffer("foo");
        pool.writeBuffer(buf);
        // Make sure that the pool has its own copy of buf (and a reference to
        // the same underlying data).
        buf.position(buf.limit());

        pool.writeString("world");

        ByteBuffer[] bufs = pool.build();
        assertEquals(5, bufs.length);

        Helper.compare(bufs[0], "hell");
        Helper.compare(bufs[1], "o");
        Helper.compare(bufs[2], "foo");
        Helper.compare(bufs[3], "worl");
        Helper.compare(bufs[4], "d");
    }

    @Test
    public void testStrings() {
        // Use heap buffers when testing so that Helper.compare will work.
        ByteBufferPool pool = new ByteBufferPool(4, false);
        pool.writeString("hello");
        pool.writeString("world");
        // Now, write exactly at the boundaries.
        pool.writeString("fo");
        pool.writeString("oob");

        ByteBuffer[] bufs = pool.build();
        assertEquals(4, bufs.length);

        Helper.compare(bufs[0], "hell");
        Helper.compare(bufs[1], "owor");
        Helper.compare(bufs[2], "ldfo");
        Helper.compare(bufs[3], "oob");
    }
}
