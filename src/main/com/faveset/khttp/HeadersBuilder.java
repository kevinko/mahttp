// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.util.ArrayList;
import java.util.List;

class HeadersBuilder extends Headers {
    public HeadersBuilder() {
        super();
    }

    public HeadersBuilder(Headers headers) {
        mHeaders = headers.mHeaders;
    }

    /**
     * Adds the mapping (key, value), and appends to any existing values
     * if applicable.
     */
    public void add(String key, String value) {
        key = canonicalizeKey(key);

        List<String> l = mHeaders.get(key);
        if (l == null) {
            l = new ArrayList<String>();
            mHeaders.put(key, l);
        }

        l.add(value);
    }

    /**
     * Appends addedValue to the existing value for key, or creates a new
     * mapping if one does not exist.  A space will separate the existing
     * value and the new value.
     */
    public void appendValue(String key, String addedValue) {
        key = canonicalizeKey(key);

        String trimmedAddedValue = addedValue.trim();

        List<String> l = mHeaders.get(key);
        if (l == null) {
            set(key, trimmedAddedValue);
            return;
        }

        if (l.size() == 0) {
            // Lists shouldn't be empty, but just in case.
            l.add(trimmedAddedValue);
            return;
        }

        // Access is fast, since we use an ArrayList internally.
        // NOTE: be aware of this when changing internal structures.
        int lastIndex = l.size() - 1;
        String lastValue = l.get(lastIndex);
        String newValue = lastValue.trim().concat(" " + trimmedAddedValue);
        l.set(lastIndex, newValue);
    }

    public void clear() {
        mHeaders.clear();
    }

    /**
     * Removes the mapping for key.
     */
    public void remove(String key) {
        key = canonicalizeKey(key);
        mHeaders.remove(key);
    }

    /**
     * Assigns value to the mapping for key.  This clears any existing list
     * values.
     */
    public void set(String key, String value) {
        key = canonicalizeKey(key);

        List<String> l = new ArrayList<String>();
        l.add(value);

        mHeaders.put(key, l);
    }

    /**
     * Assigns value to the mapping for key.  This overwrites any existing
     * mapping.
     */
    public void setList(String key, List<String> values) {
        key = canonicalizeKey(key);
        mHeaders.put(key, values);
    }
}
