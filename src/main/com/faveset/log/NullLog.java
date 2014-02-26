// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.log;

/**
 * A NullLog outputs nothing.
 */
public class NullLog implements Log {
    public NullLog() {}

    public int d(String tag, String msg) {}

    public int d(String tag, String msg, Throwable tr) {}

    public int e(String tag, String msg) {}

    public int e(String tag, String msg, Throwable tr) {}

    public int i(String tag, String msg) {}

    public int i(String tag, String msg, Throwable tr) {}

    public int v(String tag, String msg) {}

    public int v(String tag, String msg, Throwable tr) {}

    public int w(String tag, String msg) {}

    public int w(String tag, String msg, Throwable tr) {}
}
