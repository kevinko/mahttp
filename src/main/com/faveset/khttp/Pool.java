// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

interface Pool<T> {
    PoolEntry<T> allocate();

    PoolEntry<T> release(PoolEntry<T> entry);
}
