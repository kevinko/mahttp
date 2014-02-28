// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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

    private static class ServerThread extends Thread {
        public interface Task {
            void run(Socket sock);
        }

        private Object mSignal;
        private Task mTask;

        public ServerThread(Object signal, Task task) {
            mSignal = signal;
            mTask = task;
        }

        public void run() {
            try {
                ServerSocket listenSock = new ServerSocket();
                listenSock.setReuseAddress(true);
                SocketAddress sa = new InetSocketAddress("127.0.0.1", sListenPort);
                listenSock.bind(sa);

                synchronized (mSignal) {
                    mSignal.notify();
                }

                Socket sock = listenSock.accept();

                mTask.run(sock);

                sock.close();
                listenSock.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private SocketChannel connect(int port) throws IOException {
        SocketAddress sa = new InetSocketAddress("127.0.0.1", port);
        return SocketChannel.open(sa);
    }

    private abstract class Tester {
        private ServerThread.Task mServerTask;
        private int mBufferSize;

        /**
         * @param bufferSize will be passed to the NonBlockingConnection.
         */
        public Tester(ServerThread.Task serverTask, int bufferSize) {
            mServerTask = serverTask;
            mBufferSize = bufferSize;
        }

        protected abstract void prepareConn(NonBlockingConnection conn);

        protected void finish() {}

        public void run() throws IOException, InterruptedException {
            Object signal = new Object();
            ServerThread server = new ServerThread(signal, mServerTask);
            server.start();

            synchronized (signal) {
                signal.wait();
            }

            final Selector selector = Selector.open();
            SocketChannel chan = connect(sListenPort);
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

                Set<SelectionKey> readyKeys = selector.keys();

                for (SelectionKey key : readyKeys) {
                    SelectorHandler handler = (SelectorHandler) key.attachment();
                    handler.onReady(key);
                }
            }

            selector.close();

            server.join();

            finish();
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

    private ServerThread.Task makeRecvTask(final String expectedStr) {
        return new ServerThread.Task() {
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
     * Closes the connection after closeBytes is received.
     */
    private ServerThread.Task makeRecvCloseTask(final String expectedStr, final int closeBytes) {
        return new ServerThread.Task() {
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

    private ServerThread.Task makeSendTask(final String testStr) {
        return new ServerThread.Task() {
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
        ByteBufferPool pool = new ByteBufferPool(16, true);
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

                conn.recv(new NonBlockingConnection.OnRecvCallback() {
                    public void onRecv(NonBlockingConnection conn, ByteBuffer buf) {
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
}
