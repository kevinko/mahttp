// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ByteBufferArrayBuilderTest {
    @Test
    public void testBuffers() {
        // Use heap buffers when testing so that Helper.compare will work.
        ByteBufferArrayBuilder pool = new ByteBufferArrayBuilder(4, false);
        pool.writeString("hello");

        ByteBuffer buf = Helper.makeByteBuffer("foo");
        pool.writeBuffer(buf);
        // Make sure that the pool has its own copy of buf (and a reference to
        // the same underlying data).
        buf.position(buf.limit());

        pool.writeString("world");

        assertEquals(13, pool.remaining());

        ByteBuffer[] bufs = pool.build();
        assertEquals(5, bufs.length);

        Helper.compare(bufs[0], "hell");
        Helper.compare(bufs[1], "o");
        Helper.compare(bufs[2], "foo");
        Helper.compare(bufs[3], "worl");
        Helper.compare(bufs[4], "d");

        // Now, test insertion.  pool should be cleared after the build.
        pool.writeString("i am hungry!");
        assertEquals(12, pool.remaining());

        ByteBufferArrayBuilder.Inserter inserter = pool.insertFront();
        buf = Helper.makeByteBuffer("foo");
        inserter.writeBuffer(buf);
        inserter.close();

        assertEquals(15, pool.remaining());

        bufs = pool.build();
        assertEquals(4, bufs.length);

        Helper.compare(bufs[0], "foo");
        Helper.compare(bufs[1], "i am");
        Helper.compare(bufs[2], " hun");
        Helper.compare(bufs[3], "gry!");

        // Test insertions on an empty pool.
        assertEquals(0, pool.remaining());

        inserter = pool.insertFront();
        buf = Helper.makeByteBuffer("pizza");
        inserter.writeBuffer(buf);
        inserter.close();

        assertEquals(5, pool.remaining());
        bufs = pool.build();
        // The inserted buffer is always inserted as a single unit.
        assertEquals(1, bufs.length);
        Helper.compare(bufs[0], "pizza");

        // Test insertions at the end.
        pool.writeString("pizza");
        inserter = pool.insertBack();
        buf = Helper.makeByteBuffer("yummy");
        inserter.writeBuffer(buf);
        inserter.close();

        assertEquals(10, pool.remaining());
        bufs = pool.build();
        assertEquals(3, bufs.length);

        Helper.compare(bufs[0], "pizz");
        Helper.compare(bufs[1], "a");
        Helper.compare(bufs[2], "yummy");
    }

    @Test
    public void testStrings() {
        // Use heap buffers when testing so that Helper.compare will work.
        ByteBufferArrayBuilder pool = new ByteBufferArrayBuilder(4, false);
        pool.writeString("hello");
        pool.writeString("world");
        // Now, write exactly at the boundaries.
        pool.writeString("fo");
        pool.writeString("oob");

        assertEquals(15, pool.remaining());

        ByteBuffer[] bufs = pool.build();
        assertEquals(4, bufs.length);

        Helper.compare(bufs[0], "hell");
        Helper.compare(bufs[1], "owor");
        Helper.compare(bufs[2], "ldfo");
        Helper.compare(bufs[3], "oob");

        // Test insertions.
        assertEquals(0, pool.remaining());
        pool.writeString("the ");
        assertEquals(4, pool.remaining());
        pool.writeString("world.");
        assertEquals(10, pool.remaining());

        ByteBufferArrayBuilder.Inserter inserter = pool.insertFront();
        inserter.writeString("Save save SAVE!");
        inserter.close();
        assertEquals(25, pool.remaining());

        bufs = pool.build();
        assertEquals(7, bufs.length);

        Helper.compare(bufs[0], "Save");
        Helper.compare(bufs[1], " sav");
        Helper.compare(bufs[2], "e SA");
        Helper.compare(bufs[3], "VE!");
        Helper.compare(bufs[4], "the ");
        Helper.compare(bufs[5], "worl");
        Helper.compare(bufs[6], "d.");

        // Test inserting to an empty pool.
        assertEquals(0, pool.remaining());
        inserter = pool.insertFront();
        // This is also a multiple of the buffer size 4 to test handling
        // of full buffers.
        inserter.writeString("Save me please!!");
        inserter.close();

        bufs = pool.build();
        assertEquals(4, bufs.length);
        Helper.compare(bufs[0], "Save");
        Helper.compare(bufs[1], " me ");
        Helper.compare(bufs[2], "plea");
        Helper.compare(bufs[3], "se!!");

        // Test insertions at the end.
        pool.writeString("pizza");
        inserter = pool.insertBack();
        inserter.writeString("yummy");
        inserter.close();

        bufs = pool.build();
        assertEquals(3, bufs.length);
        Helper.compare(bufs[0], "pizz");
        Helper.compare(bufs[1], "ayum");
        Helper.compare(bufs[2], "my");
    }
}
