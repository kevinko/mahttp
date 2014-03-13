// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

/**
 * A NullPool does not perform object pooling.  It is useful for testing.
 */
abstract class NullPool<T> implements PoolInterface<T> {
    private int mTagCount;

    public PoolEntry<T> allocate() {
        return new PoolEntry<T>(mTagCount++, allocateValue());
    }

    /**
     * Child classes should implement this.
     */
    protected abstract T allocateValue();

    public PoolEntry<T> release(PoolEntry<T> entry) {
        return null;
    }
}
