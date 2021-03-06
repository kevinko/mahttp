// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.channels.SelectionKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

class Strings {
    public static final String CRLF = "\r\n";
    public static final byte[] CRLF_BYTES = { (byte) '\r', (byte) '\n' };

    // RFC2616 specifies US-ASCII.  However, UTF-8 is a superset of ASCII and
    // is more efficiently converted from Java's UTF-16.
    private static final Charset sUTF8Charset = Charset.forName("UTF-8");

    /**
     * Returns a String created from buf at buf's current position using its
     * remaining length.
     *
     * buf will be interpreted as a UTF-8 string.
     */
    public static String byteBufferToString(ByteBuffer buf) {
        if (buf.hasArray()) {
            int offset = buf.arrayOffset() + buf.position();
            return new String(buf.array(), offset, buf.remaining(), sUTF8Charset);
        }

        byte[] data = new byte[buf.remaining()];

        buf.mark();
        buf.get(data);
        buf.reset();

        return new String(data, sUTF8Charset);
    }

    /**
     * This also handles bare '\n', which might be erroneously passed by some
     * clients.
     *
     * @return true if lineBuf has leading CRLF.
     */
    public static boolean hasLeadingCrlf(ByteBuffer lineBuf) {
        if (!lineBuf.hasRemaining()) {
            return false;
        }

        lineBuf.mark();

        boolean success = false;
        do {
            char ch = (char) lineBuf.get();
            if (ch == '\n') {
                // Handle bare newlines, as a precaution.
                success = true;
                break;
            }

            if (ch != '\r') {
                break;
            }

            ch = (char) lineBuf.get();
            if (ch == '\n') {
                success = true;
            }
        } while (false);

        lineBuf.reset();
        return success;
    }

    /**
     * @return true if lineBuf has leading whitespace, which would
     * signal header value folding.
     */
    public static boolean hasLeadingSpace(ByteBuffer lineBuf) {
        if (!lineBuf.hasRemaining()) {
            return false;
        }

        char ch = (char) lineBuf.get(lineBuf.position());
        return isWhitespace(ch);
    }

    private static boolean isCtl(char ch) {
        if (ch <= 31 || ch == 127) {
            return true;
        }
        return false;
    }

    private static boolean isSeparator(char ch) {
        if (ch >= 0x30 && ch <= 0x39) {
            // [0-9]
            return false;
        }

        if (ch >= 0x41 && ch <= 0x5a) {
            // [A-Z]
            return false;
        }

        if (ch >= 0x61 && ch <= 0x7a) {
            // [a-z]
            return false;
        }

        switch (ch) {
            case '(':
            case ')':
            case '<':
            case '>':
            case '@':
            case ',':
            case ';':
            case ':':
            case '\\':
            case '"':
            case '/':
            case '[':
            case ']':
            case '?':
            case '=':
            case '{':
            case '}':
            case ' ':
            case '\t':
                return true;

            default:
                break;
        }

        return false;
    }

    private static boolean isWhitespace(char ch) {
        switch (ch) {
            case ' ':
            case '\t':
                return true;

            default:
                return false;
        }
    }

    /**
     * Helper function for debugging SelectionKey interest sets.
     */
    public static String keyInterestOpsToString(int ops) {
        StringBuilder b = new StringBuilder();

        b.append(ops);
        b.append(' ');

        if ((ops & SelectionKey.OP_ACCEPT) != 0) {
            b.append("a");
        } else {
            b.append(" ");
        }

        if ((ops & SelectionKey.OP_CONNECT) != 0) {
            b.append("c");
        } else {
            b.append(" ");
        }

        if ((ops & SelectionKey.OP_READ) != 0) {
            b.append("r");
        } else {
            b.append(" ");
        }

        if ((ops & SelectionKey.OP_WRITE) != 0) {
            b.append("w");
        } else {
            b.append(" ");
        }
        return b.toString();
    }

    public static String join(List<String> elems, String conjunction) {
        if (elems.size() == 0) {
            return new String();
        }

        StringBuilder sb = new StringBuilder();

        Iterator<String> iter = elems.iterator();
        sb.append(iter.next());

        while (iter.hasNext()) {
            sb.append(conjunction);
            sb.append(iter.next());
        }
        return sb.toString();
    }

