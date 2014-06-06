// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp.ssl;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;

/**
 * This manages a keystore in JKS format.
 */
public class KeyStoreBuilder {
    private static String sKeyStoreType = "JKS";

    private KeyStore mKeyStore;

    private String mPassword;

    /**
     * @param password can be null if no password is desired
     *
     * @throws KeyStoreException on error.
     */
    public static KeyStoreBuilder create(String password)
            throws IOException, KeyStoreException {
        KeyStore keyStore = KeyStore.getInstance(sKeyStoreType);

        char[] passChars = null;
        if (password != null) {
            passChars = password.toCharArray();
        }

        try {
            keyStore.load(null, passChars);
        } catch (GeneralSecurityException e) {
            throw new KeyStoreException(e);
        }
        return new KeyStoreBuilder(keyStore, password);
    }

    public static KeyStoreBuilder open(String filename, String password)
            throws IOException, KeyStoreException {
        FileInputStream input = new FileInputStream(filename);
        try {
            return open(input, password);
        } finally {
            input.close();
        }
    }

    public static KeyStoreBuilder open(InputStream input, String password)
            throws IOException, KeyStoreException {
        KeyStore keyStore = KeyStore.getInstance(sKeyStoreType);

        char[] passChars = null;
        if (password != null) {
            passChars = password.toCharArray();
        }

        try {
            keyStore.load(input, passChars);
        } catch (GeneralSecurityException e) {
            throw new KeyStoreException(e);
        }

        return new KeyStoreBuilder(keyStore, password);
    }

    private KeyStoreBuilder(KeyStore keyStore, String password) {
        mKeyStore = keyStore;
        mPassword = password;
    }

    public void build(String outFilename) throws IOException, KeyStoreException {
        FileOutputStream out = new FileOutputStream(outFilename);
        build(out);
        out.close();
    }

    public void build(OutputStream out) throws IOException, KeyStoreException {
        char[] passChars = null;
        if (mPassword != null) {
            passChars = mPassword.toCharArray();
        }

        try {
            mKeyStore.store(out, passChars);
        } catch (GeneralSecurityException e) {
            throw new KeyStoreException(e);
        }
    }

    public KeyStoreBuilder setCertificate(String alias, Certificate cert) throws KeyStoreException {
        mKeyStore.setCertificateEntry(alias, cert);
        return this;
    }

    /**
     * @param password set to null for no password
     */
    public KeyStoreBuilder setPassword(String password) {
        mPassword = password;
        return this;
    }
}
