// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.nio.ByteBuffer;

@RunWith(JUnit4.class)
public class RequestStartHandlerTest {
    @Test
    public void testSimple() throws InvalidRequestException {
        ByteBuffer buf = Helper.makeByteBuffer("GET / HTTP/1.0\n");

        HandlerState state = new HandlerState();
        HttpRequestBuilder req = state.getRequestBuilder();
        HeadersBuilder headers = req.getHeadersBuilder();
        headers.add("foo", "bar");
        state.setLastHeaderName("foo");

        RequestStartHandler handler = new RequestStartHandler();
        assertTrue(handler.handleState(null, buf, state));

        // Headers should be cleared.
        assertEquals(null, headers.get("foo"));
        assertEquals("", state.getLastHeaderName());
        assertEquals(0, req.getMinorVersion());
    }

    @Test(expected=InvalidRequestException.class)
    public void testOverflow() throws InvalidRequestException {
        // Unfinished line with too small a buffer to add more.
        ByteBuffer buf = Helper.makeByteBuffer("GET / HTTP/1.1");

        HandlerState state = new HandlerState();

        RequestStartHandler handler = new RequestStartHandler();
        assertFalse(handler.handleState(null, buf, state));
    }

    @Test
    public void testPartial() throws InvalidRequestException {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        buf.put(Helper.makeByteBuffer("GET /"));
        buf.flip();

        HandlerState state = new HandlerState();

        RequestStartHandler handler = new RequestStartHandler();
        assertFalse(handler.handleState(null, buf, state));

        buf.put(Helper.makeByteBuffer(" HTTP/1.1\n"));
        buf.flip();

        assertTrue(handler.handleState(null, buf, state));

        assertEquals(1, state.getRequestBuilder().getMinorVersion());
    }

    @Test
    public void testPartial2() throws InvalidRequestException {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        buf.put(Helper.makeByteBuffer("GET / HTTP/1.0"));
        buf.flip();

        HandlerState state = new HandlerState();

        RequestStartHandler handler = new RequestStartHandler();
        assertFalse(handler.handleState(null, buf, state));

        buf.put(Helper.makeByteBuffer("\n"));
        buf.flip();

        assertTrue(handler.handleState(null, buf, state));

        assertEquals(0, state.getRequestBuilder().getMinorVersion());
    }

    @Test(expected=InvalidRequestException.class)
    public void testBadMethod() throws InvalidRequestException {
        ByteBuffer buf = Helper.makeByteBuffer("FOO / HTTP/1.1\n");
        HandlerState state = new HandlerState();
        RequestStartHandler handler = new RequestStartHandler();
        assertFalse(handler.handleState(null, buf, state));
    }

    @Test(expected=InvalidRequestException.class)
    public void testBadVersion() throws InvalidRequestException {
        ByteBuffer buf = Helper.makeByteBuffer("PUT / HTTP/1.3\n");
        HandlerState state = new HandlerState();
        RequestStartHandler handler = new RequestStartHandler();
        assertFalse(handler.handleState(null, buf, state));
    }

    @Test(expected=InvalidRequestException.class)
    public void testBadUri() throws InvalidRequestException {
        ByteBuffer buf = Helper.makeByteBuffer("POST /\n");
        HandlerState state = new HandlerState();
        RequestStartHandler handler = new RequestStartHandler();
        assertFalse(handler.handleState(null, buf, state));
    }
}
