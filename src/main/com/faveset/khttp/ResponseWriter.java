// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;

import java.util.HashMap;
import java.util.Map;

// TODO:
// add file sending and chunked transfers.
class ResponseWriter implements HttpResponseWriter {
    public interface OnSendCallback {
        void onSend();
    }

    // 1.1 is the default version.
    private static final int sHttpMinorVersionDefault = 1;

    private static final Map<Integer, String> sReasonMap;
    // This is the default reason that will be reported if one is not found
    // in sReasonMap.
    private static final String sUnknownReason = "Unknown";

    private HeadersBuilder mHeadersBuilder;

    private ByteBufferArrayBuilder mBufBuilder;

    private OnSendCallback mSendCallback;

    private AsyncConnection.OnSendCallback mNbcSendCallback;

    // Tracks whether writeHeader() has been called explicitly.
    private boolean mWroteHeaders;

    private int mHttpMinorVersion;

    // HTTP status code for the response.
    private int mStatus;

    // Tracks the number of bytes sent for the result.
    private long mSentCount;

    // Designates that the connection should be closed after the response
    // is sent.
    private boolean mCloseConnection;

    /**
     * The default constructor uses heap-based ByteBuffers internally.
     *
     * One must call close() to clean up resources when the ResponseWriter
     * is no longer needed.
     */
    public ResponseWriter() {
        this(new NullByteBufferPool(Constants.BYTE_BUFFER_SIZE, false));
    }

    /**
     * One must call close() to clean up resources when the ResponseWriter
     * is no longer needed.
     */
    public ResponseWriter(Pool<ByteBuffer> pool) {
        mHeadersBuilder = new HeadersBuilder();

        mBufBuilder = new ByteBufferArrayBuilder(pool);

        // This is called on completion of a AsyncConnection send.
        mNbcSendCallback = new AsyncConnection.OnSendCallback() {
            @Override
            public void onSend(AsyncConnection conn) {
                // Clean up the ByteBufferArrayBuilder in preparation for
                // future sends.
                mBufBuilder.clear();

                mSendCallback.onSend();
            }
        };

        mHttpMinorVersion = sHttpMinorVersionDefault;
        mStatus = HttpStatus.OK;
    }

    /**
     * Resets the ResponseWriter state so that it can be reused.
     */
    public void clear() {
        mHeadersBuilder.clear();

        mBufBuilder.clear();

        mWroteHeaders = false;
        mHttpMinorVersion = sHttpMinorVersionDefault;
        mStatus = HttpStatus.OK;
        mSentCount = 0;

        mCloseConnection = false;
    }

    /**
     * This must be called to release internal resources.
     */
    public void close() {
        mBufBuilder.close();
    }

    /**
     * NOTE: the Connection header will not take effect.
     * Use setCloseConnection() instead.
     */
    @Override
    public HeadersBuilder getHeadersBuilder() {
        return mHeadersBuilder;
    }

    public boolean getCloseConnection() {
        return mCloseConnection;
    }

    /**
     * @return the number of bytes sent for the response.
     */
    public long getSentCount() {
        return mSentCount;
    }

    /**
     * @return the HTTP status code for the response.
     */
    public int getStatus() {
        return mStatus;
    }

    /**
     * Finalizes the response and sends it over the connection.  This manages
     * AsyncConnection callbacks until completion and then calls
     * callback when sending is done.
     *
     * This method is not idempotent.  The response body will be cleared as
     * a result.
     *
     * The callback should clear the ResponseWriter's state before reusing
     * the ResponseWriter.
     */
    public void send(AsyncConnection conn, OnSendCallback callback) {
        mSendCallback = callback;

        // We just support Content-length for now.
        // TODO: this will need to be changed if we support transfer encodings.
        Long bodyCount = mBufBuilder.remaining();
        mHeadersBuilder.set(HeaderField.Entity.CONTENT_LENGTH, bodyCount.toString());

        // Configure the Connection header if we are closing the connection.
        if (mCloseConnection) {
            mHeadersBuilder.set(HeaderField.General.CONNECTION, HeaderToken.CLOSE);
        }

        ByteBufferArrayBuilder.Inserter inserter = mBufBuilder.insertFront();
        try {
            writeStatusHeaders(inserter, mStatus);
        } finally {
            inserter.close();
        }

        long remCount = mBufBuilder.remaining();
        mSentCount = remCount;

        // mNbcSendCallback cleans up the mBufBuilder.
        ByteBuffer[] bufs = mBufBuilder.build();
        conn.send(mNbcSendCallback, bufs, remCount);
    }

