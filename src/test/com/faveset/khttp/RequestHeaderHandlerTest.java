// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

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

        RequestHeaderHandler handler = new RequestHeaderHandler();
        assertFalse(handler.handleState(null, buf, state));

        assertEquals(1, state.getRequestBuilder().getHeader("hello").size());
        assertEquals("world", state.getRequestBuilder().getHeaderFirst("hello"));

        buf.clear();
        buf.put(Helper.makeByteBuffer("foo: bar\r\n"));
        buf.flip();
        assertFalse(handler.handleState(null, buf, state));
        assertEquals(1, state.getRequestBuilder().getHeader("foo").size());
        assertEquals("bar", state.getRequestBuilder().getHeaderFirst("foo"));

        buf.clear();
        buf.put(Helper.makeByteBuffer("\n"));
        buf.flip();
        assertTrue(handler.handleState(null, buf, state));
    }

    // TODO: test partial.

    @Test
    public void testContinuation() throws InvalidRequestException {
        ByteBuffer buf = Helper.makeByteBuffer("hello: world\n");
        HandlerState state = new HandlerState();

        RequestHeaderHandler handler = new RequestHeaderHandler();
        assertFalse(handler.handleState(null, buf, state));

        assertEquals(1, state.getRequestBuilder().getHeader("hello").size());
        assertEquals("world", state.getRequestBuilder().getHeaderFirst("hello"));

        buf.clear();
        buf.put(Helper.makeByteBuffer(" hi?\n"));
        buf.flip();
        assertFalse(handler.handleState(null, buf, state));

        assertEquals(1, state.getRequestBuilder().getHeader("hello").size());
        assertEquals("world hi?", state.getRequestBuilder().getHeaderFirst("hello"));

        buf.clear();
        buf.put(Helper.makeByteBuffer("\n"));
        buf.flip();
        assertTrue(handler.handleState(null, buf, state));
    }
}
