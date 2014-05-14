// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.io.InputStream;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

public class SSLUtils {
    /**
     * This creates and initializes an SSLContext, using the keystore and trust store from the
     * given streams.
     *
     * @param keyStoreStream
     * @param trustStoreStream
     * @param password password for the keystore
     *
     * @throws CertificateException
     * @throws IOException
     * @throws KeyManagementException
     * @throws KeyStoreException
     * @throws UnrecoverableKeyException if a key password is incorrect.
     */
    public static SSLContext makeSSLContext(InputStream keyStoreStream,
            InputStream trustStoreStream, String password)
            throws CertificateException, IOException,
            KeyStoreException, KeyManagementException, UnrecoverableKeyException {
        char[] passwordChars = password.toCharArray();

        KeyStore keyStore = KeyStore.getInstance("JKS");
        KeyStore trustStore = KeyStore.getInstance("JKS");

        try {
            keyStore.load(keyStoreStream, passwordChars);
            trustStore.load(trustStoreStream, passwordChars);
        } catch (NoSuchAlgorithmException e) {
            // We expect the keystore format to be compatible with those
            // built into the Java runtime.
            throw new RuntimeException(e);
        }

        KeyManagerFactory keyManFactory;
        TrustManagerFactory trustManFactory;

        try {
            keyManFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManFactory.init(keyStore, passwordChars);

            trustManFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManFactory.init(trustStore);
        } catch (NoSuchAlgorithmException e) {
            // We use default algorithms, so they must exist.
            throw new RuntimeException(e);
        }

        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLS");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        ctx.init(keyManFactory.getKeyManagers(), trustManFactory.getTrustManagers(), null);

        return ctx;
    }
}
