// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HttpConnectionParserTest {
    private static final Charset US_ASCII_CHARSET = Charset.forName("US-ASCII");

    private void compare(ByteBuffer buf, String v) {
        String dataStr = new String(buf.array(), buf.position(), buf.remaining(), US_ASCII_CHARSET);
        assertTrue(dataStr.equals(v));
    }

    private ByteBuffer makeByteBuffer(String s) {
        return ByteBuffer.wrap(s.getBytes(US_ASCII_CHARSET));
    }

    @Test
    public void testParseLine() {
        String testStr = "line1\nline2\r\n\nline3";
        ByteBuffer buf = ByteBuffer.wrap(testStr.getBytes(US_ASCII_CHARSET));

        ByteBuffer result = HttpConnectionParser.parseLine(buf);
        compare(result, "line1");

        result = HttpConnectionParser.parseLine(buf);
        compare(result, "line2\r");

        result = HttpConnectionParser.parseLine(buf);
        compare(result, "");

        result = HttpConnectionParser.parseLine(buf);
        assertEquals(result, null);
        ByteBuffer cmp = buf.duplicate();
        cmp.flip();
        compare(cmp, "line3");

        // Now, try appending.
        String appendStr = "line3b\nline4";
        ByteBuffer appendBuf = ByteBuffer.wrap(appendStr.getBytes(US_ASCII_CHARSET));
        buf.put(appendBuf);
        buf.flip();

        // Compare the appended buffer.
        result = HttpConnectionParser.parseLine(buf);
        compare(result, "line3line3b");

        result = HttpConnectionParser.parseLine(buf);
        assertEquals(result, null);
        cmp = buf.duplicate();
        cmp.flip();
        compare(cmp, "line4");
    }

    @Test(expected=BufferOverflowException.class)
    public void testParseLineOverflow() {
        String testStr = "line1";
        ByteBuffer buf = ByteBuffer.wrap(testStr.getBytes(US_ASCII_CHARSET));
        // This should throw an exception, since we assume that buf is of fixed
        // size and nothing can be appended to it to lead to a newline.
        HttpConnectionParser.parseLine(buf);
    }

    @Test
    public void testParseWord() {
        String testStr = "\n\r \tword1 word2  word3";
        ByteBuffer buf = ByteBuffer.wrap(testStr.getBytes(US_ASCII_CHARSET));
        assertEquals(HttpConnectionParser.parseWord(buf), "word1");
        assertEquals(HttpConnectionParser.parseWord(buf), "word2");
        assertEquals(HttpConnectionParser.parseWord(buf), "word3");
        assertEquals(HttpConnectionParser.parseWord(buf), "");

        testStr = "word1\n";
        buf = ByteBuffer.wrap(testStr.getBytes(US_ASCII_CHARSET));
        assertEquals(HttpConnectionParser.parseWord(buf), "word1");
        assertEquals(HttpConnectionParser.parseWord(buf), "");
    }

    @Test
    public void testParseVersion() {
        ByteBuffer buf = makeByteBuffer("HTTP/1.0");
        assertEquals(HttpConnectionParser.parseHttpVersion(buf), 0);

        buf = makeByteBuffer("HTTP/1.1");
        assertEquals(HttpConnectionParser.parseHttpVersion(buf), 1);

        buf = makeByteBuffer("HTTP/1.12");
        assertEquals(HttpConnectionParser.parseHttpVersion(buf), 12);
    }

    @Test(expected=IllegalArgumentException.class)
    public void testParseVersionException() {
        ByteBuffer buf = makeByteBuffer("HTTP /1.2");
        HttpConnectionParser.parseHttpVersion(buf);
    }

    @Test
    public void testParseToken() {
        ByteBuffer buf = makeByteBuffer("Hello: world");
        assertEquals(HttpConnectionParser.parseToken(buf), "Hello");
        assertEquals(buf.get(), ':');

        buf = makeByteBuffer(":");
        assertEquals(HttpConnectionParser.parseToken(buf), "");
        assertEquals(buf.get(), ':');

        buf = makeByteBuffer("");
        assertEquals(HttpConnectionParser.parseToken(buf), "");
        assertFalse(buf.hasRemaining());
    }
}
