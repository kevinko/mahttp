// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
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

@RunWith(JUnit4.class)
public class SSLNonBlockingConnectionTest {
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
    public void testRecvSimple() throws IOException, CertificateException, InterruptedException,
           KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        SSLContext ctx = makeContext();
        SSLSocketFactory factory = ctx.getSocketFactory();

        // Pick something that fits within a single buffer.
        final String expectedStr = Helper.makeTestString(128);

        Tester test = new Tester(ctx, makeSendTask(factory, expectedStr), 1024) {
            private int mRecvCount = 0;

            @Override
            protected void finish() {
                super.finish();
                assertEquals(1, mRecvCount);
            }

            @Override
            protected void prepareConn(AsyncConnection connArg) {
                SSLNonBlockingConnection conn = (SSLNonBlockingConnection) connArg;
                conn.getSSLEngine().setUseClientMode(false);

                conn.recv(new AsyncConnection.OnRecvCallback() {
                    public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                        mRecvCount++;

                        try {
                            ByteBuffer cmpBuf = ByteBuffer.allocate(1024);

                            cmpBuf.put(buf);
                            cmpBuf.flip();
                            Helper.compare(cmpBuf, expectedStr);

                            conn.close();
                        } catch (SSLException e) {
                            System.out.println(e);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                conn.start();
            }
        };
        test.run();
    }

    @Test
    public void testRecvLong() throws IOException, CertificateException, InterruptedException,
           KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        SSLContext ctx = makeContext();
        SSLSocketFactory factory = ctx.getSocketFactory();

        // Pick something that exceeds a single buffer.  Note that SSL buffers are much
        // larger than usual (around 16KB).
        final String expectedStr = Helper.makeTestString(32768);

        Tester tester = new Tester(ctx, makeSendTask(factory, expectedStr), 1024) {
            private int mRecvCount = 0;
            private ByteBuffer mCmpBuf = ByteBuffer.allocate(2 * expectedStr.length());

            private AsyncConnection.OnRecvCallback makeRecvCallback() {
                return new AsyncConnection.OnRecvCallback() {
                    public void onRecv(AsyncConnection conn, ByteBuffer buf) {
                        handleRecv(conn, buf);
                    }
                };
            }

            @Override
            protected void finish() {
                super.finish();

                assertTrue(mRecvCount >= 2);
            }

            private void handleRecv(AsyncConnection conn, ByteBuffer buf) {
                System.out.println("handleRecv()");
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
            protected void prepareConn(AsyncConnection connArg) {
                SSLNonBlockingConnection conn = (SSLNonBlockingConnection) connArg;
                conn.getSSLEngine().setUseClientMode(false);

                conn.recv(makeRecvCallback());

                conn.start();
            }
        };

        tester.run();
    }
}
