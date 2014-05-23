// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Set;

import static org.junit.Assert.assertEquals;

class Helper {
    public static abstract class AsyncConnectionTester {
        // Time to force a stop during testing.  0 to disable.
        private long mStopTime;

        private Helper.ServerThread.Task mServerTask;
        private int mBufferSize;
        private int mListenPort;

        /**
         * @param bufferSize will be passed to the NonBlockingConnection.
         */
        protected AsyncConnectionTester(int listenPort,
                Helper.ServerThread.Task serverTask, int bufferSize) {
            mListenPort = listenPort;
            mServerTask = serverTask;
            mBufferSize = bufferSize;
        }

        protected abstract AsyncConnection makeConn(Selector selector, SocketChannel chan,
                int bufferSize, SelectTaskQueue taskQueue) throws IOException;

        protected abstract void prepareConn(AsyncConnection conn);

        protected void finish() {}

        protected void onStop(AsyncConnection conn) {}

        public void run() throws IOException, InterruptedException {
            Object signal = new Object();
            Helper.ServerThread server = new Helper.ServerThread(mListenPort, signal, mServerTask);
            server.start();

            synchronized (signal) {
                signal.wait();
            }

            final Selector selector = Selector.open();
            SelectTaskQueue taskQueue = new SelectTaskQueue(selector);

            SocketChannel chan = Helper.connect(mListenPort);
            AsyncConnection conn = makeConn(selector, chan, mBufferSize, taskQueue);

            prepareConn(conn);

            while (true) {
                // We busy wait here, since we want to stop as soon as all keys
                // are cancelled.
                selector.selectNow();
                if (selector.keys().size() == 0) {
                    if (!runTaskQueue(taskQueue)) {
                        // No tasks, either.  We're done.
                        break;
                    }
                } else {
                    runTaskQueue(taskQueue);
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

        private static boolean runTaskQueue(SelectTaskQueue queue) {
            boolean hasTask = false;
            do {
                Runnable task = queue.poll();
                if (task == null) {
                    return hasTask;
                }

                hasTask = true;
                task.run();
            } while (true);
        }

        /**
         * Stop the test after millis milliseconds from now.
         */
        public void delayedStop(int millis) {
            long now = System.currentTimeMillis();
            mStopTime = now + millis;
        }
    }

    public static class ServerThread extends Thread {
        public interface Task {
            void run(Socket sock);
        }

        private int mListenPort;
        private Object mSignal;
        private Task mTask;

        public ServerThread(int listenPort, Object signal, Task task) {
            mListenPort = listenPort;
            mSignal = signal;
            mTask = task;
        }

        public void run() {
            try {
                ServerSocket listenSock = new ServerSocket();
                listenSock.setReuseAddress(true);
                SocketAddress sa = new InetSocketAddress("127.0.0.1", mListenPort);
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

    public static final Charset US_ASCII_CHARSET = Charset.forName("US-ASCII");

    public static void compare(ByteBuffer buf, String v) {
        String dataStr = new String(buf.array(), buf.position(), buf.remaining(), US_ASCII_CHARSET);
        assertEquals(v, dataStr);
    }

    public static SocketChannel connect(int port) throws IOException {
        SocketAddress sa = new InetSocketAddress("127.0.0.1", port);
        return SocketChannel.open(sa);
    }

    public static ByteBuffer makeByteBuffer(String s) {
        return ByteBuffer.wrap(s.getBytes(US_ASCII_CHARSET));
    }

    public static String makeTestString(int size) {
        StringBuilder builder = new StringBuilder();
        for (int ii = 0; ii < size; ii++) {
            char ch = (char) ((byte) 'a' + (ii % 26));
            builder.append(ch);
        }
        return builder.toString();
    }

    /**
     * Reads until a '\n', and returns the line as a string, with '\n'
     * included.
     *
     * Returns the empty string if EOF is reached.
     */
    public static String readLine(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        while (true) {
            int v = input.read();
            if (v == -1) {
                break;
            }

            char ch = (char) v;
            builder.append(ch);

            if (ch == '\n') {
                break;
            }
        }
        return builder.toString();
    }
}
