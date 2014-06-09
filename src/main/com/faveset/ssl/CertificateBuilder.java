// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.ssl;

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
import java.security.PublicKey;
import java.security.Security;
import java.security.SecureRandom;
import java.security.Signature;

import java.util.Date;

import org.spongycastle.asn1.ASN1ObjectIdentifier;
import org.spongycastle.asn1.x509.AlgorithmIdentifier;
import org.spongycastle.asn1.x509.SubjectPublicKeyInfo;
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.cert.X509CertificateHolder;
import org.spongycastle.cert.X509v3CertificateBuilder;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.operator.ContentSigner;

public class CertificateBuilder {
    public static class DistinguishedName {
        private static final String sDetailDefault = "Unknown";
        private static String[] sLabels = {
            "C",
            "ST",
            "L",
            "O",
            "OU",
            "CN",
            "emailAddress",
        };

        private String mCommonName = sDetailDefault;
        private String mCountry = sDetailDefault;
        private String mEmail = sDetailDefault;
        private String mLocality = sDetailDefault;
        private String mOrgName = sDetailDefault;
        private String mOrgUnit = sDetailDefault;
        private String mState = sDetailDefault;

        // This must correspond to the order in sLabels.
        private String[] mComponents = new String[]{
            mCountry,
            mState,
            mLocality,
            mOrgName,
            mOrgUnit,
            mCommonName,
            mEmail,
        };

        public DistinguishedName() {
        }

        /**
         * Set to null to omit on output.
         */
        public DistinguishedName setCommonName(String name) {
            mCommonName = name;
            return this;
        }

        /**
         * Set to null to omit on output.
         */
        public DistinguishedName setCountry(String name) {
            mCountry = name;
            return this;
        }

        /**
         * Set to null to omit on output.
         */
        public DistinguishedName setEmail(String email) {
            mEmail = email;
            return this;
        }

        /**
         * Set to null to omit on output.
         */
        public DistinguishedName setLocality(String locality) {
            mLocality = locality;
            return this;
        }

        /**
         * Set to null to omit on output.
         */
        public DistinguishedName setOrgName(String orgName) {
            mOrgName = orgName;
            return this;
        }

        /**
         * Set to null to omit on output.
         */
        public DistinguishedName setOrgUnit(String orgUnit) {
            mOrgUnit = orgUnit;
            return this;
        }

        /**
         * Set to null to omit on output.
         */
        public DistinguishedName setState(String state) {
            mState = state;
            return this;
        }

        public String toString() {
            StringBuilder builder = new StringBuilder();

            boolean first = true;

            for (int ii = 0; ii < mComponents.length; ii++) {
                String elem = mComponents[ii];
                if (elem == null) {
                    continue;
                }

                if (first) {
                    first = false;
                } else {
                    builder.append(", ");
                }

                String label = sLabels[ii];
                builder.append(label + "=" + elem);
            }

            return builder.toString();
        }
    }

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

    private DistinguishedName mIssuer;

    private DistinguishedName mSubject;

    private BigInteger mSerial;

    private Date mNotBefore;

    private Date mNotAfter;

    /**
     * Generates a 2048-bit RSA KeyPair suitable for signing a Certificate.
     */
    public static KeyPair makeKey() {
        return makeKey(sKeySizeDefault);
    }

    /**
     * Generates an RSA KeyPair suitable for signing a Certificate.
     *
     * @param keySize key size in bits.  This should be 1024 or 2048.
     */
    public static KeyPair makeKey(int keySize) {
        KeyPairGenerator gen;
        try {
            gen = KeyPairGenerator.getInstance(sKeyAlgorithm);
        } catch (NoSuchAlgorithmException e) {
            // RSA algorithm always exists.
            throw new RuntimeException(e);
        }
        gen.initialize(keySize);

        return gen.generateKeyPair();
    }

    public CertificateBuilder() {
        long now = System.currentTimeMillis();

        mIssuer = new DistinguishedName();
        mSubject = new DistinguishedName();

        mSerial = new BigInteger(64, new SecureRandom());

        mNotBefore = new Date(now);
        mNotAfter = new Date(now + sDefaultExpireMillis);
    }

    /**
     * Generates a certificate using the signKey and publicKey.  To generate a self-signed
     * certificate, generate a KeyPair and pass the private and public components as parameters.
     *
     * @param signKey Private issuer key used to sign the certificate.
     * @param certKey Public key that will be contained within the certificate.
     *
     * @throws IllegalArgumentException if an invalid keysize was specified.
     * @throws IOException
     */
    public Certificate build(PrivateKey signKey, PublicKey certKey)
            throws IOException, IllegalArgumentException {
        X500Name issuer = new X500Name(mIssuer.toString());
        X500Name subject = new X500Name(mSubject.toString());
        SubjectPublicKeyInfo pubKeyInfo =
            SubjectPublicKeyInfo.getInstance(certKey.getEncoded());

        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(issuer, mSerial,
            mNotBefore, mNotAfter, subject, pubKeyInfo);

        // Sign the certificate with its own key to make it self-signed.
        byte[] certBytes = builder.build(new Signer(signKey)).getEncoded();

        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

            return certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
        } catch (GeneralSecurityException e) {
            // X.509 support always exists.  Moreover, the builder will always generate a valid
            // X.509 certificate.
            throw new RuntimeException(e);
        }
    }

    public CertificateBuilder.DistinguishedName getIssuer() {
        return mIssuer;
    }

    public CertificateBuilder.DistinguishedName getSubject() {
        return mSubject;
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
     * The default serial number is a 64-bit random value generated by SecureRandom.
     */
    public CertificateBuilder setSerial(BigInteger serial) {
        mSerial = serial;
        return this;
    }

    static {
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }
}
