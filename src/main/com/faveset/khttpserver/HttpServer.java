// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttpserver;

import java.io.IOException;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import com.faveset.khttp.HttpHandler;
import com.faveset.khttp.HttpRequest;
import com.faveset.khttp.HttpResponseWriter;

import com.faveset.flags.BoolFlag;
import com.faveset.flags.Flags;
import com.faveset.log.OutputStreamLog;

public class HttpServer {
    private static BoolFlag mLogFlag =
        Flags.registerBool("log", false, "enable logging to stdout");

    public static void main(String[] args) throws IOException, IllegalArgumentException {
        Flags.parse(args);

        Flags flags = Flags.get();
        if (flags.getArgSize() < 1) {
            StringBuilder builder = new StringBuilder();
            builder.append("<port>\n");
            flags.writeHelp(builder);

            System.out.println(builder.toString());
            return;
        }

        int port = new Integer(flags.getArg(0));

        final com.faveset.khttp.HttpServer server = new com.faveset.khttp.HttpServer();

        OutputStreamLog log = null;
        if (mLogFlag.get()) {
            System.out.println("logging to stdout");
            log = new OutputStreamLog(System.out);
            server.setLog(log);
        }

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

        if (log != null) {
            log.close();
        }
    }
}
