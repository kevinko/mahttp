// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.text.SimpleDateFormat;
import java.util.Date;

class DateFormatter {
    // Common log format.
    private SimpleDateFormat mClfFormat =
        new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z");

    private SimpleDateFormat mRFC1123Format =
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");

    // Date of last update.
    private Date mLastDate;

    // Seconds since epoch for mLastDate.
    private long mLastSecs;

    // Cached date string in CLF format.
    private String mClfCache;

    // Cached date string in RFC1123 format.
    private String mRfcCache;

    /**
     * The DateFormatter will be initialized to current time by default.
     * Call update() to update the internal time and cached values.
     */
    public DateFormatter() {
        this(System.currentTimeMillis());
    }

    /**
     * @param timeMillis the time in millis since the epoch to use as the
     * initial date for the DateFormatter.
     */
    public DateFormatter(long timeMillis) {
        mLastDate = new Date(timeMillis);
        mLastSecs = mLastDate.getTime() / 1000;
        mClfCache = mClfFormat.format(mLastDate);
        mRfcCache = mRFC1123Format.format(mLastDate);
    }

    /**
     * @return a string in CLF date format for the date from the last update().
     */
    public String getClfString() {
        return mClfCache;
    }

    public Date getLastDate() {
        return mLastDate;
    }

    /**
     * @return a string in RFC1123 date format for the date from the last
     * update().
     */
    public String getRFC1123String() {
        return mRfcCache;
    }

    /**
     * Updates the internal time using the current time.
     */
    public void update() {
        update(System.currentTimeMillis());
    }

    /**
     * Updates the internal time using the given time in millis since the epoch.
     *
     * @param timeMillis time in milliseconds.
     */
    public void update(long timeMillis) {
        long secsNow = (timeMillis / 1000);
        if (secsNow == mLastSecs) {
            // The cached values are correct.
            return;
        }

        // Otherwise, update the cached values.
        mLastDate.setTime(timeMillis);
        mLastSecs = secsNow;

        mClfCache = mClfFormat.format(mLastDate);
        mRfcCache = mRFC1123Format.format(mLastDate);
    }
}
