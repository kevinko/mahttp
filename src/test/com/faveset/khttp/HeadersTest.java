// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.khttp;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HeadersTest extends Headers {
    @Test
    public void testCanonicalize() {
        assertEquals("Hello", canonicalizeKey("hello"));
        assertEquals("Hello-World", canonicalizeKey("hello-world"));
        assertEquals("-Ello-World", canonicalizeKey("-ello-world"));
    }

    @Test
    public void testGet() {
        HeadersBuilder builder = new HeadersBuilder();
        builder.add("hello", "world");
        builder.add("hello", "how, am, i");
        builder.add("hello", "are");
        builder.add("hello", "you");

        List<String> resultList = builder.get("hello");
        assertEquals(4, resultList.size());
        assertEquals("world", resultList.get(0));
        assertEquals("how, am, i", resultList.get(1));
        assertEquals("are", resultList.get(2));
        assertEquals("you", resultList.get(3));

        assertEquals("world", builder.getFirst("hello"));

        Set<String> resultSet = builder.getValueSet("hello");
        assertEquals(6, resultSet.size());
        assertTrue(resultSet.contains("world"));
        assertTrue(resultSet.contains("how"));
        assertTrue(resultSet.contains("am"));
        assertTrue(resultSet.contains("i"));
        assertTrue(resultSet.contains("are"));
        assertTrue(resultSet.contains("you"));
    }

    @Test
    public void testWriteString() {
        HeadersBuilder builder = new HeadersBuilder();
        builder.add("hello", "world");
        builder.add("hello", "how");
        builder.add("hello", "are");
        builder.add("hello", "you");
        assertEquals("Hello: world,how,are,you\r\n", builder.toString());
    }

    @Test
    public void testWrite() throws BufferOverflowException {
        ByteBuffer buf = ByteBuffer.allocate(1024);

        HeadersBuilder builder = new HeadersBuilder();
        builder.add("hello", "world");
        builder.add("hello", "how");
        builder.add("hello", "are");
        builder.add("hello", "you");
        int len = builder.write(buf);
        assertEquals(26, len);

        buf.flip();

        Helper.compare(buf, "Hello: world,how,are,you\r\n");

        buf.clear();
        builder.clear();

        builder.add("hello", "world");
        builder.write(buf);

        buf.flip();
        Helper.compare(buf, "Hello: world\r\n");
    }

    @Test
    public void testWriteInserter() {
        HeadersBuilder builder = new HeadersBuilder();
        builder.add("hello", "world");
        builder.add("hello", "how");
        builder.add("hello", "are");
        builder.add("hello", "you");

        ByteBufferArrayBuilder bufBuilder = new ByteBufferArrayBuilder(4, false);
        bufBuilder.writeString("pizza!");

        assertEquals(6, bufBuilder.remaining());

        ByteBufferArrayBuilder.Inserter inserter = bufBuilder.insertBack();
        try {
            builder.write(inserter);
        } finally {
            inserter.close();
        }
        assertEquals(32, bufBuilder.remaining());

        try {
            ByteBuffer[] bufs = bufBuilder.build();
            assertEquals(8, bufs.length);
            Helper.compare(bufs[0], "pizz");
            Helper.compare(bufs[1], "a!He");
            Helper.compare(bufs[2], "llo:");
            Helper.compare(bufs[3], " wor");
            Helper.compare(bufs[4], "ld,h");
            Helper.compare(bufs[5], "ow,a");
            Helper.compare(bufs[6], "re,y");
            Helper.compare(bufs[7], "ou\r\n");
        } finally {
            bufBuilder.clear();
        }

        // Try insertFront.
        bufBuilder.writeString("pizza!");
        assertEquals(6, bufBuilder.remaining());

        inserter = bufBuilder.insertFront();
        try {
            builder.write(inserter);
        } finally {
            inserter.close();
        }
        assertEquals(32, bufBuilder.remaining());

        try {
            ByteBuffer[] bufs = bufBuilder.build();
            assertEquals(9, bufs.length);
            Helper.compare(bufs[0], "Hell");
            Helper.compare(bufs[1], "o: w");
            Helper.compare(bufs[2], "orld");
            Helper.compare(bufs[3], ",how");
            Helper.compare(bufs[4], ",are");
            Helper.compare(bufs[5], ",you");
            Helper.compare(bufs[6], "\r\n");
            Helper.compare(bufs[7], "pizz");
            Helper.compare(bufs[8], "a!");
        } finally {
            bufBuilder.clear();
        }
    }

    @Test
    public void testWritePool() {
        ByteBufferArrayBuilder bufBuilder = new ByteBufferArrayBuilder(4, false);

        HeadersBuilder builder = new HeadersBuilder();
        builder.add("hello", "world");
        builder.add("hello", "how");
        builder.add("hello", "are");
        builder.add("hello", "you");
        int len = builder.write(bufBuilder);
        assertEquals(26, len);

        try {
            ByteBuffer[] bufs = bufBuilder.build();
            assertEquals(7, bufs.length);
            Helper.compare(bufs[0], "Hell");
            Helper.compare(bufs[1], "o: w");
            Helper.compare(bufs[2], "orld");
            Helper.compare(bufs[3], ",how");
            Helper.compare(bufs[4], ",are");
            Helper.compare(bufs[5], ",you");
            Helper.compare(bufs[6], "\r\n");
        } finally {
            bufBuilder.clear();
        }
    }

    @Test
    public void testWriteStringBuilder() throws BufferOverflowException {
        StringBuilder sb = new StringBuilder();

        HeadersBuilder builder = new HeadersBuilder();
        builder.add("hello", "world");
        builder.add("hello", "how");
        builder.add("hello", "are");
        builder.add("hello", "you");
        int len = builder.write(sb);
        assertEquals(26, len);

        assertEquals("Hello: world,how,are,you\r\n", sb.toString());
    }
}
