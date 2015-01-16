# Lightweight non-blocking Java HTTP server with SSL support.

Requirements
============
- spongycastle-{core,pkix,prov} (developed with v1.50+)
- JUnit4 (for unit tests)

Compiling
=========

This currently uses a simple `ant` build file.  Simply adjust the
spongycastle-{core,pkix,prov}-jarfile paths near the top of `build.xml`,
and run `ant compile` to build.  Unit tests rely on JUnit4 (see the
`junit-jarfile` property in `build.xml`) and can be run with `ant test`.
Specify `-Ddebug=on` if debugging information is needed.

Benchmarking Notes
==================
When load-testing, adjust the following on the server:

```
$ sysctl -w net.ipv4.tcp_max_syn_backlog=65535
$ echo 0 > /proc/sys/net/ipv4/tcp_syncookies
```

Then, edit (on Ubuntu) `/etc/security/limits.conf` to increase the number of
open files:

```
<username> soft nofile 65535
<username> hard nofile 65535
```

Finally, edit `/etc/sysctl.conf`:

```
fs.file-max = 999999
```

Be sure to restart for changes to take effect.

`com.faveset.khttpserver.HttpServer` implements a simple example that
outputs a static page.  To run:

```
$ java -server -Xmx512m -classpath build/classes/main com.faveset.khttpserver.HttpServer 8080
```

Some results
------------

Client is an Intel Core 2 Duo 2.26ghz connected via 100mbs switch to a Intel Xeon (Harpertown) 3ghz, both run
Ubuntu 14.04.

Client benchmark command:

```
$ ab -n 50000 -c 10000 <url>
```

Go implementation on server for comparison:

```
package main

import (
        "flag"
        "fmt"
        "log"
        "net/http"
)

var flagPort = flag.Int("port", 8080, "port to listen on")

func main() {
        flag.Parse()

        http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
                fmt.Fprintf(w, "<html><body>Hello</body></html>")
        })
        log.Fatal(http.ListenAndServe(fmt.Sprintf(":%d", *flagPort), nil))
}
```

Go (concurrency 500):

```
Concurrency Level:      500
Time taken for tests:   4.670 seconds
Complete requests:      50000
Failed requests:        0
Total transferred:      7350000 bytes
HTML transferred:       1550000 bytes
Requests per second:    10705.72 [#/sec] (mean)
Time per request:       46.704 [ms] (mean)
Time per request:       0.093 [ms] (mean, across all concurrent requests)
Transfer rate:          1536.86 [Kbytes/sec] received

Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:        0   29 202.6      0    3012
Processing:     4   13  16.8     11     422
Waiting:        2   13  16.8     11     422
Total:          8   43 206.8     12    3216

Percentage of the requests served within a certain time (ms)
  50%     12
  66%     12
  75%     13
  80%     14
  90%     16
  95%     19
  98%   1013
  99%   1017
 100%   3216 (longest request)
```

Java (concurrency 500):

```
Concurrency Level:      500
Time taken for tests:   4.867 seconds
Complete requests:      50000
Failed requests:        0
Total transferred:      7600000 bytes
HTML transferred:       1550000 bytes
Requests per second:    10273.68 [#/sec] (mean)
Time per request:       48.668 [ms] (mean)
Time per request:       0.097 [ms] (mean, across all concurrent requests)
Transfer rate:          1525.00 [Kbytes/sec] received

Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:        0   15 152.3      0    3004
Processing:     1    7  41.4      5    1636
Waiting:        1    7  41.4      5    1636
Total:          2   22 170.7      5    4637

Percentage of the requests served within a certain time (ms)
  50%      5
  66%      5
  75%      5
  80%      5
  90%      5
  95%      6
  98%     25
  99%   1003
 100%   4637 (longest request)
```

Go (concurrency 10000):

```
Concurrency Level:      10000
Time taken for tests:   5.135 seconds
Complete requests:      50000
Failed requests:        0
Total transferred:      7350000 bytes
HTML transferred:       1550000 bytes
Requests per second:    9737.94 [#/sec] (mean)
Time per request:       1026.911 [ms] (mean)
Time per request:       0.103 [ms] (mean, across all concurrent requests)
Transfer rate:          1397.93 [Kbytes/sec] received

Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:        0  321 672.6      1    3010
Processing:     4   38  82.2     16    1654
Waiting:        4   38  82.0     16    1653
Total:          7  360 691.5     19    3853

Percentage of the requests served within a certain time (ms)
  50%     19
  66%     29
  75%    386
  80%   1012
  90%   1025
  95%   1224
  98%   3026
  99%   3038
 100%   3853 (longest request)
```

Java (concurrency 10000):

```
Concurrency Level:      10000
Time taken for tests:   6.503 seconds
Complete requests:      50000
Failed requests:        0
Total transferred:      7600000 bytes
HTML transferred:       1550000 bytes
Requests per second:    7688.52 [#/sec] (mean)
Time per request:       1300.641 [ms] (mean)
Time per request:       0.130 [ms] (mean, across all concurrent requests)
Transfer rate:          1141.26 [Kbytes/sec] received

Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:        0   67 335.1      0    3009
Processing:     0   12  59.5      5    3276
Waiting:        0   11  59.4      5    3275
Total:          1   79 353.9      5    6276

Percentage of the requests served within a certain time (ms)
  50%      5
  66%      5
  75%      5
  80%      5
  90%      7
  95%    289
  98%   1006
  99%   1415
 100%   6276 (longest request)
```

Generating keystore/truststore
==============================

`keytool` below is from the JDK.

* Generating a private key in keystore:

`$ keytool -genkeypair -alias certificatekey -keyalg RSA -validity <days> -keystore keystore.jks`

* Verifying the keystore file:

`$ keytool -list -v -keystore keystore.jks`

* Exporting the certificate (for self-signed cert, officially signed certs in prod):

`$ keytool -export -alias certificatekey -keystore keystore.jks -rfc -file selfsignedcert.crt`

* Importing the certificate into the truststore:

`$ keytool -import -alias certificatekey -file selfsignedcert.crt -keystore truststore.jks`

* Verifying the truststore file:

`$ keytool -list -v -keystore truststore.jks`
