// Copyright 2014, Kevin Ko <kevin@faveset.com>. All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.faveset.mahttpd;

import java.util.Date;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DateFormatterTest {
    @Test
    public void test() {
        DateFormatter d = new DateFormatter();
        // Update in millis.
        d.update(1394944106000L);
        assertEquals("15/Mar/2014:23:28:26 -0500", d.getClfString());
        assertEquals("Sat, 15 Mar 2014 23:28:26 CDT", d.getRFC1123String());

        String clf = d.getClfString();
        String rfc = d.getRFC1123String();

        // Check caching.
        d.update(1394944106999L);
        assertEquals(clf, d.getClfString());
        assertEquals(rfc, d.getRFC1123String());

        d.update(1394944107000L);
        assertEquals("15/Mar/2014:23:28:27 -0500", d.getClfString());
        assertEquals("Sat, 15 Mar 2014 23:28:27 CDT", d.getRFC1123String());

        // Check defaults.
        d = new DateFormatter();
        Date cmp = d.getLastDate();
        DateFormatter cmpFormatter = new DateFormatter(cmp.getTime());
        assertEquals(cmpFormatter.getClfString(), d.getClfString());
        assertEquals(cmpFormatter.getRFC1123String(), d.getRFC1123String());
    }
}
