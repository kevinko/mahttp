// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttpserver;

import java.io.IOException;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import com.faveset.khttp.HttpHandler;
import com.faveset.khttp.HttpRequest;
import com.faveset.khttp.HttpResponseWriter;

import com.faveset.log.OutputStreamLog;

public class HttpServer {
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("<port>");
            return;
        }

        int port = new Integer(args[0]);

        OutputStreamLog log = new OutputStreamLog(System.out);

        final com.faveset.khttp.HttpServer server = new com.faveset.khttp.HttpServer();
        server.setLog(log);

        // Set up the signal handler for exiting the server.
        Signal.handle(new Signal("INT"), new SignalHandler() {
            @Override
            public void handle(Signal sig) {
                server.stop();
            }
        });

        // Register handlers.

        server.registerHandler("/", new HttpHandler() {
            public void onRequest(HttpRequest req, HttpResponseWriter w) {
                w.getHeadersBuilder().set("Content-Type", "text/html");
                w.write("<html><body>Hello</body></html>");
            }
        });
        server.listenAndServe("::", port);

        log.close();
    }
}
