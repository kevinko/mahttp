// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.khttp;

class ParseException extends Exception {
    private static final long serialVersionUID = 1L;

    public ParseException() {
        super();
    }

    public ParseException(String reason) {
        super(reason);
    }
}
