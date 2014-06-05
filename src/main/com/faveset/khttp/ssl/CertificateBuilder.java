// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp.ssl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.math.BigInteger;

import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;

import java.util.Date;

import org.spongycastle.asn1.ASN1ObjectIdentifier;
import org.spongycastle.asn1.x509.AlgorithmIdentifier;
import org.spongycastle.asn1.x509.SubjectPublicKeyInfo;
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.cert.X509CertificateHolder;
import org.spongycastle.cert.X509v3CertificateBuilder;
import org.spongycastle.operator.ContentSigner;

public class CertificateBuilder {
    private static class Signer implements ContentSigner {
        private static final String sSigAlgorithm = "SHA256withRSA";

        // OID for sha256WithRSAEncryption
        private static final String sSigAlgorithmOid = "1.2.840.113549.1.1.11";

        private static final AlgorithmIdentifier sSigAlgorithmIdentifier =
            new AlgorithmIdentifier(new ASN1ObjectIdentifier(sSigAlgorithmOid));

        private ByteArrayOutputStream mOutputStream;

        private Signature mSignature;

        public Signer(PrivateKey privKey) throws IllegalArgumentException {
            mOutputStream = new ByteArrayOutputStream();

            try {
                mSignature = Signature.getInstance(sSigAlgorithm);
                mSignature.initSign(privKey);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return sSigAlgorithmIdentifier;
        }

        @Override
        public OutputStream getOutputStream() {
            return mOutputStream;
        }

        @Override
        public byte[] getSignature() {
            try {
                mSignature.update(mOutputStream.toByteArray());
                return mSignature.sign();
            } catch (GeneralSecurityException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final String sKeyAlgorithm = "RSA";

    // 2048 bit keys.
    private static final int sKeySizeDefault = 2048;

    // 1 year in millis.  By default, mNotAfter will be sent to now + sDefaultExpireMillis.
    private static long sDefaultExpireMillis = 365 * 24 * 60 * 60 * 1000L;

    private int mKeySize;

    private String mIssuer;

    private String mSubject;

    private BigInteger mSerial = BigInteger.ZERO;

    private Date mNotBefore;

    private Date mNotAfter;

    public CertificateBuilder() {
        long now = System.currentTimeMillis();

        mKeySize = sKeySizeDefault;

        mNotBefore = new Date(now);
        mNotAfter = new Date(now + sDefaultExpireMillis);
    }

    /**
     * @throws IllegalArgumentException if an invalid keysize was specified.
     * @throws IOException
     */
    public Certificate build() throws IOException, IllegalArgumentException {
        // Generate a public key pair for the issuer.
        KeyPairGenerator gen;
        try {
            gen = KeyPairGenerator.getInstance(sKeyAlgorithm);
        } catch (NoSuchAlgorithmException e) {
            // RSA algorithm always exists.
            throw new RuntimeException(e);
        }
        gen.initialize(mKeySize);

        KeyPair keyPair = gen.generateKeyPair();

        X500Name issuer = new X500Name(mIssuer);
        X500Name subject = new X500Name(mSubject);
        SubjectPublicKeyInfo pubKeyInfo =
            SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());

        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(issuer, mSerial,
            mNotBefore, mNotAfter, subject, pubKeyInfo);

        byte[] certBytes = builder.build(new Signer(keyPair.getPrivate())).getEncoded();

        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

            return certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
        } catch (GeneralSecurityException e) {
            // X.509 support always exists.  Moreover, the builder will always generate a valid
            // X.509 certificate.
            throw new RuntimeException(e);
        }
    }

    public CertificateBuilder setIssuer(String issuer) {
        mIssuer = issuer;
        return this;
    }

    public CertificateBuilder setNotAfter(Date notAfter) {
        mNotAfter = notAfter;
        return this;
    }

    public CertificateBuilder setNotBefore(Date notBefore) {
        mNotBefore = notBefore;
        return this;
    }

    /**
     * RSA key size in bits.
     *
     * @param numBits must be 1024 or 2048.
     */
    public CertificateBuilder setKeySize(int numBits) {
        mKeySize = numBits;
        return this;
    }

    public CertificateBuilder setSerial(BigInteger serial) {
        mSerial = serial;
        return this;
    }

    public CertificateBuilder setSubject(String subject) {
        mSubject = subject;
        return this;
    }
}
