// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

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
        assertEquals(result, null);
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
        assertEquals(result, null);
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
        assertEquals(Strings.parseWord(buf), "word1");
        assertEquals(Strings.parseWord(buf), "word2");
        assertEquals(Strings.parseWord(buf), "word3");
        assertEquals(Strings.parseWord(buf), "");

        testStr = "word1\n";
        buf = ByteBuffer.wrap(testStr.getBytes(Helper.US_ASCII_CHARSET));
        assertEquals(Strings.parseWord(buf), "word1");
        assertEquals(Strings.parseWord(buf), "");
    }

    @Test
    public void testParseVersion() throws ParseException {
        ByteBuffer buf = Helper.makeByteBuffer("HTTP/1.0");
        assertEquals(Strings.parseHttpVersion(buf), 0);

        buf = Helper.makeByteBuffer("HTTP/1.1");
        assertEquals(Strings.parseHttpVersion(buf), 1);

        buf = Helper.makeByteBuffer("HTTP/1.12");
        assertEquals(Strings.parseHttpVersion(buf), 12);
    }

    @Test(expected=ParseException.class)
    public void testParseVersionException() throws ParseException {
        ByteBuffer buf = Helper.makeByteBuffer("HTTP /1.2");
        Strings.parseHttpVersion(buf);
    }

    @Test
    public void testParseText() {
        ByteBuffer buf = Helper.makeByteBuffer("Hello: world");
        assertEquals(Strings.parseText(buf), "Hello: world");
        assertFalse(buf.hasRemaining());

        buf = Helper.makeByteBuffer("Hello: world\037");
        assertEquals(Strings.parseText(buf), "Hello: world");
        assertEquals(buf.get(), '\037');
    }

    @Test
    public void testParseToken() {
        ByteBuffer buf = Helper.makeByteBuffer("Hello: world");
        assertEquals(Strings.parseToken(buf), "Hello");
        assertEquals(buf.get(), ':');

        buf = Helper.makeByteBuffer(":");
        assertEquals(Strings.parseToken(buf), "");
        assertEquals(buf.get(), ':');

        buf = Helper.makeByteBuffer("");
        assertEquals(Strings.parseToken(buf), "");
        assertFalse(buf.hasRemaining());
    }

    @Test
    public void testSkipWhitespaceString() {
        String s = "hello";
        assertEquals(Strings.skipWhitespaceString(s, 0), 0);
        assertEquals(Strings.skipWhitespaceStringReverse(s, s.length() - 1), s.length() - 1);

        s = " hello ";
        assertEquals(Strings.skipWhitespaceString(s, 0), 1);
        assertEquals(Strings.skipWhitespaceStringReverse(s, s.length() - 1), s.length() - 2);
        assertEquals(Strings.skipWhitespaceStringReverse(s, s.length()), s.length() - 2);
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
