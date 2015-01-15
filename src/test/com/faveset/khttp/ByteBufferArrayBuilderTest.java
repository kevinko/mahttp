// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

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
        ByteBufferArrayBuilder builder = new ByteBufferArrayBuilder(4, false);
        builder.writeString("hello");

        ByteBuffer buf = Helper.makeByteBuffer("foo");
        builder.writeBuffer(buf);
        // Make sure that the builder has its own copy of buf (and a reference to
        // the same underlying data).
        buf.position(buf.limit());

        builder.writeString("world");

        assertEquals(13, builder.remaining());

        try {
            ByteBuffer[] bufs = builder.build();
            assertEquals(5, bufs.length);

            Helper.compare(bufs[0], "hell");
            Helper.compare(bufs[1], "o");
            Helper.compare(bufs[2], "foo");
            Helper.compare(bufs[3], "worl");
            Helper.compare(bufs[4], "d");
        } finally {
            builder.clear();
        }

        // Now, test insertion.  builder should be cleared after the build.
        builder.writeString("i am hungry!");
        assertEquals(12, builder.remaining());

        ByteBufferArrayBuilder.Inserter inserter = builder.insertFront();
        buf = Helper.makeByteBuffer("foo");
        inserter.writeBuffer(buf);
        inserter.close();

        assertEquals(15, builder.remaining());

        try {
            ByteBuffer[] bufs = builder.build();
            assertEquals(4, bufs.length);

            Helper.compare(bufs[0], "foo");
            Helper.compare(bufs[1], "i am");
            Helper.compare(bufs[2], " hun");
            Helper.compare(bufs[3], "gry!");
        } finally {
            builder.clear();
        }

        // Test insertions on an empty builder.
        assertEquals(0, builder.remaining());

        inserter = builder.insertFront();
        buf = Helper.makeByteBuffer("pizza");
        inserter.writeBuffer(buf);
        inserter.close();

        assertEquals(5, builder.remaining());
        try {
            ByteBuffer[] bufs = builder.build();
            // The inserted buffer is always inserted as a single unit.
            assertEquals(1, bufs.length);
            Helper.compare(bufs[0], "pizza");
        } finally {
            builder.clear();
        }

        // Test insertions at the end.
        builder.writeString("pizza");
        inserter = builder.insertBack();
        buf = Helper.makeByteBuffer("yummy");
        inserter.writeBuffer(buf);
        inserter.close();

        assertEquals(10, builder.remaining());
        try {
            ByteBuffer[] bufs = builder.build();
            assertEquals(3, bufs.length);

            Helper.compare(bufs[0], "pizz");
            Helper.compare(bufs[1], "a");
            Helper.compare(bufs[2], "yummy");
        } finally {
            builder.clear();
        }
    }

    @Test
    public void testStrings() {
        // Use heap buffers when testing so that Helper.compare will work.
        ByteBufferArrayBuilder builder = new ByteBufferArrayBuilder(4, false);
        builder.writeString("hello");
        builder.writeString("world");
        // Now, write exactly at the boundaries.
        builder.writeString("fo");
        builder.writeString("oob");

        assertEquals(15, builder.remaining());

        try {
            ByteBuffer[] bufs = builder.build();
            assertEquals(4, bufs.length);

            Helper.compare(bufs[0], "hell");
            Helper.compare(bufs[1], "owor");
            Helper.compare(bufs[2], "ldfo");
            Helper.compare(bufs[3], "oob");
        } finally {
            builder.clear();
        }

        // Test insertions.
        assertEquals(0, builder.remaining());
        builder.writeString("the ");
        assertEquals(4, builder.remaining());
        builder.writeString("world.");
        assertEquals(10, builder.remaining());

        ByteBufferArrayBuilder.Inserter inserter = builder.insertFront();
        inserter.writeString("Save save SAVE!");
        inserter.close();
        assertEquals(25, builder.remaining());

        try {
            ByteBuffer[] bufs = builder.build();
            assertEquals(7, bufs.length);

            Helper.compare(bufs[0], "Save");
            Helper.compare(bufs[1], " sav");
            Helper.compare(bufs[2], "e SA");
            Helper.compare(bufs[3], "VE!");
            Helper.compare(bufs[4], "the ");
            Helper.compare(bufs[5], "worl");
            Helper.compare(bufs[6], "d.");
        } finally {
            builder.clear();
        }

        // Test inserting to an empty builder.
        assertEquals(0, builder.remaining());
        inserter = builder.insertFront();
        // This is also a multiple of the buffer size 4 to test handling
        // of full buffers.
        inserter.writeString("Save me please!!");
        inserter.close();

        try {
            ByteBuffer[] bufs = builder.build();
            assertEquals(4, bufs.length);
            Helper.compare(bufs[0], "Save");
            Helper.compare(bufs[1], " me ");
            Helper.compare(bufs[2], "plea");
            Helper.compare(bufs[3], "se!!");
        } finally {
            builder.clear();
        }

        // Test insertions at the end.
        builder.writeString("pizza");
        inserter = builder.insertBack();
        inserter.writeString("yummy");
        inserter.close();

        try {
            ByteBuffer[] bufs = builder.build();
            assertEquals(3, bufs.length);
            Helper.compare(bufs[0], "pizz");
            Helper.compare(bufs[1], "ayum");
            Helper.compare(bufs[2], "my");
        } finally {
            builder.clear();
        }
    }
}
