# Lightweight non-blocking Java HTTP server with SSL support.

Requirements:
- spongycastle-{core,pkix,prov} (developed with v1.50+)
- JUnit4 (for unit tests)

When load-testing, adjust the following on the server:

```
$ sysctl -w net.ipv4.tcp_max_syn_backlog=65535
$ echo 0 > /proc/sys/net/ipv4/tcp_syncookies
```

`com.faveset.khttpserver.HttpServer` implements a simple example that
outputs a static page.  To run:

```
$ java -server -Xmx512m -classpath build/classes/main com.faveset.khttpserver.HttpServer 8080
```

Generating keystore and truststore files:

* Generating a private key in keystore.

Use keytool from the JDK.

`$ keytool -genkeypair -alias certificatekey -keyalg RSA -validity <days> -keystore keystore.jks`

* Verify the keystore file.

`$ keytool -list -v -keystore keystore.jks`

* Export the certificate (for self-signed cert).  Use officially signed certs in prod.

`$ keytool -export -alias certificatekey -keystore keystore.jks -rfc -file selfsignedcert.crt`

* Import the certificate into the truststore.

`$ keytool -import -alias certificatekey -file selfsignedcert.crt -keystore truststore.jks`

* Verify the truststore file.

`$ keytool -list -v -keystore truststore.jks`
