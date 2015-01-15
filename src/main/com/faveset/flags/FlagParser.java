// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.flags;

interface FlagParser {
    /**
     * @return default value as a string.
     */
    String getDefaultValueString();

    String getDesc();

    /**
     * Singular flags are treated differently:
     *
     *   -flag
     *     in isolation will set the flag value according to the empty
     *     string.
     *
     *   -flag=v
     *     will set the value according to the interpretation of v.
     *
     *   -flag v
     *     is not allowed.
     */
    boolean isSingular();

    /**
     * s will be the flag value, which will be empty if no value exists.
     */
    void parse(String s);
}
