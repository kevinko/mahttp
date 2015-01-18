// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

abstract class BasePool<T> implements Pool<T> {
    // Used for generating unique tags for each entry's hashCode.
    // Since Java is a GCed language, we need not worry about wraparound.
    // If a duplication occurs, the entry Set will only hold one
    // of the entries.  The other will be released and treated as a normal
    // reference, which will cause a negligible amount of GC churn.
    private int mTagCount;

    // The maximum number of entries to maintain.
    private int mMaxCount;

    // Tracks all free entries.  A free entry is one that is allocated and
    // then released.
    private Set<PoolEntry<T>> mFreeEntries;

    /**
     * @param maxCount the maximum number of entries to maintain before letting
     * the GC take over.
     */
    protected BasePool(int maxCount) {
        mMaxCount = maxCount;

        mFreeEntries = new HashSet<PoolEntry<T>>(mMaxCount);
    }

    @Override
    public PoolEntry<T> allocate() {
        PoolEntry<T> entry;
        if (mFreeEntries.size() == 0) {
            entry = new PoolEntry<T>(mTagCount, allocateValue());
            mTagCount++;
        } else {
            // Otherwise, use a value from the pool.
            Iterator<PoolEntry<T>> iter = mFreeEntries.iterator();
            entry = iter.next();
            resetValue(entry.get());
            iter.remove();
        }

        return entry;
    }

    /**
     * Child classes should implement this.
     */
    protected abstract T allocateValue();

    // Used for testing.
    int getFreeEntryCount() {
        return mFreeEntries.size();
    }

    /**
     * This returns null so that one can clear the reference to entry when
     * releasing:
     *
     *   entry = pool.release(entry);
     *
     * @return null
     */
    @Override
    public PoolEntry<T> release(PoolEntry<T> entry) {
        if (entry == null) {
            return null;
        }

        if (mFreeEntries.size() < mMaxCount) {
            // Keep the entry so that we can reach the desired thresholds.
            mFreeEntries.add(entry);
        }
        // At this point, mFreeEntries.size() <= mMaxCount.

        return null;
    }

    /**
     * Child classes should implement this to reset a value.  Values are
     * reset when released to the pool so that a cleared value will be
     * returned on allocate().
     */
    protected abstract void resetValue(T v);
}
