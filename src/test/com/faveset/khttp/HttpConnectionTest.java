// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import com.faveset.log.Log;
import com.faveset.log.NullLog;

@RunWith(JUnit4.class)
public class HttpConnectionTest {
    private static final int sListenPort = 8123;

    private abstract class Tester {
        private Helper.ServerThread.Task mServerTask;
        private Log mLog = new NullLog();

        public Tester(Helper.ServerThread.Task serverTask) {
            mServerTask = serverTask;
        }

        protected abstract void prepareConn(HttpConnection conn);

        protected void finish() {}

        public void run() throws IOException, InterruptedException {
            Object signal = new Object();
            Helper.ServerThread server = new Helper.ServerThread(sListenPort, signal, mServerTask);
            server.start();

            synchronized (signal) {
                signal.wait();
            }

            final Selector selector = Selector.open();

            SocketChannel chan = Helper.connect(sListenPort);
            HttpConnection conn = new HttpConnection(selector, chan);
            conn.setLog(mLog);

            prepareConn(conn);

            SelectionKey connKey = conn.getNonBlockingConnection().getSelectionKey();
            while (true) {
                // We busy wait here, since we want to stop as soon as all keys
                // are cancelled.
                selector.selectNow();
                if (selector.keys().size() == 0) {
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
    }

    @Test
    public void test() throws IOException, InterruptedException {
        Tester tester = new Tester(new Helper.ServerThread.Task() {
            public void run(Socket sock) {
                try {
                    OutputStream os = sock.getOutputStream();
                    PrintWriter w = new PrintWriter(os);
                    w.print("GET / HTTP/1.0\r\n");
                    w.print("\r\n");
                    w.flush();

                    InputStream is = sock.getInputStream();
                    String line = Helper.readLine(is);
                    assertEquals("HTTP/1.0 404, Not Found\r\n", line);
                    line = Helper.readLine(is);
                    assertEquals("Content-Length: 0\r\n", line);
                    line = Helper.readLine(is);
                    assertEquals("\r\n", line);

                    // Try reading once more, since connections are pipelined.
                    w.print("GET / HTTP/1.1\r\n");
                    w.print("\r\n");
                    w.flush();

                    line = Helper.readLine(is);
                    assertEquals("HTTP/1.1 404, Not Found\r\n", line);

                    line = Helper.readLine(is);
                    assertEquals("Content-Length: 0\r\n", line);

                    line = Helper.readLine(is);
                    assertEquals("\r\n", line);

                    // Try with headers.
                    w.print("GET / HTTP/1.1\r\n");
                    w.print("User-Agent: curl/7.22.0 (x86_64-pc-linux-gnu) libcurl/7.22.0 OpenSSL/1.0.1 zlib/1.2.3.4 libidn/1.23 librtmp/2.3\r\n");
                    w.print("Host: localhost:5123\r\n");
                    w.print("Accept: */*\r\n");
                    w.print("\r\n");
                    w.flush();

                    line = Helper.readLine(is);
                    assertEquals("HTTP/1.1 404, Not Found\r\n", line);
                    line = Helper.readLine(is);
                    assertEquals("Content-Length: 0\r\n", line);
                    line = Helper.readLine(is);
                    assertEquals("\r\n", line);

                    sock.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }) {
            private void handleClose(HttpConnection conn) {
                try {
                    conn.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected void prepareConn(HttpConnection conn) {
                conn.setOnCloseCallback(new HttpConnection.OnCloseCallback() {
                    public void onClose(HttpConnection conn) {
                        handleClose(conn);
                    }
                });

                Map<String, HttpHandler> handlers = new HashMap<String, HttpHandler>();
                conn.start(handlers);
            }
        };
        tester.run();
    }
}
