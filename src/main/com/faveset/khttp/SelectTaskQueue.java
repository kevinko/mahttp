// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.khttp;

import java.nio.channels.Selector;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Executor;

/**
 * A SelectTaskQueue is meant to be used with a Selector.  Submitted
 * tasks will wake up the associated Selector for immediate execution.
 * Because of this, all tasks should be short and non-blocking.
 *
 * This is intended for waking up (i.e., rescheduling select operations)
 * a sleeping connection.
 *
 * All tasks are placed in an unbounded queue.  Thus, it is best to schedule
 * just O(1) tasks per connection.
 */
class SelectTaskQueue implements Executor {
    private Selector mSelector;

    private BlockingQueue<Runnable> mTaskQueue;

    public SelectTaskQueue(Selector selector) {
        mSelector = selector;
        // We want an unbounded queue, since we never want a task to block.
        mTaskQueue = new LinkedBlockingQueue<Runnable>();
    }

    public void execute(final Runnable r) {
        mTaskQueue.add(r);

        mSelector.wakeup();
    }

    /**
     * @return the head of the queue or null if empty.  This is thread-safe.
     */
    public Runnable poll() {
        return mTaskQueue.poll();
    }
}