    /**
     * @return null if a line has yet to be parsed from buf, in which case
     * buf will be compacted with the remainder so that new data can be
     * loaded.  Otherwise, returns a buffer holding the parsed line.  The
     * array backing the buffer is shared, so one must be careful about
     * modifying the returned buffer.
     *
     * Carriage returns might be in the returned string but newlines will not.
     *
     * @throws BufferOverflowException if the line is longer than buf's
     * capacity.  A server will typically send a 414 Request-URI Too Long error
     * as a result.
     */
    public static ByteBuffer parseLine(ByteBuffer buf) throws BufferOverflowException {
        int startPos = buf.position();

        while (buf.hasRemaining()) {
            char ch = (char) buf.get();
            if (ch == '\r') {
                continue;
            }

            if (ch == '\n') {
                ByteBuffer result = buf.duplicate();
                // Exclude the delimiter.
                result.limit(buf.position() - 1);
                result.position(startPos);
                return result;
            }
        }

        // We haven't encountered a newline yet.  See if the buffer space has
        // been exceeded.
        if (startPos == 0 &&
            buf.position() == buf.capacity()) {
            throw new BufferOverflowException();
        }

        buf.position(startPos);
        buf.compact();
        // Signal that more data should be loaded.
        return null;
    }

    /**
     * @return the http minor version (0 for 1.0 or 1 for 1.1).
     * @throws ParseException on error.
     */
    public static int parseHttpVersion(ByteBuffer buf) throws ParseException {
        String word = parseWord(buf);
        if (word.length() == 0) {
            throw new ParseException();
        }
        if (!word.startsWith("HTTP/1.")) {
            throw new ParseException();
        }
        String minorVersionStr = word.substring(7);
        return Integer.parseInt(minorVersionStr);
    }

    /**
     * @throws ParseException if the method name is unknown.
     */
    public static HttpRequest.Method parseMethod(ByteBuffer buf) throws ParseException {
        String methodStr = parseWord(buf);
        if (methodStr.length() == 0) {
            throw new ParseException();
        }
        try {
            return HttpRequest.Method.valueOf(methodStr);
        } catch (IllegalArgumentException e) {
            throw new ParseException();
        }
    }

    /**
     * Parses the next TEXT from buf, where TEXT consists of any OCTET except
     * CTLs.
     *
     * buf's position will be incremented to the position of the separating
     * delimiter or the end of buf if none was found.
     */
    public static String parseText(ByteBuffer buf) {
        ByteBuffer result = buf.duplicate();

        while (buf.hasRemaining()) {
            char ch = (char) buf.get();
            if (isCtl(ch)) {
                buf.position(buf.position() - 1);
                break;
            }
        }

        result.limit(buf.position());
        return byteBufferToString(result);
    }

    /**
     * Parses the next token from buf, where a token consists of any CHAR except
     * CTLs or separators.
     *
     * buf's position will be incremented to that of the separating delimiter.
     * This way, one can distinguish between an empty value and an invalid
     * header without a delimiter.
     */
    public static String parseToken(ByteBuffer buf) {
        ByteBuffer result = buf.duplicate();

        while (buf.hasRemaining()) {
            char ch = (char) buf.get();
            if (isCtl(ch) || isSeparator(ch)) {
                // Set the position to the delimiter.
                buf.position(buf.position() - 1);
                break;
            }
        }

        result.limit(buf.position());
        return byteBufferToString(result);
    }

    /**
     * @return an untrimmed word taken from s at offset until delim is
     * encountered.  The remainder of the string is returned if no delimiter
     * is found.
     */
    private static String parseWordString(String s, char delim, int offset) {
        int endIndex = s.length();
        int delimIndex = s.indexOf(delim, offset);
        if (delimIndex != -1) {
            endIndex = delimIndex;
        }
        return s.substring(offset, endIndex);
    }

