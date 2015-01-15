Benchmarking:

- Host:

$ sysctl -w net.ipv4.tcp_max_syn_backlog=65535
$ echo 0 > /proc/sys/net/ipv4/tcp_syncookies

Run:

$ java -server -Xmx512m -classpath build/classes/main com.faveset.khttpserver.HttpServer 8080

Generating keystore and truststore files:

1. Generating a private key in keystore.

Use keytool from the JDK.

$ keytool -genkeypair -alias certificatekey -keyalg RSA -validity <days> -keystore keystore.jks

2. Verify the keystore file.

$ keytool -list -v -keystore keystore.jks

3. Export the certificate (for self-signed cert).  Use officially signed certs in prod.

$ keytool -export -alias certificatekey -keystore keystore.jks -rfc -file selfsignedcert.crt

4. Import the certificate into the truststore.

$ keytool -import -alias certificatekey -file selfsignedcert.crt -keystore truststore.jks

5. Verify the truststore file.

$ keytool -list -v -keystore truststore.jks
