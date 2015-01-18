// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

class InvalidRequestException extends Exception {
    private static final long serialVersionUID = 1L;

    private int mErrorCode;

    public InvalidRequestException(String reason) {
        super(reason);
    }

    public InvalidRequestException(String reason, int errorCode) {
        super(reason);
        mErrorCode = errorCode;
    }

    /**
     * @return The error code or 0 if none.
     */
    public int getErrorCode() {
        return mErrorCode;
    }
}
