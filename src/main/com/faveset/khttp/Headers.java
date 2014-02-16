// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Represents the key-value pairs in an HTTP headers.
 */
public class Headers {
    private static final byte[] sCrlfBytes = { (byte) '\r', (byte) '\n' };

    // Holds the characters representing the delimiter between header key and
    // value.
    private static final byte[] sHeaderDelimBytes = { (byte) ':', (byte) ' ' };
    // Used to delimit multiple header values.
    private static final byte sHeaderValueDelim = (byte) ',';

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

    /**
     * @return the header map as a string in wire format.
     */
    public String toString() {
        StringBuilder builder = new StringBuilder();
        writeString(builder);
        return builder.toString();
    }

    /**
     * Writes the headers in (HTTP) wire format to buf.
     */
    public void write(ByteBuffer buf) throws BufferOverflowException {
        for (Map.Entry<String, List<String>> entry : mHeaders.entrySet()) {
            Strings.write(entry.getKey(), buf);
            buf.put(sHeaderDelimBytes);
            writeValue(buf, entry.getValue());

            buf.put(sCrlfBytes);
        }
    }

    /**
     * Write the headers in (HTTP) wire format to the StringBuilder.
     */
    public void writeString(StringBuilder builder) {
        for (Map.Entry<String, List<String>> entry : mHeaders.entrySet()) {
            builder.append(entry.getKey());
            builder.append(": ");
            String v = Strings.join(entry.getValue(), ",");
            builder.append(v);

            builder.append("\r\n");
        }
    }

    /**
     * Writes the values as a comma-separated list to buf.
     */
    private void writeValue(ByteBuffer buf, List<String> values) throws BufferOverflowException {
        if (values.size() == 0) {
            return;
        }

        Iterator<String> iter = values.iterator();
        Strings.write(iter.next(), buf);

        while (iter.hasNext()) {
            buf.put(sHeaderValueDelim);
            Strings.write(iter.next(), buf);
        }
    }
}
