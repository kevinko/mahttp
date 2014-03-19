// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.util.ArrayList;
import java.util.List;

/**
 * IMPORTANT: all header field names and values must hold only ASCII values.
 * RFC2616 does allow values to have TEXT (any OCTET except CTLs, including
 * LWS); however, it also suggests the use of RFC2047 (ASCII-based) encoding
 * for non-ISO-8859-1 characters.
 *
 * We err on the conservative side and only allow the ASCII subset of
 * ISO-8859-1.  This allows for faster UTF-8 conversions of Java's UTF-16
 * Strings.
 */
public class HeadersBuilder extends Headers {
    public HeadersBuilder() {
        super();
    }

    /**
     * Adds the mapping (key, value), and appends to any existing values
     * if applicable.
     *
     * @param key must only contain ASCII characters
     * @param value must only contain ASCII characters
     */
    public void add(String key, String value) {
        key = canonicalizeKey(key);

        List<String> l = mHeaders.get(key);
        if (l == null) {
            l = new ArrayList<String>(1);
            mHeaders.put(key, l);
        }

        l.add(value);
    }

    /**
     * Appends addedValue to the existing value for key, or creates a new
     * mapping if one does not exist.  A space will separate the existing
     * value and the new value.
     *
     * @param key must only contain ASCII characters
     * @param addedValue must only contain ASCII characters
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
     *
     * @param key must only contain ASCII characters
     * @param value must only contain ASCII characters
     */
    public void set(String key, String value) {
        key = canonicalizeKey(key);

        // In the common case, keys will have a single value.
        List<String> l = new ArrayList<String>(1);
        l.add(value);

        mHeaders.put(key, l);
    }

    /**
     * Assigns value to the mapping for key.  This overwrites any existing
     * mapping.
     *
     * @param key must only contain ASCII characters
     * @param values must only contain ASCII characters
     */
    public void setList(String key, List<String> values) {
        key = canonicalizeKey(key);
        mHeaders.put(key, values);
    }
}
