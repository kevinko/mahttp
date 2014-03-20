// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

/**
 * A NullPool does not perform object pooling.  It is useful for testing.
 */
abstract class NullPool<T> implements Pool<T> {
    private int mTagCount;

    @Override
    public PoolEntry<T> allocate() {
        return new PoolEntry<T>(mTagCount++, allocateValue());
    }

    /**
     * Child classes should implement this.
     */
    protected abstract T allocateValue();

    @Override
    public PoolEntry<T> release(PoolEntry<T> entry) {
        return null;
    }
}
