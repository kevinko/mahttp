// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.khttpserver;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import sun.misc.Signal;
import sun.misc.SignalHandler;

import com.faveset.mahttpd.HttpHandler;
import com.faveset.mahttpd.HttpRequest;
import com.faveset.mahttpd.HttpResponseWriter;

import com.faveset.flags.BoolFlag;
import com.faveset.flags.Flags;
import com.faveset.flags.StringFlag;
import com.faveset.log.OutputStreamLog;

public class HttpServer {
    private static BoolFlag sLogFlag =
        Flags.registerBool("log", false, "enable logging to stdout");

    private static StringFlag sKeyStoreFlag = Flags.registerString("keystore", "", "keystore file");

    private static StringFlag sTrustStoreFlag =
        Flags.registerString("truststore", "", "truststore file");

    private static StringFlag sKeyPassFlag =
        Flags.registerString("keypass", "", "password for private key/cert");

    public static void main(String[] args) throws CertificateException, IOException,
           IllegalArgumentException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
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

        final com.faveset.mahttpd.HttpServer server = new com.faveset.mahttpd.HttpServer();

        OutputStreamLog log = null;
        if (sLogFlag.get()) {
            System.out.println("logging to stdout");
            log = new OutputStreamLog(System.out);
            server.setLog(log);
        }

        if (!sKeyStoreFlag.get().isEmpty() &&
                !sTrustStoreFlag.get().isEmpty()) {
            System.out.println("using keystore (" + sKeyStoreFlag.get() + ") truststore (" +
                    sTrustStoreFlag.get() + ")");
            server.configureSSL(sKeyStoreFlag.get(), sTrustStoreFlag.get(), sKeyPassFlag.get());
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
