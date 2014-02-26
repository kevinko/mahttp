// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.log;

public interface Log {
    /**
     * Send a DEBUG log message.
     */
    int d(String tag, String msg);

    /**
     * Send a DEBUG log message and log the exception.
     */
    int d(String tag, String msg, Throwable tr);

    /**
     * Send an ERROR log message.
     */
    int e(String tag, String msg);

    /**
     * Send an ERROR log message and log the exception.
     */
    int e(String tag, String msg, Throwable tr);

    /**
     * Send an INFO log message.
     */
    int i(String tag, String msg);

    /**
     * Send an INFO log message and log the exception.
     */
    int i(String tag, String msg, Throwable tr);

    /**
     * Send a VERBOSE log message.
     */
    int v(String tag, String msg);

    /**
     * Send a VERBOSE log message and log the exception.
     */
    int v(String tag, String msg, Throwable tr);

    /**
     * Send a WARN log message.
     */
    int w(String tag, String msg);

    /**
     * Send a WARN log message and log the exception.
     */
    int w(String tag, String msg, Throwable tr);
}
