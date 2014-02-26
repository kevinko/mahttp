// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.log;

public interface Log {
    /**
     * Send a DEBUG log message.
     *
     * @return the number of bytes written
     */
    int d(String tag, String msg);

    /**
     * Send a DEBUG log message and log the exception.
     *
     * @return the number of bytes written
     */
    int d(String tag, String msg, Throwable tr);

    /**
     * Send an ERROR log message.
     *
     * @return the number of bytes written
     */
    int e(String tag, String msg);

    /**
     * Send an ERROR log message and log the exception.
     *
     * @return the number of bytes written
     */
    int e(String tag, String msg, Throwable tr);

    /**
     * Send an INFO log message.
     *
     * @return the number of bytes written
     */
    int i(String tag, String msg);

    /**
     * Send an INFO log message and log the exception.
     *
     * @return the number of bytes written
     */
    int i(String tag, String msg, Throwable tr);

    /**
     * Send a VERBOSE log message.
     *
     * @return the number of bytes written
     */
    int v(String tag, String msg);

    /**
     * Send a VERBOSE log message and log the exception.
     *
     * @return the number of bytes written
     */
    int v(String tag, String msg, Throwable tr);

    /**
     * Send a WARN log message.
     *
     * @return the number of bytes written
     */
    int w(String tag, String msg);

    /**
     * Send a WARN log message and log the exception.
     *
     * @return the number of bytes written
     */
    int w(String tag, String msg, Throwable tr);
}
