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
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.text.SimpleDateFormat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import com.faveset.log.Log;
import com.faveset.log.NullLog;

@RunWith(JUnit4.class)
public class HttpConnectionTest {
    private static final int sListenPort = 8123;

    private static SimpleDateFormat sHttpDateFormat =
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");

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

    private static class ExpectedHeader {
        private boolean mSeen;
        private String mKey;
        private String mValue;

        public ExpectedHeader(String key, String value) {
            mKey = key;
            mValue = value;
        }

        public String getKey() {
            return mKey;
        }

        public String getValue() {
            return mValue;
        }

        /**
         * @return true if seen for the first time.
         */
        public boolean setSeen() {
            boolean newlySeen = !mSeen;

            mSeen = true;

            return newlySeen;
        }

        /**
         * @return true if the key-value matches the header line in s.
         */
        public boolean match(String s) {
            String[] keyValue = parseKeyValue(s);
            if (keyValue == null) {
                return false;
            }

            String key = keyValue[0];
            String value = keyValue[1];

            if (!mKey.equals(key)) {
                return false;
            }
            if (!mValue.equals(value)) {
                return false;
            }

            // Check for a trailing CR-LF.
            return (s.endsWith("\n") || s.endsWith("\r\n"));
        }

        /**
         * @returns null if a key-value is not detected.
         */
        public static String[] parseKeyValue(String s) {
            int splitIndex = s.indexOf(':');
            if (splitIndex == -1) {
                return null;
            }
            String key = s.substring(0, splitIndex).trim();
            String value = s.substring(splitIndex + 1).trim();
            return new String[]{key, value};
        }
    }

    private static class ExpectedDateHeader extends ExpectedHeader {
        private Date mDate;

        public ExpectedDateHeader(Date date) {
            super("Date", null);
            mDate = date;
        }

        /**
         * We handle date header values by expecting +/- a second from the
         * given time.
         */
        @Override
        public boolean match(String s) {
            String[] keyValue = parseKeyValue(s);
            if (keyValue == null) {
                return false;
            }

            String key = keyValue[0];
            String value = keyValue[1];

            if (!getKey().equals(key)) {
                return false;
            }

            // +/- one second.
            Date prevDate = new Date(mDate.getTime() - 1000);
            Date nextDate = new Date(mDate.getTime() + 1000);

            Date[] dates = new Date[]{prevDate, mDate, nextDate};
            boolean success = false;
            for (int ii = 0; ii < dates.length; ii++) {
                String dateStr = sHttpDateFormat.format(dates[ii]);
                if (dateStr.equals(value)) {
                    success = true;
                    break;
                }
            }
            if (!success) {
                return false;
            }

            // Check for a trailing CR-LF.
            return (s.endsWith("\n") || s.endsWith("\r\n"));
        }
    }

    private static void checkHeaders(InputStream is,
            ExpectedHeader[] expectedHeaders) throws IOException {
        Map<String, ExpectedHeader> expectMap = new HashMap<String, ExpectedHeader>(expectedHeaders.length);
        for (int ii = 0; ii < expectedHeaders.length; ii++) {
            ExpectedHeader h = expectedHeaders[ii];
            expectMap.put(h.getKey(), h);
        }

        int count = 0;
        do {
            String line = Helper.readLine(is);
            String[] keyValue = ExpectedHeader.parseKeyValue(line);
            assertTrue(keyValue != null);

            ExpectedHeader h = expectMap.get(keyValue[0]);
            assertTrue(h != null);

            if (h.match(line)) {
                if (h.setSeen()) {
                    count++;
                }
            }
        } while (count < expectMap.size());
    }

    private void checkEmpty(InputStream is) throws IOException {
        checkHeaders(is, new ExpectedHeader[]{
            new ExpectedHeader("Content-Length", "0"),
            new ExpectedDateHeader(new Date()),
        });
    }

