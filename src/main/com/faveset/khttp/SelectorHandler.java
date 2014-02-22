// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.IOException;
import java.nio.channels.SelectionKey;

/**
 * SelectorHandler callbacks are attached to SelectionKeys so that we have
 * a standardized interface for triggering actions.
 */
interface SelectorHandler {
    void onReady(SelectionKey key) throws IOException;
}