    /**
     * Parses the next word from buf, using whitespace ([\t\s\n\r]) as a
     * delimiter.  Repeated whitespace will be trimmed.
     *
     * buf's position will be incremented to one past the separating LWS
     * character.
     *
     * This will return the built-up string (possibly empty) when buf is
     * exhausted.  Thus, it is designed for when buf contains a self-contained
     * line.  Otherwise, the contents will be interpreted as an ASCII value.
     */
    public static String parseWord(ByteBuffer buf) {
        int startPos = buf.position();

        // Eat up all leading whitespace.
        while (buf.hasRemaining()) {
            char ch = (char) buf.get();
            if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') {
                break;
            }
            startPos++;
        }

        ByteBuffer result = buf.duplicate();
        result.position(startPos);

        while (true) {
            // At this point, buf.position() is not LWS due to the prior loop.
            if (!buf.hasRemaining()) {
                // Pass the entire string.
                result.limit(buf.position());
                break;
            }

            char ch = (char) buf.get();
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                // Exclude the whitespace delimiter.
                result.limit(buf.position() - 1);
                break;
            }
        }

        // Support direct buffers by creating an explicit byte buffer.
        return byteBufferToString(result);
    }

    /**
     * Splits the string in buf by delim, trimming any whitespace between
     * elements.
     */
    public static List<String> splitTrim(String s, char delim) {
        return splitTrim(new ArrayList<String>(), s, delim);
    }

    /**
     * @param results is the result set to add to.
     * @param s the string to split.
     * @param delim the delimiter for splitting.
     */
    public static <T extends Collection<String>> T splitTrim(T results, String s, char delim) {
        int index = 0;
        while (index < s.length()) {
            String word = parseWordString(s, delim, index);
            results.add(word.trim());

            int delimIndex = index + word.length();
            if (delimIndex == s.length() - 1) {
                // Handle the special case when nothing follows the final
                // delimiter.
                results.add("");
            }

            // Skip over the delimiter.
            index = delimIndex + 1;
        }

        return results;
    }

    /**
     * Skips all leading CRLFs in buf.
     */
    public static void skipCrlf(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            char ch = (char) buf.get();
            if (ch != '\r' && ch != '\n') {
                // Put back the read character.
                buf.position(buf.position() - 1);
                break;
            }
        }
    }

    public static void skipWhitespace(ByteBuffer buf) {
        while (buf.hasRemaining()) {
            char ch = (char) buf.get();
            if (!isWhitespace(ch)) {
                // Put back the read character.
                buf.position(buf.position() - 1);
                break;
            }
        }
    }

    /**
     * @return the offset of the first non-whitespace char in s after offset,
     * or s.length() if none is found.
     */
    public static int skipWhitespaceString(String s, int offset) {
        for (int ii = offset; ii < s.length(); ii++) {
            char ch = s.charAt(ii);
            if (!isWhitespace(ch)) {
                return ii;
            }
        }
        return s.length();
    }

    /**
     * Seeks backwards from the current limit until a non-whitespace
     * character is encountered.  If buf consists of all whitespace characters,
     * buf.limit() will be set to buf.position().  On success, buf's limit will
     * be set to one past the non-whitespace character.
     */
    public static void skipWhitespaceReverse(ByteBuffer buf) {
        for (int ii = buf.limit() - 1; ii >= buf.position(); ii--) {
            char ch = (char) buf.get(ii);
            if (!isWhitespace(ch)) {
                buf.limit(ii + 1);
                break;
            }
        }
    }

    /**
     * @param offset will be adjusted if it exceeds a valid position within s.
     *
     * @return the offset of the index of the first non-whitespace character
     * found by traversing s from the right beginning at offset (inclusive).
     * Returns -1 if no such index is found.
     */
    public static int skipWhitespaceStringReverse(String s, int offset) {
        if (offset >= s.length()) {
            offset = s.length() - 1;
        }

        for (int ii = offset; ii >= 0; ii--) {
            char ch = s.charAt(ii);
            if (!isWhitespace(ch)) {
                return ii;
            }
        }
        return -1;
    }

    /**
     * Writes s to buf, converting s to UTF-8 format.
     */
    public static void writeUTF8(String s, ByteBuffer buf) {
        buf.put(s.getBytes(sUTF8Charset));
    }
}
