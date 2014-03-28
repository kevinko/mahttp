// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ByteBufferArrayTest {
    private static ByteBuffer[] allocate(int count, int size) {
        ByteBuffer[] bufs = new ByteBuffer[count];
        for (int ii = 0; ii < bufs.length; ii++) {
            bufs[ii] = ByteBuffer.allocateDirect(size);
        }
        return bufs;
    }

    @Test
    public void test() {
        ByteBuffer[] bufs = new ByteBuffer[4];
        bufs[0] = Helper.makeByteBuffer("one");
        bufs[1] = Helper.makeByteBuffer("two");
        bufs[2] = Helper.makeByteBuffer("three");
        bufs[3] = Helper.makeByteBuffer("four");

        ByteBufferArray bufArray = new ByteBufferArray(bufs);
        assertEquals(0, bufArray.getNonEmptyOffset());
        assertEquals(15, bufArray.remaining());

        bufs[0].position(2);
        bufArray.update();
        assertEquals(0, bufArray.getNonEmptyOffset());
        assertEquals(13, bufArray.remaining());

        bufs[0].position(bufs[0].limit());
        bufArray.update();
        assertEquals(1, bufArray.getNonEmptyOffset());
        assertEquals(12, bufArray.remaining());

        bufs[1].position(bufs[1].limit());
        bufArray.update();
        assertEquals(2, bufArray.getNonEmptyOffset());
        assertEquals(9, bufArray.remaining());

        bufs[0].position(0);
        bufArray.reset();
        assertEquals(0, bufArray.getNonEmptyOffset());
        assertEquals(12, bufArray.remaining());
    }
}
