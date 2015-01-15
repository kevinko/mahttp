// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.flags;

public abstract class BaseFlag implements FlagParser {
    private String mDesc;

    public BaseFlag(String desc) {
        mDesc = desc;
    }

    public abstract String getDefaultValueString();

    public String getDesc() {
        return mDesc;
    }

    public boolean isSingular() {
        return false;
    }

    public abstract void parse(String s);
}
