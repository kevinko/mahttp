// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import java.nio.channels.Selector;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLContext;

import com.faveset.log.Log;
import com.faveset.log.NullLog;

public class HttpServer {
    private static final String sTag = HttpServer.class.toString();

    private Log mLog = new NullLog();

    private Map<String, HttpHandler> mHttpHandlerMap = new HashMap<String, HttpHandler>();

    private ServerSocketChannel mListenChan;

    private SelectionKey mListenKey;

    // A SelectorHandler will be attached to each SelectionKey registered
    // with the Selector.
    private Selector mSelector;

    private SelectTaskQueue mSelectTaskQueue;

    private SelectorHandler mListenSelectorHandler = new SelectorHandler() {
            @Override
            public void onReady(SelectionKey key) {
                if (key.isValid() && key.isAcceptable()) {
                    handleAccept(key.channel());
                }
            }
    };

    private Set<HttpConnection> mConnectionSet = new HashSet<HttpConnection>();

    private HttpConnection.OnCloseCallback mCloseCallback = new HttpConnection.OnCloseCallback() {
        @Override
        public void onClose(HttpConnection conn) {
            handleConnectionClose(conn);
        }
    };

    private volatile boolean mIsDone;

    private SSLContext mSSLContext;

    public HttpServer() {}

    /**
     * Configures the HttpServer to use SSL/TLS transport using the certificate from the given key
     * and trust store.  password specifies the key used to encrypt the certificate.
     *
     * This must be called before listenAndServe().
     *
     * @param keyStoreStream
     * @param trustStoreStream
     * @param password
     *
     * @throws CertificateException
     * @throws IOException
     * @throws KeyManagementException
     * @throws KeyStoreException
     * @throws UnrecoverableKeyException if a key password is incorrect.
     */
    public void configureSSL(InputStream keyStoreStream, InputStream trustStoreStream,
            String password) throws CertificateException, IOException, KeyManagementException,
            KeyStoreException, UnrecoverableKeyException {
        mSSLContext = SSLUtils.makeSSLContext(keyStoreStream, trustStoreStream, password);
    }

    public void configureSSL(String keyStoreFile, String trustStoreFile, String password)
            throws CertificateException, IOException, KeyManagementException, KeyStoreException,
            UnrecoverableKeyException {
        FileInputStream keyFile = new FileInputStream(keyStoreFile);
        FileInputStream trustFile = new FileInputStream(trustStoreFile);

        configureSSL(keyFile, trustFile, password);
    }

    /**
     * Cleans up all resources used by the http server.  Once closed, it is effectively terminated
     * and cannot be restarted.
     */
    public void close() throws IOException {
        // This also cancels the key.
        mListenChan.close();

        // Unregister handlers to avoid reference loops.
        mListenKey.attach(null);

        for (HttpConnection conn : mConnectionSet) {
            conn.close();
        }
        mConnectionSet.clear();

        mSelector.close();

        // Clean up all static resources.
        SSLNonBlockingConnection.shutdown();
    }

    private void handleAccept(SelectableChannel chanArg) {
        ServerSocketChannel chan = (ServerSocketChannel) chanArg;

        SocketChannel newChan;
        try {
            newChan = chan.accept();
        } catch (IOException e) {
            mLog.e(sTag, "accept failed; shutting down", e);

            // We have a serious problem, so just bring down the server.
            stop();

            return;
        }

        if (newChan == null) {
            // The selection key hint was incorrect, as the channel is not
            // ready to accept.
            return;
        }

        HttpConnection conn;
        try {
            if (mSSLContext != null) {
                conn = HttpConnection.makeSecureConnection(mSelector, newChan, mSelectTaskQueue,
                        mSSLContext);
            } else {
                conn = HttpConnection.makeConnection(mSelector, newChan);
            }
        } catch (IOException e) {
            mLog.e(sTag, "could not create HttpConnection, closing", e);

            try {
                newChan.close();
            } catch (IOException newChanE) {
                mLog.e(sTag, "could not close channel, ignoring", newChanE);
            }

            return;
        }

        conn.setOnCloseCallback(mCloseCallback);
        conn.setLog(mLog);

        // We must update mConnectionSet before starting, since conn.start()
        // might issue a sequence of callbacks immediately.
        mConnectionSet.add(conn);

        conn.start(mHttpHandlerMap);

        // NOTE: The connection might close as a result of start(), so we
        // must be careful when modifying after this point.
    }

    private void handleConnectionClose(HttpConnection conn) {
        // The HttpServer is at the top of the chain.  Thus, we start
        // closing for real after cleaning up.
        try {
            conn.close();
        } catch (IOException e) {
            mLog.e(sTag, "connection close failed, continuing", e);

            // We've tried our best to clean up.  It's safe to continue.
        }

        mConnectionSet.remove(conn);
    }

    /**
     * Starts the HTTP server and begins listening on the given address and
     * port.  This only returns if stop() is called by another thread.
     */
    public void listenAndServe(String listenAddr, int port) throws IllegalArgumentException, IOException {
        InetSocketAddress sa = new InetSocketAddress(listenAddr, port);

        mListenChan = ServerSocketChannel.open();
        mListenChan.configureBlocking(false);

        ServerSocket sock = mListenChan.socket();
        sock.bind(sa);

        mSelector = Selector.open();
        mSelectTaskQueue = new SelectTaskQueue(mSelector);

        mListenKey = mListenChan.register(mSelector, SelectionKey.OP_ACCEPT, mListenSelectorHandler);

        mIsDone = false;

        while (true) {
            // The return value of select() (number of keys whose
            // ready-operation sets were updated) is not the best indicator
            // to check for completeness for two reasons:
            //
            // 1) A key will not be counted if its ready set does not change.
            // (It might have been read ready before the select() and read
            // ready after, which would not be counted.)
            //
            // 2) It might take an indefinite period of time to finish handling
            // connections before we get an empty selection result.
            mSelector.select();

            if (mIsDone) {
                break;
            }

            // Handle any tasks that must run in the selector thread.
            do {
                Runnable task = mSelectTaskQueue.poll();
                if (task == null) {
                    break;
                }
                task.run();
            } while (true);

            Iterator<SelectionKey> iter = mSelector.selectedKeys().iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                if (!key.isValid()) {
                    continue;
                }

                SelectorHandler handler = (SelectorHandler) key.attachment();
                handler.onReady(key);

                // Remove for the next selection loop.
                iter.remove();
            }
        }

        close();
    }

    public void registerHandler(String url, HttpHandler handler) {
        mHttpHandlerMap.put(url, handler);
    }

    public void setLog(Log log) {
        mLog = log;
    }

    /**
     * Threadsafe method for stopping the HttpServer.
     */
    public synchronized void stop() {
        mIsDone = true;
        mSelector.wakeup();
    }

    public void unregisterHandler(String url) {
        mHttpHandlerMap.remove(url);
    }
};
