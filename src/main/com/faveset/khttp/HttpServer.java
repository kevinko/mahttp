// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import java.nio.channels.Selector;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

class HttpServer {
    private Map<String, HttpHandler> mHttpHandlerMap = new HashMap<String, HttpHandler>();

    private ServerSocketChannel mListenChan;
    // A SelectorHandler will be attached to each SelectionKey registered
    // with the Selector.
    private Selector mSelector;

    private SelectorHandler mListenSelectorHandler = new SelectorHandler() {
            public void onReady(SelectionKey key) throws IOException {
                handleAccept(key.channel());
            }
    };

    private Set<HttpConnection> mConnectionSet = new HashSet<HttpConnection>();

    private HttpConnection.OnCloseCallback mCloseCallback = new HttpConnection.OnCloseCallback() {
        public void onClose(HttpConnection conn) throws IOException {
            handleConnectionClose(conn);
        }
    };

    public HttpServer() {}

    public void close() throws IOException {
        mListenChan.close();

        for (HttpConnection conn : mConnectionSet) {
            conn.close();
        }
        mConnectionSet.clear();

        mSelector.close();
    }

    private void handleAccept(SelectableChannel chanArg) throws IOException {
        ServerSocketChannel chan = (ServerSocketChannel) chanArg;

        SocketChannel newChan = chan.accept();

        HttpConnection conn = new HttpConnection(mSelector, newChan);
        conn.start(mHttpHandlerMap);
        conn.setOnCloseCallback(mCloseCallback);

        mConnectionSet.add(conn);
    }

    private void handleConnectionClose(HttpConnection conn) throws IOException {
        conn.close();
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
        SelectionKey listenKey = mListenChan.register(mSelector, SelectionKey.OP_ACCEPT);
        listenKey.attach(mListenSelectorHandler);

        while (true) {
            int readyCount = mSelector.select();
            if (readyCount == 0) {
                // We were interrupted, or the selector was cancelled.
                break;
            }

            Set<SelectionKey> readyKeys = mSelector.selectedKeys();
            for (SelectionKey key : readyKeys) {
                if (!key.isValid()) {
                    continue;
                }

                SelectorHandler handler = (SelectorHandler) key.attachment();
                handler.onReady(key);
            }
        }

        close();
    }

    public void registerHandler(String url, HttpHandler handler) {
        mHttpHandlerMap.put(url, handler);
    }

    /**
     * Threadsafe method for stopping the HttpServer.
     */
    public synchronized void stop() {
        mSelector.wakeup();
    }

    public void unregisterHandler(String url) {
        mHttpHandlerMap.remove(url);
    }
};
