// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import static org.junit.Assert.assertEquals;

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
}
