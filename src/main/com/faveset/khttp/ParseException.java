// Copyright 2014, Kevin Ko <kevin@faveset.com>

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
