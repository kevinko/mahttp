// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Represents the key-value pairs in an HTTP headers.
 */
public class Headers {
    protected HashMap<String, List<String>> mHeaders;

    public Headers() {
        mHeaders = new HashMap<String, List<String>>();
    }

    /**
     * Key will be canonicalized so that the first letter and any letter
     * following a hypen is upper case; all other letters are lowercase.
     */
    protected String canonicalizeKey(String key) {
        StringBuilder builder = new StringBuilder(key.length());
        boolean seenHyphen = false;
        for (int ii = 0; ii < key.length(); ii++) {
            char ch = key.charAt(ii);
            if (ch == '-') {
                seenHyphen = true;
            } else if (ii == 0 || seenHyphen) {
                ch = Character.toUpperCase(ch);
                seenHyphen = false;
            }
            builder.append(ch);
        }
        return builder.toString();
    }

    /**
     * @return The list of values for the header named key or null if it
     * doesn't exist.  The returned list will always have non-zero length.
     */
    public List<String> get(String key) {
        key = canonicalizeKey(key);
        return mHeaders.get(key);
    }

    /**
     * Convenience method for returning the first header value or null if no
     * mapping exists.
     */
    public String getFirst(String key) {
        key = canonicalizeKey(key);

        List<String> l = mHeaders.get(key);
        if (l == null) {
            return null;
        }
        return l.get(0);
    }
}
