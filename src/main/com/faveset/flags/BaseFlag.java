// Copyright 2014, Kevin Ko <kevin@faveset.com>

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
