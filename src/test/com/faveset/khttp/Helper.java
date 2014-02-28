// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

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

    public static ByteBuffer makeByteBuffer(String s) {
        return ByteBuffer.wrap(s.getBytes(US_ASCII_CHARSET));
    }
}
