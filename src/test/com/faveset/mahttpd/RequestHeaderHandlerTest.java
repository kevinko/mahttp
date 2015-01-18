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
public class RequestHeaderHandlerTest {
    @Test
    public void testSimple() throws InvalidRequestException {
        ByteBuffer buf = Helper.makeByteBuffer("hello: world\n");
        HandlerState state = new HandlerState();
        HeadersBuilder headers = state.getRequestBuilder().getHeadersBuilder();

        RequestHeaderHandler handler = new RequestHeaderHandler();
        assertFalse(handler.handleState(null, buf, state));

        assertEquals(1, headers.get("hello").size());
        assertEquals("world", headers.getFirst("hello"));

        buf.clear();
        buf.put(Helper.makeByteBuffer("foo: bar\r\n"));
        buf.flip();
        assertFalse(handler.handleState(null, buf, state));
        assertEquals(1, headers.get("foo").size());
        assertEquals("bar", headers.getFirst("foo"));

        buf.clear();
        buf.put(Helper.makeByteBuffer("\n"));
        buf.flip();
        assertTrue(handler.handleState(null, buf, state));
    }

    @Test
    public void testContinuation() throws InvalidRequestException {
        ByteBuffer buf = Helper.makeByteBuffer("hello: world\n");
        HandlerState state = new HandlerState();
        HeadersBuilder headers = state.getRequestBuilder().getHeadersBuilder();

        RequestHeaderHandler handler = new RequestHeaderHandler();
        assertFalse(handler.handleState(null, buf, state));

        assertEquals(1, headers.get("hello").size());
        assertEquals("world", headers.getFirst("hello"));

        buf.clear();
        buf.put(Helper.makeByteBuffer(" hi?\n"));
        buf.flip();
        assertFalse(handler.handleState(null, buf, state));

        assertEquals(1, headers.get("hello").size());
        assertEquals("world hi?", headers.getFirst("hello"));

        buf.clear();
        buf.put(Helper.makeByteBuffer(" ah!\n"));
        buf.flip();
        assertFalse(handler.handleState(null, buf, state));

        assertEquals(1, headers.get("hello").size());
        assertEquals("world hi? ah!", headers.getFirst("hello"));

        buf.clear();
        buf.put(Helper.makeByteBuffer("foo: bar!\n"));
        buf.flip();
        assertFalse(handler.handleState(null, buf, state));

        assertEquals(1, headers.get("foo").size());
        assertEquals("bar!", headers.getFirst("foo"));

        buf.clear();
        buf.put(Helper.makeByteBuffer("\n"));
        buf.flip();
        assertTrue(handler.handleState(null, buf, state));
    }

    @Test
    public void testMultiple() throws InvalidRequestException {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        buf.put(Helper.makeByteBuffer("hello: world\r\nfoo: bar\r\n hid!\r\n\r\n"));
        buf.flip();

        HandlerState state = new HandlerState();

        RequestHeaderHandler handler = new RequestHeaderHandler();
        assertTrue(handler.handleState(null, buf, state));

        HeadersBuilder headers = state.getRequestBuilder().getHeadersBuilder();
        assertEquals(1, headers.get("hello").size());
        assertEquals("world", headers.getFirst("hello"));
        assertEquals(1, headers.get("foo").size());
        assertEquals("bar hid!", headers.getFirst("foo"));
    }

    @Test
    public void testPartial() throws InvalidRequestException {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        buf.put(Helper.makeByteBuffer("hello: wor"));
        buf.flip();

        HandlerState state = new HandlerState();
        HeadersBuilder headers = state.getRequestBuilder().getHeadersBuilder();

        RequestHeaderHandler handler = new RequestHeaderHandler();
        assertFalse(handler.handleState(null, buf, state));

        buf.put(Helper.makeByteBuffer("ld\r\n"));
        buf.flip();
        assertFalse(handler.handleState(null, buf, state));

        assertEquals(1, headers.get("hello").size());
        assertEquals("world", headers.getFirst("hello"));

        buf.clear();
        buf.put(Helper.makeByteBuffer("\n"));
        buf.flip();
        assertTrue(handler.handleState(null, buf, state));
    }
}
