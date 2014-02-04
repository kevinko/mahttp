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

    private interface SelectableChannelHandler {
        void onReady(SelectableChannel chan, Object attachment) throws IOException;
    }

    private Map<String, Handler> mHttpHandlerMap = new HashMap<String, Handler>();

    private Map<SelectionKey, SelectableChannelHandler> mSelectableChannelHandlerMap = new HashMap<SelectionKey, SelectableChannelHandler>();

    private ServerSocketChannel mListenChan;
    private Selector mSelector;

    public HttpServer() {
        mHttpHandlerMap = new HashMap<String, Handler>();
    }

    public void close() throws IOException {
        mListenChan.close();

        mSelector.close();
    }

    private void handleAccept(SelectableChannel chanArg, Object attachment) throws IOException {
        ServerSocketChannel chan = (ServerSocketChannel) chanArg;

        SocketChannel newChan = chan.accept();
        newChan.configureBlocking(false);
    }

    public void listenAndServe(String listenAddr, int port) throws IllegalArgumentException, IOException {
        InetSocketAddress sa = new InetSocketAddress(listenAddr, port);

        mListenChan = ServerSocketChannel.open();
        mListenChan.configureBlocking(false);

        ServerSocket sock = mListenChan.socket();
        sock.bind(sa);

        mSelector = Selector.open();
        SelectionKey listenKey = mListenChan.register(mSelector, SelectionKey.OP_ACCEPT);
        registerSelectableChannelHandler(listenKey, new SelectableChannelHandler() {
            public void onReady(SelectableChannel chan, Object attach) throws IOException {
                handleAccept(chan, attach);
            }
        });

        while (true) {
            int readyCount = mSelector.select();
            if (readyCount == 0) {
                // We were interrupted.
                break;
            }

            Set<SelectionKey> readyKeys = mSelector.selectedKeys();
            for (SelectionKey key : readyKeys) {
                if (!key.isValid()) {
                    continue;
                }

                SelectableChannelHandler handler = mSelectableChannelHandlerMap.get(key);
                handler.onReady(key.channel(), key.attachment());
            }
        }

        mSelector.close();
    }

    public void registerGet(String url, Handler handler) {
        mHttpHandlerMap.put(url, handler);
    }

    private void registerSelectableChannelHandler(SelectionKey key, SelectableChannelHandler handler) {
        mSelectableChannelHandlerMap.put(key, handler);
    }

    private void unregisterSelectableChannelHandler(SelectionKey key) {
        mSelectableChannelHandlerMap.remove(key);
    }
};
