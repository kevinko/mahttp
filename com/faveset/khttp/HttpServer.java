// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import java.nio.channels.Selector;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

class HttpServer {
    public interface Handler {
        void onRequest(HttpRequest req, HttpResponseWriter writer);
    }

    private interface SelectorHandler {
        void onReady(SelectionKey key) throws IOException;
    }

    private Map<String, Handler> mHttpHandlerMap = new HashMap<String, Handler>();

    private Map<SelectionKey, SelectorHandler> mSelectorHandlerMap = new HashMap<SelectionKey, SelectorHandler>();

    private ServerSocketChannel mListenChan;
    private Selector mSelector;

    public HttpServer() {
        mHttpHandlerMap = new HashMap<String, Handler>();
    }

    public void close() throws IOException {
        mListenChan.close();

        mSelector.close();
    }

    private void handleAccept(SelectableChannel chanArg) throws IOException {
        ServerSocketChannel chan = (ServerSocketChannel) chanArg;

        SocketChannel newChan = chan.accept();
        newChan.configureBlocking(false);

        // Prepare to read in the request.
        SelectionKey key = newChan.register(mSelector, SelectionKey.OP_READ);
        registerSelectorHandler(key, new SelectorHandler() {
            public void onReady(SelectionKey key) throws IOException {
                // TODO
                //handleHttp(chan, attach);
            }
        });
    }

    public void listenAndServe(String listenAddr, int port) throws IllegalArgumentException, IOException {
        InetSocketAddress sa = new InetSocketAddress(listenAddr, port);

        mListenChan = ServerSocketChannel.open();
        mListenChan.configureBlocking(false);

        ServerSocket sock = mListenChan.socket();
        sock.bind(sa);

        mSelector = Selector.open();
        SelectionKey listenKey = mListenChan.register(mSelector, SelectionKey.OP_ACCEPT);
        registerSelectorHandler(listenKey, new SelectorHandler() {
            public void onReady(SelectionKey key) throws IOException {
                handleAccept(key.channel());
            }
        });

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

                SelectorHandler handler = mSelectorHandlerMap.get(key);
                handler.onReady(key);
            }
        }

        mSelector.close();
    }

    public void registerGet(String url, Handler handler) {
        mHttpHandlerMap.put(url, handler);
    }

    private void registerSelectorHandler(SelectionKey key, SelectorHandler handler) {
        mSelectorHandlerMap.put(key, handler);
    }

    private void unregisterSelectorHandler(SelectionKey key) {
        mSelectorHandlerMap.remove(key);
    }
};
