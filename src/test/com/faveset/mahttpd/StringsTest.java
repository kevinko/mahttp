// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class StringsTest {
    private Helper mHelper = new Helper();

    @Test
    public void testHasCrlf() {
        assertTrue(Strings.hasLeadingCrlf(Helper.makeByteBuffer("\n")));
        assertTrue(Strings.hasLeadingCrlf(Helper.makeByteBuffer("\r\n")));
        assertFalse(Strings.hasLeadingCrlf(Helper.makeByteBuffer(" \n")));
        assertFalse(Strings.hasLeadingCrlf(Helper.makeByteBuffer(" n")));
        assertFalse(Strings.hasLeadingCrlf(Helper.makeByteBuffer(" \r\n")));

        ByteBuffer buf = Helper.makeByteBuffer(" \r\n");
        int oldPos = buf.position();
        assertFalse(Strings.hasLeadingCrlf(buf));
        assertEquals(oldPos, buf.position());
    }

    @Test
    public void testJoin() {
        assertEquals("", Strings.join(Arrays.asList(""), ","));
        assertEquals("a", Strings.join(Arrays.asList("a"), ","));
        assertEquals("a,b", Strings.join(Arrays.asList("a", "b"), ","));
    }

    @Test
    public void testParseLine() {
        String testStr = "line1\nline2\r\n\nline3";
        ByteBuffer buf = ByteBuffer.wrap(testStr.getBytes(Helper.US_ASCII_CHARSET));

        ByteBuffer result = Strings.parseLine(buf);
        Helper.compare(result, "line1");

        result = Strings.parseLine(buf);
        Helper.compare(result, "line2\r");

        result = Strings.parseLine(buf);
        Helper.compare(result, "");

        result = Strings.parseLine(buf);
        assertEquals(null, result);
        ByteBuffer cmp = buf.duplicate();
        cmp.flip();
        Helper.compare(cmp, "line3");

        // Now, try appending.
        String appendStr = "line3b\nline4";
        ByteBuffer appendBuf = ByteBuffer.wrap(appendStr.getBytes(Helper.US_ASCII_CHARSET));
        buf.put(appendBuf);
        buf.flip();

        // Compare the appended buffer.
        result = Strings.parseLine(buf);
        Helper.compare(result, "line3line3b");

        result = Strings.parseLine(buf);
        assertEquals(null, result);
        cmp = buf.duplicate();
        cmp.flip();
        Helper.compare(cmp, "line4");
    }

    @Test(expected=BufferOverflowException.class)
    public void testParseLineOverflow() {
        String testStr = "line1";
        ByteBuffer buf = ByteBuffer.wrap(testStr.getBytes(Helper.US_ASCII_CHARSET));
        // This should throw an exception, since we assume that buf is of fixed
        // size and nothing can be appended to it to lead to a newline.
        Strings.parseLine(buf);
    }

    @Test
    public void testParseWord() {
        String testStr = "\n\r \tword1 word2  word3";
        ByteBuffer buf = ByteBuffer.wrap(testStr.getBytes(Helper.US_ASCII_CHARSET));
        assertEquals("word1", Strings.parseWord(buf));
        assertEquals("word2", Strings.parseWord(buf));
        assertEquals("word3", Strings.parseWord(buf));
        assertEquals("", Strings.parseWord(buf));

        testStr = "word1\n";
        buf = ByteBuffer.wrap(testStr.getBytes(Helper.US_ASCII_CHARSET));
        assertEquals("word1", Strings.parseWord(buf));
        assertEquals("", Strings.parseWord(buf));
    }

    @Test
    public void testParseVersion() throws ParseException {
        ByteBuffer buf = Helper.makeByteBuffer("HTTP/1.0");
        assertEquals(0, Strings.parseHttpVersion(buf));

        buf = Helper.makeByteBuffer("HTTP/1.1");
        assertEquals(1, Strings.parseHttpVersion(buf));

        buf = Helper.makeByteBuffer("HTTP/1.12");
        assertEquals(12, Strings.parseHttpVersion(buf));
    }

    @Test(expected=ParseException.class)
    public void testParseVersionException() throws ParseException {
        ByteBuffer buf = Helper.makeByteBuffer("HTTP /1.2");
        Strings.parseHttpVersion(buf);
    }

    @Test
    public void testParseText() {
        ByteBuffer buf = Helper.makeByteBuffer("Hello: world");
        assertEquals("Hello: world", Strings.parseText(buf));
        assertFalse(buf.hasRemaining());

        buf = Helper.makeByteBuffer("Hello: world\037");
        assertEquals("Hello: world", Strings.parseText(buf));
        assertEquals('\037', buf.get());
    }

    @Test
    public void testParseToken() {
        ByteBuffer buf = Helper.makeByteBuffer("Hello: world");
        assertEquals("Hello", Strings.parseToken(buf));
        assertEquals(':', buf.get());

        buf = Helper.makeByteBuffer(":");
        assertEquals("", Strings.parseToken(buf));
        assertEquals(':', buf.get());

        buf = Helper.makeByteBuffer("");
        assertEquals("", Strings.parseToken(buf));
        assertFalse(buf.hasRemaining());
    }

    @Test
    public void testSkipWhitespaceString() {
        String s = "hello";
        assertEquals(0, Strings.skipWhitespaceString(s, 0));
        assertEquals(s.length() - 1, Strings.skipWhitespaceStringReverse(s, s.length() - 1));

        s = " hello ";
        assertEquals(1, Strings.skipWhitespaceString(s, 0));
        assertEquals(s.length() - 2, Strings.skipWhitespaceStringReverse(s, s.length() - 1));
        assertEquals(s.length() - 2, Strings.skipWhitespaceStringReverse(s, s.length()));
    }

    @Test
    public void testSplitTrim() {
        assertEquals(new ArrayList<String>(), Strings.splitTrim("", ','));
        assertEquals(Arrays.asList("", ""), Strings.splitTrim(",", ','));
        assertEquals(Arrays.asList("", "", ""), Strings.splitTrim(",,", ','));
        assertEquals(Arrays.asList(""), Strings.splitTrim(" ", ','));
        assertEquals(Arrays.asList("", ""), Strings.splitTrim(" , ", ','));
        assertEquals(Arrays.asList("hello", "world"), Strings.splitTrim(" hello   ,world   ", ','));
    }
}