    /**
     * Checks for Connection close and Content-length 0 headers.
     * Loops until the headers are found.
     */
    private void checkEmptyConnectionClose(InputStream is) throws IOException {
        checkHeaders(is, new ExpectedHeader[]{
            new ExpectedHeader("Connection", "close"),
            new ExpectedHeader("Content-Length", "0"),
            new ExpectedDateHeader(new Date()),
        });
    }

    private Tester makeSimpleTester(Helper.ServerThread.Task task) {
        return new Tester(task) {
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
    }

    @Test
    public void test() throws IOException, InterruptedException {
        Tester tester = makeSimpleTester(new Helper.ServerThread.Task() {
            public void run(Socket sock) {
                try {
                    OutputStream os = sock.getOutputStream();
                    PrintWriter w = new PrintWriter(os);
                    w.print("GET / HTTP/1.1\r\n");
                    w.print("\r\n");
                    w.flush();

                    InputStream is = sock.getInputStream();
                    String line = Helper.readLine(is);
                    assertEquals("HTTP/1.1 404, Not Found\r\n", line);
                    checkEmpty(is);
                    line = Helper.readLine(is);
                    assertEquals("\r\n", line);

                    // Try reading once more, since connections are persistent
                    // with HTTP/1.1.
                    w.print("GET / HTTP/1.1\r\n");
                    w.print("\r\n");
                    w.flush();

                    line = Helper.readLine(is);
                    assertEquals("HTTP/1.1 404, Not Found\r\n", line);
                    checkEmpty(is);
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
                    checkEmpty(is);
                    line = Helper.readLine(is);
                    assertEquals("\r\n", line);

                    sock.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        tester.run();
    }

    @Test
    public void testClose() throws IOException, InterruptedException {
        Tester tester = makeSimpleTester(new Helper.ServerThread.Task() {
            public void run(Socket sock) {
                try {
                    OutputStream os = sock.getOutputStream();
                    PrintWriter w = new PrintWriter(os);
                    w.print("GET / HTTP/1.1\r\n");
                    w.print("connection:    close   \r\n");
                    w.print("\r\n");
                    w.flush();

                    InputStream is = sock.getInputStream();
                    String line = Helper.readLine(is);
                    assertEquals("HTTP/1.1 404, Not Found\r\n", line);

                    checkEmptyConnectionClose(is);

                    line = Helper.readLine(is);
                    assertEquals("\r\n", line);

                    // Try reading once more, which should signify a
                    // connection close.
                    line = Helper.readLine(is);
                    assertEquals("", line);

                    sock.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        tester.run();
    }

    @Test
    public void testLegacyClose() throws IOException, InterruptedException {
        Tester tester = makeSimpleTester(new Helper.ServerThread.Task() {
            public void run(Socket sock) {
                try {
                    OutputStream os = sock.getOutputStream();
                    PrintWriter w = new PrintWriter(os);
                    w.print("GET / HTTP/1.0\r\n");
                    w.print("Connection:   Keep-Alive\r\n");
                    w.print("\r\n");
                    w.flush();

                    InputStream is = sock.getInputStream();
                    String line = Helper.readLine(is);
                    assertEquals("HTTP/1.0 404, Not Found\r\n", line);
                    checkHeaders(is, new ExpectedHeader[]{
                        new ExpectedHeader("Content-Length", "0"),
                        new ExpectedHeader("Connection", "Keep-Alive"),
                        new ExpectedDateHeader(new Date()),
                    });
                    line = Helper.readLine(is);
                    assertEquals("\r\n", line);

                    w.print("GET / HTTP/1.0\r\n");
                    w.print("\r\n");
                    w.flush();

                    line = Helper.readLine(is);
                    assertEquals("HTTP/1.0 404, Not Found\r\n", line);

                    checkEmptyConnectionClose(is);

                    line = Helper.readLine(is);
                    assertEquals("\r\n", line);

                    // Try reading once more, which should signify a
                    // connection close.
                    line = Helper.readLine(is);
                    assertEquals("", line);

                    sock.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        tester.run();
    }
}
