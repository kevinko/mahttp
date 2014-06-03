// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.flags;

public class StringFlag extends BaseFlag {
    private String mDefaultValue;
    private String mValue;

    StringFlag(String defaultValue, String desc) {
        super(desc);

        mDefaultValue = defaultValue;
        mValue = defaultValue;
    }

    @Override
    public String getDefaultValueString() {
        return mDefaultValue;
    }

    public String get() {
        return mValue;
    }

    @Override
    public boolean isSingular() {
        return false;
    }

    @Override
    public void parse(String s) {
        mValue = s;
    }
}
