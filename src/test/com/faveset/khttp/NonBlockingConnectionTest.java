// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NonBlockingConnectionTest {
    private static final Charset sUsAsciiCharset = Charset.forName("US-ASCII");
    private static final int sListenPort = 4889;

    private abstract class Tester {
        // Time to force a stop during testing.  0 to disable.
        private long mStopTime;

        private Helper.ServerThread.Task mServerTask;
        private int mBufferSize;

        /**
         * @param bufferSize will be passed to the NonBlockingConnection.
         */
        public Tester(Helper.ServerThread.Task serverTask, int bufferSize) {
            mServerTask = serverTask;
            mBufferSize = bufferSize;
        }

        protected abstract void prepareConn(NonBlockingConnection conn);

        protected void finish() {}

        protected void onStop(NonBlockingConnection conn) {
        }

        public void run() throws IOException, InterruptedException {
            Object signal = new Object();
            Helper.ServerThread server = new Helper.ServerThread(sListenPort, signal, mServerTask);
            server.start();

            synchronized (signal) {
                signal.wait();
            }

            final Selector selector = Selector.open();
            SocketChannel chan = Helper.connect(sListenPort);
            NonBlockingConnection conn = new NonBlockingConnection(selector, chan, mBufferSize);

            prepareConn(conn);

            SelectionKey connKey = conn.getSelectionKey();
            while (true) {
                // We busy wait here, since we want to stop as soon as all keys
                // are cancelled.
                selector.selectNow();
                if (selector.keys().size() == 0) {
                    break;
                }
                if (mStopTime != 0 && mStopTime < System.currentTimeMillis()) {
                    onStop(conn);
                    break;
                }

                Set<SelectionKey> readyKeys = selector.selectedKeys();

                for (SelectionKey key : readyKeys) {
                    SelectorHandler handler = (SelectorHandler) key.attachment();
                    handler.onReady(key);
                }
            }

            selector.close();

            server.join();

            finish();
        }

        /**
         * Stop the test after millis milliseconds from now.
         */
        public void delayedStop(int millis) {
            long now = System.currentTimeMillis();
            mStopTime = now + millis;
        }
    }

    @Test
    public void testRecvSimple() throws IOException, InterruptedException {
        // Pick something that fits within a single buffer.
        final String expectedStr = makeTestString(128);

        Tester tester = new Tester(makeSendTask(expectedStr), 1024) {
            @Override
            protected void prepareConn(NonBlockingConnection conn) {
                conn.recv(new NonBlockingConnection.OnRecvCallback() {
                    public void onRecv(NonBlockingConnection conn, ByteBuffer buf) {
                        try {
                            ByteBuffer cmpBuf = ByteBuffer.allocate(1024);

                            cmpBuf.put(buf);
                            cmpBuf.flip();
                            Helper.compare(cmpBuf, expectedStr);

                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
        };

        tester.run();
    }

    @Test
    public void testRecvLong() throws IOException, InterruptedException {
        // Pick something that exceeds a single buffer.
        final String expectedStr = makeTestString(4096);

        Tester tester = new Tester(makeSendTask(expectedStr), 1024) {
            private int mRecvCount = 0;
            private ByteBuffer mCmpBuf = ByteBuffer.allocate(2 * expectedStr.length());

            private NonBlockingConnection.OnRecvCallback makeRecvCallback() {
                return new NonBlockingConnection.OnRecvCallback() {
                    public void onRecv(NonBlockingConnection conn, ByteBuffer buf) {
                        handleRecv(conn, buf);
                    }
                };
            }

            @Override
            protected void finish() {
                super.finish();

                assertTrue(mRecvCount > 1);
            }

            private void handleRecv(NonBlockingConnection conn, ByteBuffer buf) {
                mRecvCount++;

                try {
                    mCmpBuf.put(buf);

                    if (mCmpBuf.position() < expectedStr.length()) {
                        // Continue feeding.
                        conn.recv(makeRecvCallback());
                        return;
                    }

                    mCmpBuf.flip();
                    Helper.compare(mCmpBuf, expectedStr);

                    conn.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected void prepareConn(NonBlockingConnection conn) {
                conn.recv(makeRecvCallback());
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
        final String expectedStr = makeTestString(4096);

        Tester tester = new Tester(makeSendTask(expectedStr), 16) {
            private int mRecvCount = 0;
            private ByteBuffer mCmpBuf = ByteBuffer.allocate(2 * expectedStr.length());

            private NonBlockingConnection.OnRecvCallback makeRecvCallback() {
                return new NonBlockingConnection.OnRecvCallback() {
                    public void onRecv(NonBlockingConnection conn, ByteBuffer buf) {
                        handleRecv(conn, buf);
                    }
                };
            }

            @Override
            protected void finish() {
                super.finish();

                assertTrue(mRecvCount > 1);
            }

            private void handleRecv(NonBlockingConnection conn, ByteBuffer buf) {
                mRecvCount++;

                try {
                    mCmpBuf.put(buf);

                    if (mCmpBuf.position() < expectedStr.length()) {
                        // Continue feeding (persistent).
                        return;
                    }

                    mCmpBuf.flip();
                    Helper.compare(mCmpBuf, expectedStr);

                    conn.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected void prepareConn(NonBlockingConnection conn) {
                conn.recvPersistent(makeRecvCallback());
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

    private String makeTestString(int size) {
        StringBuilder builder = new StringBuilder();
        for (int ii = 0; ii < size; ii++) {
            char ch = (char) ((byte) 'a' + (ii % 26));
            builder.append(ch);
        }
        return builder.toString();
    }

    @Test
    public void testSend() throws IOException, InterruptedException {
        final String expectedString = makeTestString(128);

        Tester tester = new Tester(makeRecvTask(expectedString), 1024) {
            @Override
            protected void prepareConn(NonBlockingConnection conn) {
                ByteBuffer buf = conn.getOutBuffer();

                String s = expectedString;
                buf.put(s.getBytes(Helper.US_ASCII_CHARSET));

                buf.flip();

                conn.send(new NonBlockingConnection.OnSendCallback() {
                    public void onSend(NonBlockingConnection conn) {
                        try {
                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
        };

        tester.run();
    }

    @Test
    public void testSendBuffer() throws IOException, InterruptedException {
        final String expectedString = makeTestString(128);

        Tester tester = new Tester(makeRecvTask(expectedString), 1024) {
            @Override
            protected void prepareConn(NonBlockingConnection conn) {
                ByteBuffer src = Helper.makeByteBuffer(expectedString);

                conn.send(new NonBlockingConnection.OnSendCallback() {
                    public void onSend(NonBlockingConnection conn) {
                        try {
                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }, src);
            }
        };

        tester.run();
    }

    @Test
    public void testSendBuffers() throws IOException, InterruptedException {
        // Pick an number that is not divisible by a buffer size.
        final String expectedString = makeTestString(5001);
        ByteBufferArrayBuilder pool = new ByteBufferArrayBuilder(16, true);
        pool.writeString(expectedString);

        final long remCount = pool.remaining();
        final ByteBuffer[] bufs = pool.build();

        Tester tester = new Tester(makeRecvTask(expectedString), 1024) {
            @Override
            protected void prepareConn(NonBlockingConnection conn) {
                conn.send(new NonBlockingConnection.OnSendCallback() {
                    public void onSend(NonBlockingConnection conn) {
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
        final String expectedString = makeTestString(len - 1);
        final ByteBuffer buf = Helper.makeByteBuffer(expectedString);

        Tester tester = new Tester(makeRecvTask(expectedString), len) {
            @Override
            protected void prepareConn(NonBlockingConnection conn) {
                ByteBuffer outBuf = conn.getOutBuffer();
                outBuf.put(buf);
                outBuf.flip();

                conn.sendPartial(new NonBlockingConnection.OnSendCallback() {
                    private int mSendCount = 0;

                    public void onSend(NonBlockingConnection conn) {
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
        final String expectedString = makeTestString(65535);
        final ByteBuffer buf = Helper.makeByteBuffer(expectedString);

        Tester tester = new Tester(makeSendTask(expectedString), 65536) {
            private int mCloseCount = 0;

            @Override
            protected void finish() {
                super.finish();

                assertEquals(1, mCloseCount);
            }

            @Override
            protected void prepareConn(NonBlockingConnection conn) {
                conn.setOnCloseCallback(new NonBlockingConnection.OnCloseCallback() {
                    public void onClose(NonBlockingConnection conn) {
                        mCloseCount++;

                        try {
                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                conn.recvPersistent(new NonBlockingConnection.OnRecvCallback() {
                    public void onRecv(NonBlockingConnection conn, ByteBuffer buf) {
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
        final String expectedString = makeTestString(bufLen - 1);
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
            protected void prepareConn(NonBlockingConnection conn) {
                conn.setOnCloseCallback(new NonBlockingConnection.OnCloseCallback() {
                    public void onClose(NonBlockingConnection conn) {
                        mCloseCount++;

                        try {
                            conn.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                conn.setOnErrorCallback(new NonBlockingConnection.OnErrorCallback() {
                    public void onError(NonBlockingConnection conn, String reason) {
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

                conn.send(new NonBlockingConnection.OnSendCallback() {
                    public void onSend(NonBlockingConnection conn) {
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
        final String expectedString = makeTestString(65535);
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
            protected void prepareConn(NonBlockingConnection conn) {
                // This will hang if the implementation is correct, so set
                // a delay.
                delayedStop(1000);

                conn.setOnCloseCallback(new NonBlockingConnection.OnCloseCallback() {
                    public void onClose(NonBlockingConnection conn) {
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
                conn.recv(new NonBlockingConnection.OnRecvCallback() {
                    public void onRecv(NonBlockingConnection conn, ByteBuffer buf) {
                        mRecvCount++;
                    }
                });
            }
        };

        tester.run();
    }

    @Test
    public void testSendSelectorSets() throws IOException, InterruptedException {
        final String expectedString = makeTestString(65535);
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
            protected void onStop(NonBlockingConnection conn) {
                super.onStop(conn);
                try {
                    conn.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected void prepareConn(NonBlockingConnection conn) {
                // This will hang if the implementation is correct, so set
                // a delay.
                delayedStop(1000);

                conn.setOnCloseCallback(new NonBlockingConnection.OnCloseCallback() {
                    public void onClose(NonBlockingConnection conn) {
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

                conn.send(new NonBlockingConnection.OnSendCallback() {
                    public void onSend(NonBlockingConnection conn) {
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
}
