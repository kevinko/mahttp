// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.flags;

public class BoolFlag extends BaseFlag {
    private boolean mDefaultValue;
    private boolean mValue;

    BoolFlag(boolean defaultValue, String desc) {
        super(desc);

        mDefaultValue = defaultValue;
        mValue = defaultValue;
    }

    @Override
    public String getDefaultValueString() {
        return Boolean.toString(mDefaultValue);
    }

    public boolean get() {
        return mValue;
    }

    @Override
    public boolean isSingular() {
        return true;
    }

    @Override
    public void parse(String s) {
        if (s.isEmpty()) {
            mValue = true;
            return;
        }

        mValue = Boolean.parseBoolean(s);
    }
}
