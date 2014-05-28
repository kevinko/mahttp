// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NonBlockingConnectionTest {
    private static final Charset sUsAsciiCharset = Charset.forName("US-ASCII");
    private static final int sListenPort = 4889;

    private abstract class Tester extends Helper.AsyncConnectionTester {
        public Tester(Helper.ServerThread.Task serverTask, int bufferSize) {
            super(sListenPort, serverTask, bufferSize);
        }

        @Override
        protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                SelectTaskQueue taskQueue) throws IOException {
            return new NonBlockingConnection(selector, chan, bufferSize);
        }
    }

    public static abstract class RecvLongTester extends Helper.AsyncConnectionTester {
        private int mRecvCount = 0;

        private String mExpectedStr;

        private ByteBuffer mCmpBuf;

        public RecvLongTester(Helper.ServerThread.Task serverTask, int bufferSize, String expectedStr) {
            super(sListenPort, serverTask, bufferSize);

            mExpectedStr = expectedStr;
            mCmpBuf = ByteBuffer.allocate(2 * expectedStr.length());
        }

        private AsyncConnection.OnRecvCallback makeRecvCallback() {
            return new AsyncConnection.OnRecvCallback() {
                public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                    handleRecv(conn, buf);
                }
            };
        }

        @Override
        protected void finish() {
            super.finish();

            assertTrue(mRecvCount > 1);
        }

        private void handleRecv(AsyncConnection conn, ByteBuffer buf) {
            mRecvCount++;

            try {
                mCmpBuf.put(buf);

                if (mCmpBuf.position() < mExpectedStr.length()) {
                    // Continue feeding.
                    conn.recv(makeRecvCallback());
                    return;
                }

                mCmpBuf.flip();
                Helper.compare(mCmpBuf, mExpectedStr);

                conn.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        protected void prepareConn(AsyncConnection conn) {
            conn.recv(makeRecvCallback());
        }
    }

    public static abstract class RecvSimpleTester extends Helper.AsyncConnectionTester {
        private String mExpectedStr;

        private int mRecvCount = 0;

        public RecvSimpleTester(Helper.ServerThread.Task serverTask, int bufferSize, String expectedStr) {
            super(sListenPort, serverTask, bufferSize);

            mExpectedStr = expectedStr;
        }

        @Override
        protected void finish() {
            super.finish();
            assertEquals(1, mRecvCount);
        }

        @Override
        protected void prepareConn(AsyncConnection conn) {
            conn.recv(new AsyncConnection.OnRecvCallback() {
                public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                    mRecvCount++;

                    try {
                        ByteBuffer cmpBuf = ByteBuffer.allocate(1024);

                        cmpBuf.put(buf);
                        cmpBuf.flip();
                        Helper.compare(cmpBuf, mExpectedStr);

                        conn.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
    }

    public static abstract class RecvPersistentTester extends Helper.AsyncConnectionTester {
        private String mExpectedStr;

        private int mRecvCount = 0;
        private ByteBuffer mCmpBuf;

        public RecvPersistentTester(Helper.ServerThread.Task serverTask, int bufferSize,
                String expectedStr) {
            super(sListenPort, serverTask, bufferSize);

            mExpectedStr = expectedStr;
            mCmpBuf = ByteBuffer.allocate(expectedStr.length());
        }

        @Override
        protected void finish() {
            super.finish();
            assertTrue(mRecvCount > 1);
        }

        @Override
        protected void prepareConn(AsyncConnection conn) {
            conn.recvPersistent(new AsyncConnection.OnRecvCallback() {
                public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                    mRecvCount++;

                    try {
                        mCmpBuf.put(buf);

                        if (mCmpBuf.position() < mExpectedStr.length()) {
                            // Continue feeding (persistent).
                            return;
                        }

                        mCmpBuf.flip();
                        Helper.compare(mCmpBuf, mExpectedStr);

                        conn.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
    }

    public static abstract class SendTester extends Helper.AsyncConnectionTester {
        private String mExpectedStr;

        public SendTester(Helper.ServerThread.Task serverTask, int bufferSize, String expectedStr) {
            super(sListenPort, serverTask, bufferSize);

            mExpectedStr = expectedStr;
        }

        @Override
        protected void prepareConn(AsyncConnection conn) {
            ByteBuffer buf = conn.getOutBuffer();
            buf.clear();

            String s = mExpectedStr;
            buf.put(s.getBytes(Helper.US_ASCII_CHARSET));

            buf.flip();

            conn.send(new AsyncConnection.OnSendCallback() {
                public void onSend(AsyncConnection conn) {
                    try {
                        conn.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
    }

    public static abstract class SendBufferTester extends Helper.AsyncConnectionTester {
        private String mExpectedStr;

        public SendBufferTester(Helper.ServerThread.Task serverTask, int bufferSize,
                String expectedStr) {
            super(sListenPort, serverTask, bufferSize);

            mExpectedStr = expectedStr;
        }

        @Override
        protected void prepareConn(AsyncConnection conn) {
            ByteBuffer src = Helper.makeByteBuffer(mExpectedStr);

            conn.send(new AsyncConnection.OnSendCallback() {
                public void onSend(AsyncConnection conn) {
                    try {
                        conn.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, src);
        }
    }

    @Test
    public void testRecvAppend() throws IOException, InterruptedException {
        // Pick something that fits within a single buffer.
        final String expectedStr = Helper.makeTestString(128);
        // Pick a smaller buffer that differs in size from the internal buffer.
        final ByteBuffer extBuf = ByteBuffer.allocate(17);
        // Test that onRecv does not clear the buf parameter.
        extBuf.put((byte) 'a');

        Tester tester = new Tester(makeSendTask(expectedStr), 1024) {
            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                conn.recvAppend(new AsyncConnection.OnRecvCallback() {
                    public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                        try {
                            assertEquals(extBuf.capacity(), buf.remaining());

                            ByteBuffer cmpBuf = ByteBuffer.allocate(1024);
                            cmpBuf.put(buf);
                            cmpBuf.flip();

                            assertTrue(!buf.hasRemaining());
                            Helper.compare(cmpBuf, "a" + expectedStr.substring(0, extBuf.capacity() - 1));

                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }, extBuf);
            }
        };

        tester.run();
    }

    @Test
    public void testRecvAppendPersistent() throws IOException, InterruptedException {
        // Pick something that fits within a single buffer.
        final String expectedStr = Helper.makeTestString(128);
        // Pick a smaller buffer that differs in size from the internal buffer.
        final ByteBuffer extBuf = ByteBuffer.allocate(17);
        // Test that onRecv does not clear the buf parameter.
        extBuf.put((byte) 'a');

        Tester tester = new Tester(makeSendTask(expectedStr), 1024) {
            private int mRecvCount = 0;
            private int mExpectedOffset = 0;

            private AsyncConnection.OnRecvCallback makeRecvCallback() {
                return new AsyncConnection.OnRecvCallback() {
                    public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                        try {
                            mRecvCount++;

                            // Make sure that buf is fully utilized.
                            assertEquals(extBuf.capacity(), buf.remaining());

                            ByteBuffer cmpBuf = ByteBuffer.allocate(1024);
                            cmpBuf.put(buf);
                            cmpBuf.flip();

                            assertTrue(!buf.hasRemaining());

                            // Skip the first char.
                            int len = extBuf.capacity() - 1;
                            String cmp = "a" + expectedStr.substring(mExpectedOffset, mExpectedOffset + len);

                            Helper.compare(cmpBuf, cmp);

                            mExpectedOffset += len;

                            if (mRecvCount == 1) {
                                // Manipulate the buffer so that it starts
                                // just after the first "a" for the next
                                // (persistent) callback.
                                buf.position(1);
                            } else {
                                conn.close();
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                };
            }

            @Override
            protected void finish() {
                super.finish();
                assertEquals(2, mRecvCount);
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                conn.recvAppendPersistent(makeRecvCallback(), extBuf);
            }
        };

        tester.run();
    }

    @Test
    public void testRecvSimple() throws IOException, InterruptedException {
        // Pick something that fits within a single buffer.
        final String expectedStr = Helper.makeTestString(128);

        RecvSimpleTester tester = new RecvSimpleTester(makeSendTask(expectedStr), 1024, expectedStr) {
            @Override
            protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                    SelectTaskQueue taskQueue) throws IOException {
                return new NonBlockingConnection(selector, chan, bufferSize);
            }
        };

        tester.run();
    }

    @Test
    public void testRecvLong() throws IOException, InterruptedException {
        // Pick something that exceeds a single buffer.
        final String expectedStr = Helper.makeTestString(4096);

        RecvLongTester tester = new RecvLongTester(makeSendTask(expectedStr), 1024, expectedStr) {
            @Override
            protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                    SelectTaskQueue taskQueue) throws IOException {
                return new NonBlockingConnection(selector, chan, bufferSize);
            }
        };

        tester.run();
    }

    private Helper.ServerThread.Task makeRecvTask(final String expectedStr) {
        return new Helper.ServerThread.Task() {
            public void run(Socket sock) {
                try {
                    InputStream is = sock.getInputStream();
                    byte[] data = new byte[expectedStr.length()];

                    int count = 0;
                    while (count < expectedStr.length()) {
                        int remLen = expectedStr.length() - count;
                        int len = is.read(data, count, remLen);
                        count += len;
                    }

                    String s = new String(data, sUsAsciiCharset);
                    assertEquals(expectedStr, s);

                    // Read one more byte, which should be connection close.
                    assertEquals(-1, is.read(data));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /**
     * This just receives until close.
     */
    private Helper.ServerThread.Task makeRecvSinkTask() {
        return new Helper.ServerThread.Task() {
            public void run(Socket sock) {
                try {
                    InputStream is = sock.getInputStream();
                    while (true) {
                        int ch = is.read();
                        if (ch == -1) {
                            break;
                        }
                    }

                    sock.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /**
     * Closes the connection after closeBytes is received.
     */
    private Helper.ServerThread.Task makeRecvCloseTask(final String expectedStr, final int closeBytes) {
        return new Helper.ServerThread.Task() {
            public void run(Socket sock) {
                int recvLen = closeBytes;
                if (recvLen > expectedStr.length()) {
                    recvLen = expectedStr.length();
                }

                try {
                    InputStream is = sock.getInputStream();
                    byte[] data = new byte[recvLen];

                    int count = 0;
                    while (count < recvLen) {
                        int remLen = recvLen - count;
                        int len = is.read(data, count, remLen);
                        count += len;
                    }

                    String s = new String(data, sUsAsciiCharset);
                    assertEquals(expectedStr.substring(0, recvLen), s);

                    // Now, force a connection close.
                    sock.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    @Test
    public void testRecvPersistent() throws IOException, InterruptedException {
        // Pick something that exceeds the NonBlockingConnection buffer.
        final String expectedStr = Helper.makeTestString(65535);

        RecvPersistentTester tester = new RecvPersistentTester(makeSendTask(expectedStr), 16,
                expectedStr) {
            @Override
            protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                    SelectTaskQueue taskQueue) throws IOException {
                return new NonBlockingConnection(selector, chan, bufferSize);
            }
        };

        tester.run();
    }

    private Helper.ServerThread.Task makeSendTask(final String testStr) {
        return new Helper.ServerThread.Task() {
            public void run(Socket sock) {
                try {
                    OutputStream os = sock.getOutputStream();

                    String data = testStr;
                    os.write(data.getBytes(Helper.US_ASCII_CHARSET));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    @Test
    public void testSend() throws IOException, InterruptedException {
        final String expectedStr = Helper.makeTestString(128);

        SendTester tester = new SendTester(makeRecvTask(expectedStr), 1024, expectedStr) {
            @Override
            protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                    SelectTaskQueue taskQueue) throws IOException {
                return new NonBlockingConnection(selector, chan, bufferSize);
            }
        };

        tester.run();
    }

    @Test
    public void testSendBuffer() throws IOException, InterruptedException {
        final String expectedStr = Helper.makeTestString(128);

        SendBufferTester tester = new SendBufferTester(makeRecvTask(expectedStr), 1024,
                expectedStr) {
            @Override
            protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                    SelectTaskQueue taskQueue) throws IOException {
                return new NonBlockingConnection(selector, chan, bufferSize);
            }
        };

        tester.run();
    }

    @Test
    public void testSendBuffers() throws IOException, InterruptedException {
        // Pick an number that is not divisible by a buffer size.
        final String expectedString = Helper.makeTestString(5001);
        final ByteBufferArrayBuilder builder = new ByteBufferArrayBuilder(16, true);
        builder.writeString(expectedString);

        final long remCount = builder.remaining();
        final ByteBuffer[] bufs = builder.build();

        Tester tester = new Tester(makeRecvTask(expectedString), 1024) {
            @Override
            protected void finish() {
                super.finish();

                builder.close();
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                conn.send(new AsyncConnection.OnSendCallback() {
                    public void onSend(AsyncConnection conn) {
                        try {
                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }, bufs, remCount);
            }
        };

        tester.run();
    }

    @Test
    public void testSendPartial() throws IOException, InterruptedException {
        int len = 1 << 20;
        final String expectedString = Helper.makeTestString(len - 1);
        final ByteBuffer buf = Helper.makeByteBuffer(expectedString);

        Tester tester = new Tester(makeRecvTask(expectedString), len) {
            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                ByteBuffer outBuf = conn.getOutBuffer();
                outBuf.put(buf);
                outBuf.flip();

                conn.sendPartial(new AsyncConnection.OnSendCallback() {
                    private int mSendCount = 0;

                    public void onSend(AsyncConnection conn) {
                        try {
                            mSendCount++;

                            if (!conn.getOutBuffer().hasRemaining()) {
                                // The partial callback should be called
                                // a couple of times, since the buffer size
                                // is large.
                                assertTrue(mSendCount > 1);

                                conn.close();
                            } else {
                                conn.sendPartial(this);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
        };

        tester.run();
    }

    // Test a close by the sender, which occurs after the Task's run method
    // completes.
    @Test
    public void testRecvClose() throws IOException, InterruptedException {
        final String expectedString = Helper.makeTestString(65535);
        final ByteBuffer buf = Helper.makeByteBuffer(expectedString);

        Tester tester = new Tester(makeSendTask(expectedString), 65536) {
            private int mCloseCount = 0;

            @Override
            protected void finish() {
                super.finish();

                assertEquals(1, mCloseCount);
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                conn.setOnCloseCallback(new AsyncConnection.OnCloseCallback() {
                    public void onClose(AsyncConnection conn) {
                        mCloseCount++;

                        try {
                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                conn.recvPersistent(new NonBlockingConnection.OnRecvCallback() {
                    public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                        // Just eat up the data.
                    }
                });
            }
        };

        tester.run();
    }

    // Expect some sort of write error because of a premature close while
    // sending.

    @Test
    public void testSendClose() throws IOException, InterruptedException {
        // Pick a large value that won't fit in a socket buffer.
        int bufLen = 1 << 20;
        final String expectedString = Helper.makeTestString(bufLen - 1);
        final ByteBuffer buf = Helper.makeByteBuffer(expectedString);

        Tester tester = new Tester(makeRecvCloseTask(expectedString, 1024), bufLen) {
            private int mCloseCount = 0;
            private int mErrorCount = 0;

            @Override
            protected void finish() {
                super.finish();

                // NOTE: closes are just for receives, so send closes
                // should not show up.
                assertEquals(0, mCloseCount);
                assertEquals(1, mErrorCount);
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                conn.setOnCloseCallback(new AsyncConnection.OnCloseCallback() {
                    public void onClose(AsyncConnection conn) {
                        mCloseCount++;

                        try {
                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                conn.setOnErrorCallback(new AsyncConnection.OnErrorCallback() {
                    public void onError(AsyncConnection conn, String reason) {
                        mErrorCount++;

                        try {
                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                ByteBuffer outBuf = conn.getOutBuffer();
                outBuf.put(buf);
                outBuf.flip();

                conn.send(new AsyncConnection.OnSendCallback() {
                    public void onSend(AsyncConnection conn) {
                        assertTrue(false);
                    }
                });
            }
        };

        tester.run();
    }

    // Make sure that a selector's prior ready state doesn't leak over to
    // the new ready set when manipulating interest states.  This catches the
    // following situation described in Selector docs:
    //
    //     Otherwise the channel's key is already in the selected-key set, so
    //     its ready-operation set is modified to identify any new operations
    //     for which the channel is reported to be ready. Any readiness
    //     information previously recorded in the ready set is preserved; in
    //     other words, the ready set returned by the underlying system is
    //     bitwise-disjoined into the key's current ready set.
    @Test
    public void testRecvSelectorSets() throws IOException, InterruptedException {
        final String expectedString = Helper.makeTestString(65535);
        final ByteBuffer buf = Helper.makeByteBuffer(expectedString);

        Tester tester = new Tester(makeSendTask(expectedString), 65536) {
            private int mCloseCount = 0;
            private int mRecvCount = 0;

            @Override
            protected void finish() {
                super.finish();

                assertEquals(0, mCloseCount);
                assertEquals(1, mRecvCount);
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                // This will hang if the implementation is correct, so set
                // a delay.
                delayedStop(1000);

                conn.setOnCloseCallback(new AsyncConnection.OnCloseCallback() {
                    public void onClose(AsyncConnection conn) {
                        mCloseCount++;

                        try {
                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                // This is intentionally not a persistent receive.  The
                // receive will be cancelled as a result and should not
                // be called again.
                conn.recv(new AsyncConnection.OnRecvCallback() {
                    public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                        mRecvCount++;
                    }
                });
            }
        };

        tester.run();
    }

    @Test
    public void testSendSelectorSets() throws IOException, InterruptedException {
        final String expectedString = Helper.makeTestString(65535);
        final ByteBuffer buf = Helper.makeByteBuffer(expectedString);

        Tester tester = new Tester(makeRecvSinkTask(), 65536) {
            private int mCloseCount = 0;
            private int mSendCount = 0;

            @Override
            protected void finish() {
                super.finish();

                assertEquals(0, mCloseCount);
                assertEquals(1, mSendCount);
            }

            @Override
            protected void onStop(AsyncConnection conn) {
                super.onStop(conn);
                try {
                    conn.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                // This will hang if the implementation is correct, so set
                // a delay.
                delayedStop(1000);

                conn.setOnCloseCallback(new AsyncConnection.OnCloseCallback() {
                    public void onClose(AsyncConnection conn) {
                        mCloseCount++;

                        try {
                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                int origLimit = buf.limit();
                buf.limit(1024);
                ByteBuffer outBuf = conn.getOutBuffer();
                outBuf.put(buf);
                outBuf.flip();
                buf.limit(origLimit);

                conn.send(new AsyncConnection.OnSendCallback() {
                    public void onSend(AsyncConnection conn) {
                        mSendCount++;

                        // Write just a portion of the expected string.  Since
                        // this is non-persistent, things will hang until
                        // the delayedStop kicks in.  If the selector handling
                        // is wrong, this will keep sending.
                    }
                });
            }
        };

        tester.run();
    }

    private static Helper.ServerThread.Task makeNullTask() {
        return new Helper.ServerThread.Task() {
            public void run(Socket sock) {
            }
        };
    }

    @Test(expected=IllegalArgumentException.class)
    public void testRecvNull() throws IOException, InterruptedException {
        Tester tester = new Tester(makeNullTask(), 1024) {
            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                conn.recv(null);
            }
        };
        tester.run();
    }

    @Test(expected=IllegalArgumentException.class)
    public void testRecvPersistentNull() throws IOException, InterruptedException {
        Tester tester = new Tester(makeNullTask(), 1024) {
            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                conn.recvPersistent(null);
            }
        };
        tester.run();
    }

    @Test(expected=IllegalArgumentException.class)
    public void testSendNull() throws IOException, InterruptedException {
        Tester tester = new Tester(makeNullTask(), 1024) {
            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                conn.send(null);
            }
        };
        tester.run();
    }

    @Test(expected=IllegalArgumentException.class)
    public void testSendNull2() throws IOException, InterruptedException {
        Tester tester = new Tester(makeNullTask(), 1024) {
            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                conn.send(null, null);
            }
        };
        tester.run();
    }

    @Test(expected=IllegalArgumentException.class)
    public void testSendNull3() throws IOException, InterruptedException {
        Tester tester = new Tester(makeNullTask(), 1024) {
            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                conn.send(null, null, 0);
            }
        };
        tester.run();
    }

    @Test(expected=IllegalArgumentException.class)
    public void testSendPartialNull() throws IOException, InterruptedException {
        Tester tester = new Tester(makeNullTask(), 1024) {
            @Override
            protected void prepareConn(AsyncConnection connArg) {
                NonBlockingConnection conn = (NonBlockingConnection) connArg;

                conn.sendPartial(null);
            }
        };
        tester.run();
    }
}
