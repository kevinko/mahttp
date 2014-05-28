// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.security.cert.CertificateException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import com.faveset.log.Log;
import com.faveset.log.OutputStreamLog;

@RunWith(JUnit4.class)
public class SSLNonBlockingConnectionTest {
    private static final Charset sUsAsciiCharset = Charset.forName("US-ASCII");

    private static final String sHostName = "localhost";
    private static final int sListenPort = 4889;

    private static final String sKeyStoreFile = "keystore.jks";
    private static final String sTrustStoreFile = "truststore.jks";
    private static final String sPassword = "password";

    private abstract class Tester extends Helper.AsyncConnectionTester {
        private SSLContext mCtx;

        public Tester(SSLContext ctx, Helper.ServerThread.Task serverTask, int bufferSize) {
            super(sListenPort, serverTask, bufferSize);

            mCtx = ctx;
        }

        @Override
        protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                SelectTaskQueue taskQueue) throws IOException {
            try {
                return new SSLNonBlockingConnection(selector, chan, new HeapByteBufferFactory(), taskQueue, mCtx);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Closes the connection after closeBytes is received.
     */
    private Helper.ServerThread.Task makeRecvCloseTask(final SSLSocketFactory factory,
            final String expectedStr, final int closeBytes) {
        return new Helper.ServerThread.Task() {
            public void run(Socket sockArg) {
                int recvLen = closeBytes;
                if (recvLen > expectedStr.length()) {
                    recvLen = expectedStr.length();
                }

                try {
                    SSLSocket sock = (SSLSocket) factory.createSocket(sockArg, sHostName, sListenPort, true);
                    sock.setUseClientMode(true);

                    sock.startHandshake();

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

    private Helper.ServerThread.Task makeRecvTask(final SSLSocketFactory factory, final String expectedStr) {
        return new Helper.ServerThread.Task() {
            public void run(Socket sockArg) {
                try {
                    SSLSocket sock = (SSLSocket) factory.createSocket(sockArg, sHostName, sListenPort, true);
                    sock.setUseClientMode(true);

                    sock.startHandshake();

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

    private Helper.ServerThread.Task makeSendTask(final SSLSocketFactory factory, final String testStr) {
        return new Helper.ServerThread.Task() {
            public void run(Socket sockArg) {
                try {
                    SSLSocket sock = (SSLSocket) factory.createSocket(sockArg, sHostName, sListenPort, true);
                    sock.setUseClientMode(true);

                    sock.startHandshake();

                    OutputStream os = sock.getOutputStream();

                    String data = testStr;
                    os.write(data.getBytes(Helper.US_ASCII_CHARSET));

                    // NOTE: this can lead to a close_notify error, since the underlying sockArg
                    // will be closed by the ServerThread.  You can get around this by explicitly
                    // calling sock.close() (i.e., on the SSLSocket), which will handshake properly.
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private SSLContext makeContext() throws CertificateException, IOException, KeyStoreException, KeyManagementException, UnrecoverableKeyException {
        FileInputStream keyFile = new FileInputStream(sKeyStoreFile);
        FileInputStream trustFile = new FileInputStream(sTrustStoreFile);

        SSLContext ctx = SSLUtils.makeSSLContext(keyFile, trustFile, sPassword);
        return ctx;
    }

    @Test
    public void testRecvClose() throws IOException, CertificateException, InterruptedException,
           KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        final SSLContext ctx = makeContext();
        SSLSocketFactory factory = ctx.getSocketFactory();

        // Pick something that exceeds a single buffer.  Note that SSL buffers are much
        // larger than usual (around 16KB).
        final String expectedStr = Helper.makeTestString(65535);

        NonBlockingConnectionTest.RecvCloseTester tester =
            new NonBlockingConnectionTest.RecvCloseTester(makeSendTask(factory, expectedStr), 65536,
                    expectedStr) {
            @Override
            protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                    SelectTaskQueue taskQueue) throws IOException {
                try {
                    return new SSLNonBlockingConnection(selector, chan, new HeapByteBufferFactory(), taskQueue, ctx);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                SSLNonBlockingConnection conn = (SSLNonBlockingConnection) connArg;
                conn.getSSLEngine().setUseClientMode(false);

                super.prepareConn(connArg);

                conn.start();
            }
        };

        tester.run();
    }

    @Test
    public void testRecvPersistent() throws IOException, CertificateException, InterruptedException,
           KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        final SSLContext ctx = makeContext();
        SSLSocketFactory factory = ctx.getSocketFactory();

        // Pick something that exceeds the connection buffer.
        final String expectedStr = Helper.makeTestString(65535);

        NonBlockingConnectionTest.RecvPersistentTester test =
            new NonBlockingConnectionTest.RecvPersistentTester(makeSendTask(factory, expectedStr), 16, expectedStr) {
                @Override
                protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                        SelectTaskQueue taskQueue) throws IOException {
                    try {
                        return new SSLNonBlockingConnection(selector, chan, new HeapByteBufferFactory(), taskQueue, ctx);
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                protected void prepareConn(AsyncConnection connArg) {
                    SSLNonBlockingConnection conn = (SSLNonBlockingConnection) connArg;
                    conn.getSSLEngine().setUseClientMode(false);

                    super.prepareConn(connArg);

                    conn.start();
                }
        };

        test.run();
    }

    @Test
    public void testRecvSimple() throws IOException, CertificateException, InterruptedException,
           KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        final SSLContext ctx = makeContext();
        SSLSocketFactory factory = ctx.getSocketFactory();

        // Pick something that fits within a single buffer.
        final String expectedStr = Helper.makeTestString(128);

        NonBlockingConnectionTest.RecvSimpleTester test =
            new NonBlockingConnectionTest.RecvSimpleTester(makeSendTask(factory, expectedStr), 1024, expectedStr) {
                @Override
                protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                        SelectTaskQueue taskQueue) throws IOException {
                    try {
                        return new SSLNonBlockingConnection(selector, chan, new HeapByteBufferFactory(), taskQueue, ctx);
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                protected void prepareConn(AsyncConnection connArg) {
                    SSLNonBlockingConnection conn = (SSLNonBlockingConnection) connArg;
                    conn.getSSLEngine().setUseClientMode(false);

                    super.prepareConn(connArg);

                    conn.start();
                }
        };

        test.run();
    }

    @Test
    public void testRecvLong() throws IOException, CertificateException, InterruptedException,
           KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        final SSLContext ctx = makeContext();
        SSLSocketFactory factory = ctx.getSocketFactory();

        // Pick something that exceeds a single buffer.  Note that SSL buffers are much
        // larger than usual (around 16KB).
        final String expectedStr = Helper.makeTestString(32768);

        NonBlockingConnectionTest.RecvLongTester tester = new NonBlockingConnectionTest.RecvLongTester(makeSendTask(factory, expectedStr), 1024, expectedStr) {
            @Override
            protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                    SelectTaskQueue taskQueue) throws IOException {
                try {
                    return new SSLNonBlockingConnection(selector, chan, new HeapByteBufferFactory(), taskQueue, ctx);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                SSLNonBlockingConnection conn = (SSLNonBlockingConnection) connArg;
                conn.getSSLEngine().setUseClientMode(false);

                super.prepareConn(connArg);

                conn.start();
            }
        };

        tester.run();
    }

    @Test
    public void testSend() throws IOException, CertificateException, InterruptedException,
           KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        final OutputStreamLog log = new OutputStreamLog(System.out);

        final SSLContext ctx = makeContext();
        SSLSocketFactory factory = ctx.getSocketFactory();

        final String expectedStr = Helper.makeTestString(128);

        NonBlockingConnectionTest.SendTester tester = new NonBlockingConnectionTest.SendTester(makeRecvTask(factory, expectedStr), 1024, expectedStr) {
            @Override
            protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                    SelectTaskQueue taskQueue) throws IOException {
                try {
                    return new SSLNonBlockingConnection(selector, chan, new HeapByteBufferFactory(), taskQueue, ctx);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                SSLNonBlockingConnection conn = (SSLNonBlockingConnection) connArg;
                conn.setLog(log);
                conn.getSSLEngine().setUseClientMode(false);

                super.prepareConn(connArg);

                conn.start();
            }
        };

        tester.run();

        log.close();
    }

    @Test
    public void testSendBuffer() throws IOException, CertificateException, InterruptedException,
           KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        final OutputStreamLog log = new OutputStreamLog(System.out);

        final SSLContext ctx = makeContext();
        SSLSocketFactory factory = ctx.getSocketFactory();

        final String expectedStr = Helper.makeTestString(128);

        NonBlockingConnectionTest.SendBufferTester tester = new NonBlockingConnectionTest.SendBufferTester(
                makeRecvTask(factory, expectedStr), 1024, expectedStr) {
            @Override
            protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                    SelectTaskQueue taskQueue) throws IOException {
                try {
                    return new SSLNonBlockingConnection(selector, chan, new HeapByteBufferFactory(), taskQueue, ctx);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                SSLNonBlockingConnection conn = (SSLNonBlockingConnection) connArg;
                conn.setLog(log);
                conn.getSSLEngine().setUseClientMode(false);

                super.prepareConn(connArg);

                conn.start();
            }
        };

        tester.run();

        log.close();
    }

    @Test
    public void testSendBuffers() throws IOException, CertificateException, InterruptedException,
           KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        final OutputStreamLog log = new OutputStreamLog(System.out);

        final SSLContext ctx = makeContext();
        SSLSocketFactory factory = ctx.getSocketFactory();

        final String expectedStr = Helper.makeTestString(128);

        NonBlockingConnectionTest.SendBuffersTester tester =
            new NonBlockingConnectionTest.SendBuffersTester(makeRecvTask(factory, expectedStr),
                    1024, expectedStr) {
            @Override
            protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                    SelectTaskQueue taskQueue) throws IOException {
                try {
                    return new SSLNonBlockingConnection(selector, chan, new HeapByteBufferFactory(), taskQueue, ctx);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                SSLNonBlockingConnection conn = (SSLNonBlockingConnection) connArg;
                conn.setLog(log);
                conn.getSSLEngine().setUseClientMode(false);

                super.prepareConn(connArg);

                conn.start();
            }
        };

        tester.run();

        log.close();
    }

    @Test
    public void testSendClose() throws IOException, CertificateException, InterruptedException,
           KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        final OutputStreamLog log = new OutputStreamLog(System.out);

        final SSLContext ctx = makeContext();
        SSLSocketFactory factory = ctx.getSocketFactory();

        // Pick a large value that won't fit in a socket buffer.
        int bufLen = 1 << 20;
        String expectedStr = Helper.makeTestString(bufLen - 1);

        NonBlockingConnectionTest.SendCloseTester tester =
            new NonBlockingConnectionTest.SendCloseTester(makeRecvCloseTask(factory, expectedStr, 1024),
                    expectedStr.length(), expectedStr) {
            @Override
            protected AsyncConnection makeConn(Selector selector, SocketChannel chan, int bufferSize,
                    SelectTaskQueue taskQueue) throws IOException {
                try {
                    return new SSLNonBlockingConnection(selector, chan, new HeapByteBufferFactory(), taskQueue, ctx);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                SSLNonBlockingConnection conn = (SSLNonBlockingConnection) connArg;
                conn.setLog(log);
                conn.getSSLEngine().setUseClientMode(false);

                super.prepareConn(connArg);

                conn.start();
            }
        };

        tester.run();

        log.close();
    }
}
