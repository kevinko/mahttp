// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.log;

/**
 * A NullLog outputs nothing.
 */
public class NullLog implements Log {
    public NullLog() {}

    public int d(String tag, String msg) { return 0; }

    public int d(String tag, String msg, Throwable tr) { return 0; }

    public int e(String tag, String msg) { return 0; }

    public int e(String tag, String msg, Throwable tr) { return 0; }

    public int i(String tag, String msg) { return 0; }

    public int i(String tag, String msg, Throwable tr) { return 0; }

    public int v(String tag, String msg) { return 0; }

    public int v(String tag, String msg, Throwable tr) { return 0; }

    public int w(String tag, String msg) { return 0; }

    public int w(String tag, String msg, Throwable tr) { return 0; }
}
