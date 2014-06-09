// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttpserver;

import java.io.IOException;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;

import com.faveset.flags.Flags;
import com.faveset.flags.BoolFlag;
import com.faveset.flags.StringFlag;
import com.faveset.ssl.CertificateBuilder;
import com.faveset.ssl.KeyStoreBuilder;

public class SSLCertGen {
    private static StringFlag sAliasFlag =
        Flags.registerString("alias", "", "certificate alias");

    private static StringFlag sOutputFlag =
        Flags.registerString("o", "", "output filename");

    private static StringFlag sKeystoreFlag =
        Flags.registerString("keystore", "", "filename for the JKS keystore");

    private static StringFlag sTruststoreFlag =
        Flags.registerString("truststore", "",
                "filename for the JKS truststore that will hold the certificate");

    private static String getConsolePassword(String prompt) {
        System.out.print(prompt);
        String password = System.console().readLine();
        if (password.isEmpty()) {
            password = null;
        }
        return password;
    }

    public static void main(String[] args)
            throws CertificateEncodingException, IOException, KeyStoreException {
        Flags.parse(args);

        if (sOutputFlag.get().isEmpty()) {
            System.out.println("must specify output file");
            return;
        }

        if (sKeystoreFlag.get().isEmpty()) {
            System.out.println("error: keystore file not specified");
            return;
        }

        if (sTruststoreFlag.get().isEmpty()) {
            System.out.println("error: truststore file not specified");
            return;
        }

        KeyPair keyPair = CertificateBuilder.makeKey();

        // Build the certificate and truststore first so that we can use the self-signed certificate
        // for the keystore's certificate chain..
        CertificateBuilder builder = new CertificateBuilder();
        Certificate cert = builder.build(keyPair.getPrivate(), keyPair.getPublic());

        String truststorePass = getConsolePassword("Truststore pass: ");
        KeyStoreBuilder ksBuilder = KeyStoreBuilder.create(truststorePass);
        ksBuilder.setCertificate(sAliasFlag.get(), cert);
        ksBuilder.build(sTruststoreFlag.get());

        // Build the keystore.
        String keystorePass = getConsolePassword("Keystore pass: ");
        ksBuilder = KeyStoreBuilder.create(keystorePass);
        // We are generating a self-certified certificate, so the certificate chain is empty.
        Certificate[] certChain = new Certificate[]{ cert };
        ksBuilder.setPrivateKey(sAliasFlag.get(), keyPair.getPrivate(), certChain);
        ksBuilder.build(sKeystoreFlag.get());
    }
}
