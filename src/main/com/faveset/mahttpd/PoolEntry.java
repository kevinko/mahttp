// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

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
