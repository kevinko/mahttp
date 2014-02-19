// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NonBlockingConnectionTest {
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

    @Test
    public void test() throws IOException, InterruptedException {
        Object signal = new Object();
        ServerThread server = new ServerThread(signal, new ServerThread.Task() {
            public void run(Socket sock) {
                try {
                    OutputStream os = sock.getOutputStream();

                    String data = "test";
                    os.write(data.getBytes(Helper.US_ASCII_CHARSET));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        server.start();

        synchronized (signal) {
            signal.wait();
        }

        final Selector selector = Selector.open();
        SocketChannel chan = connect(sListenPort);
        NonBlockingConnection conn = new NonBlockingConnection(selector, chan, 1024);
        SelectionKey connKey = conn.getSelectionKey();

        boolean done = false;

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
