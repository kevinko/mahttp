// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.log;

import java.io.OutputStream;
import java.io.PrintWriter;

public class OutputStreamLog implements Log {
    private static final String sDebugLabel = "DEBUG";
    private static final String sErrorLabel = "ERROR";
    private static final String sInfoLabel = "INFO";
    private static final String sVerboseLabel = "VERBOSE";
    private static final String sWarnLabel = "WARN";

    private PrintWriter mWriter;

    public OutputStreamLog(OutputStream os) {
        mWriter = new PrintWriter(os);
    }

    /**
     * Closes the stream and releases all resources.
     *
     * One should typically call this when finished logging to flush all output to the stream.
     */
    public void close() {
        mWriter.close();
    }

    public synchronized int d(String tag, String msg) {
        String s = makeString(sDebugLabel, tag, msg);
        mWriter.println(s);
        return s.length();
    }

    public synchronized int d(String tag, String msg, Throwable tr) {
        String s = makeExceptionString(sDebugLabel, tag, msg, tr);
        mWriter.println(s);
        return s.length();
    }

    public synchronized int e(String tag, String msg) {
        String s = makeString(sErrorLabel, tag, msg);
        mWriter.println(s);
        return s.length();
    }

    public synchronized int e(String tag, String msg, Throwable tr) {
        String s = makeExceptionString(sErrorLabel, tag, msg, tr);
        mWriter.println(s);
        return s.length();
    }

    public synchronized int i(String tag, String msg) {
        String s = makeString(sInfoLabel, tag, msg);
        mWriter.println(s);
        return s.length();
    }

    public synchronized int i(String tag, String msg, Throwable tr) {
        String s = makeExceptionString(sInfoLabel, tag, msg, tr);
        mWriter.println(s);
        return s.length();
    }

    public synchronized int v(String tag, String msg) {
        String s = makeString(sVerboseLabel, tag, msg);
        mWriter.println(s);
        return s.length();
    }

    public synchronized int v(String tag, String msg, Throwable tr) {
        String s = makeExceptionString(sVerboseLabel, tag, msg, tr);
        mWriter.println(s);
        return s.length();
    }

    public synchronized int w(String tag, String msg) {
        String s = makeString(sWarnLabel, tag, msg);
        mWriter.println(s);
        return s.length();
    }

    public synchronized int w(String tag, String msg, Throwable tr) {
        String s = makeExceptionString(sWarnLabel, tag, msg, tr);
        mWriter.println(s);
        return s.length();
    }

    private static String makeString(String label, String tag, String msg) {
        StringBuilder builder = new StringBuilder();
        builder.append(label);
        builder.append(": ");
        builder.append(tag);
        builder.append(": ");
        builder.append(msg);
        return builder.toString();
    }

    private static String makeExceptionString(String label, String tag, String msg, Throwable tr) {
        StringBuilder builder = new StringBuilder();
        builder.append(label);
        builder.append(": ");
        builder.append(tag);
        builder.append(": caught exception ");
        builder.append(tr.toString());
        builder.append(": ");
        builder.append(msg);
        return builder.toString();
    }
}
