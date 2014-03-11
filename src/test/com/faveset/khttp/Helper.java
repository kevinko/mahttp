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
import java.nio.channels.SocketChannel;

import static org.junit.Assert.assertEquals;

class Helper {
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
