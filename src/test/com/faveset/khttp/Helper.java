// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static org.junit.Assert.assertEquals;

class Helper {
    public static final Charset US_ASCII_CHARSET = Charset.forName("US-ASCII");

    public static void compare(ByteBuffer buf, String v) {
        String dataStr = new String(buf.array(), buf.position(), buf.remaining(), US_ASCII_CHARSET);
        assertEquals(v, dataStr);
    }

    public static ByteBuffer makeByteBuffer(String s) {
        return ByteBuffer.wrap(s.getBytes(US_ASCII_CHARSET));
    }
}