    @Override
    public void setCloseConnection(boolean close) {
        mCloseConnection = close;
    }

    /**
     * Configures the ResponseWriter to use an HTTP minor version of
     * minorVersion.  Major version will always be 1.
     *
     * @return this for chaining.
     */
    public ResponseWriter setHttpMinorVersion(int minorVersion) {
        mHttpMinorVersion = minorVersion;
        return this;
    }

    /**
     * Uses buf as the data for the HTTP reply.
     *
     * This will implicitly call writeHeader with status OK if not already
     * performed by the caller.
     */
    @Override
    public void write(ByteBuffer buf) {
        writeHeader(HttpStatus.OK);

        mBufBuilder.writeBuffer(buf);
    }

    @Override
    public void write(String s) {
        writeHeader(HttpStatus.OK);

        mBufBuilder.writeString(s);
    }

    /**
     * Designates that the response header should contain the given status
     * code.  If not called, the other write methods will call this implicitly
     * with status code HttpStatus.OK.
     *
     * Response headers in the HeadersBuilder will take effect on send, so
     * they can still be modified after this method is called.
     *
     * Only the first call to this will take precedence.
     */
    @Override
    public void writeHeader(int statusCode) {
        if (mWroteHeaders) {
            return;
        }

        mStatus = statusCode;
        mWroteHeaders = true;
    }

    /**
     * Writes the Status-Line and Headers in wire format to the inserter.
     */
    private void writeStatusHeaders(ByteBufferArrayBuilder.Inserter inserter, int statusCode) {
        String reason = sReasonMap.get(statusCode);
        if (reason == null) {
            reason = sUnknownReason;
        }

        String statusLine = String.format("HTTP/1.%d %d, %s\r\n",
                mHttpMinorVersion, statusCode, reason);
        inserter.writeString(statusLine);

        mHeadersBuilder.write(inserter);

        // Terminal CRLF.
        inserter.writeString(Strings.CRLF);

        // We're now ready for the message-body.
    }

    static {
        // These are taken from RFC2616 recommendations.
        sReasonMap = new HashMap<Integer, String>();
        sReasonMap.put(100, "Continue");
        sReasonMap.put(101, "Switching Protocols");
        sReasonMap.put(200, "OK");
        sReasonMap.put(201, "Created");
        sReasonMap.put(202, "Accepted");
        sReasonMap.put(203, "Non-Authoritative Information");
        sReasonMap.put(204, "No Content");
        sReasonMap.put(205, "Reset Content");
        sReasonMap.put(206, "Partial Content");
        sReasonMap.put(300, "Multiple Choices");
        sReasonMap.put(301, "Moved Permanently");
        sReasonMap.put(302, "Found");
        sReasonMap.put(303, "See Other");
        sReasonMap.put(304, "Not Modified");
        sReasonMap.put(305, "Use Proxy");
        sReasonMap.put(307, "Temporary Redirect");
        sReasonMap.put(400, "Bad Request");
        sReasonMap.put(401, "Unauthorized");
        sReasonMap.put(402, "Payment Required ");
        sReasonMap.put(403, "Forbidden");
        sReasonMap.put(404, "Not Found");
        sReasonMap.put(405, "Method Not Allowed");
        sReasonMap.put(406, "Not Acceptable");
        sReasonMap.put(407, "Proxy Authentication Required");
        sReasonMap.put(408, "Request Time-out");
        sReasonMap.put(409, "Conflict");
        sReasonMap.put(410, "Gone");
        sReasonMap.put(411, "Length Required");
        sReasonMap.put(412, "Precondition Failed");
        sReasonMap.put(413, "Request Entity Too Large");
        sReasonMap.put(414, "Request-URI Too Large");
        sReasonMap.put(415, "Unsupported Media Type");
        sReasonMap.put(416, "Requested range not satisfiable");
        sReasonMap.put(417, "Expectation Failed");
        sReasonMap.put(500, "Internal Server Error");
        sReasonMap.put(501, "Not Implemented");
        sReasonMap.put(502, "Bad Gateway");
        sReasonMap.put(503, "Service Unavailable");
        sReasonMap.put(504, "Gateway Time-out");
        sReasonMap.put(505, "HTTP Version not supported");
    }
}
