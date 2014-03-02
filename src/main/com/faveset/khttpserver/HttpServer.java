// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttpserver;

import java.io.IOException;
import com.faveset.log.OutputStreamLog;

public class HttpServer {
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("<port>");
            return;
        }

        int port = new Integer(args[0]);

        OutputStreamLog log = new OutputStreamLog(System.out);

        com.faveset.khttp.HttpServer server = new com.faveset.khttp.HttpServer();
        server.setLog(log);

        server.listenAndServe("127.0.0.1", port);

        log.close();
    }
}
