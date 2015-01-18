// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

interface Pool<T> {
    PoolEntry<T> allocate();

    PoolEntry<T> release(PoolEntry<T> entry);
}
