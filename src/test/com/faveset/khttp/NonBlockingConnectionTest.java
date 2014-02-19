// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import static org.junit.Assert.assertEquals;

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
                ServerSocket listenSock = new ServerSocket(sListenPort);

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

        public Tester(ServerThread.Task serverTask, int bufferSize) {
            mServerTask = serverTask;
            mBufferSize = bufferSize;
        }

        protected abstract void prepareConn(NonBlockingConnection conn);

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
                    if (key.equals(connKey)) {
                        conn.onSelect();
                    }
                }
            }

            selector.close();

            server.join();
        }
    }

    @Test
    public void testRecv() throws IOException, InterruptedException {
        Tester tester = new Tester(new ServerThread.Task() {
            public void run(Socket sock) {
                try {
                    OutputStream os = sock.getOutputStream();

                    String data = "test";
                    os.write(data.getBytes(Helper.US_ASCII_CHARSET));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }, 1024) {
            @Override
            protected void prepareConn(NonBlockingConnection conn) {
                conn.recv(new NonBlockingConnection.OnRecvCallback() {
                    public void onRecv(NonBlockingConnection conn, ByteBuffer buf) {
                        try {
                            ByteBuffer cmpBuf = ByteBuffer.allocate(1024);

                            cmpBuf.put(buf);
                            cmpBuf.flip();
                            Helper.compare(cmpBuf, "test");

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

    private ServerThread.Task makeRecvTask(final String expectedStr) {
        return new ServerThread.Task() {
            public void run(Socket sock) {
                try {
                    InputStream is = sock.getInputStream();
                    byte[] data = new byte[expectedStr.length()];
                    assertEquals(expectedStr.length(), is.read(data));

                    String s = new String(data, sUsAsciiCharset);
                    assertEquals(expectedStr, s);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    @Test
    public void testSend() throws IOException, InterruptedException {
        Tester tester = new Tester(makeRecvTask("test"), 1024) {
            @Override
            protected void prepareConn(NonBlockingConnection conn) {
                ByteBuffer buf = conn.getOutBuffer();

                String s = "test";
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
}
