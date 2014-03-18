// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

class PoolEntry<T> {
    private int mTag;

    private T mValue;

    PoolEntry(int tag, T v) {
        mTag = tag;
        mValue = v;
    }

    public T get() {
        return mValue;
    }

    @Override
    public int hashCode() {
        return mTag;
    }
}