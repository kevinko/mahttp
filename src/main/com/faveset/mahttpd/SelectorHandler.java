// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.nio.channels.SelectionKey;

/**
 * SelectorHandler callbacks are attached to SelectionKeys so that we have
 * a standardized interface for triggering actions.
 */
interface SelectorHandler {
    void onReady(SelectionKey key);
}
