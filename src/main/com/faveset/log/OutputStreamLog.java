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

    public synchronized int d(String tag, String msg) {
        mWriter.append(makeString(sDebugLabel, tag, msg));
    }

    public synchronized int d(String tag, String msg, Throwable tr) {
        mWriter.append(makeExceptionString(sDebugLabel, tag, msg, tr));
    }

    public synchronized int e(String tag, String msg) {
        mWriter.append(makeString(sErrorLabel, tag, msg));
    }

    public synchronized int e(String tag, String msg, Throwable tr) {
        mWriter.append(makeExceptionString(sErrorLabel, tag, msg, tr));
    }

    public synchronized int i(String tag, String msg) {
        mWriter.append(makeString(sInfoLabel, tag, msg));
    }

    public synchronized int i(String tag, String msg, Throwable tr) {
        mWriter.append(makeExceptionString(sInfoLabel, tag, msg, tr));
    }

    public synchronized int v(String tag, String msg) {
        mWriter.append(makeString(sVerboseLabel, tag, msg));
    }

    public synchronized int v(String tag, String msg, Throwable tr) {
        mWriter.append(makeExceptionString(sVerboseLabel, tag, msg, tr));
    }

    public synchronized int w(String tag, String msg) {
        mWriter.append(makeString(sWarnLabel, tag, msg));
    }

    public synchronized int w(String tag, String msg, Throwable tr) {
        mWriter.append(makeExceptionString(sWarnLabel, tag, msg, tr));
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
