package com.avnishgamedev.tvcompanioncontroller.pairing;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.UUID;

public class SslUtil {

    public static KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator kg = KeyPairGenerator.getInstance("RSA");
        kg.initialize(2048);
        return kg.generateKeyPair();
    }

    public static KeyStore getEmptyKeyStore() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        return ks;
    }

    public static X509Certificate generateX509V3Certificate(KeyPair pair, String commonName, Date notBefore, Date notAfter, BigInteger serialNumber) throws GeneralSecurityException {
        try {
            X500Name subject = new X500Name(commonName);
            
            // Using JcaX509v3CertificateBuilder (modern BouncyCastle)
            X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    subject,
                    serialNumber,
                    notBefore,
                    notAfter,
                    subject,
                    pair.getPublic()
            );

            // Add Extensions
            // Basic Constraints
            certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

            // Key Usage
            certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(
                    KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.keyCertSign));

            // Extended Key Usage
            certBuilder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));

            // Signer
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(pair.getPrivate());

            return new JcaX509CertificateConverter().getCertificate(certBuilder.build(contentSigner));

        } catch (Exception e) {
            throw new GeneralSecurityException(e);
        }
    }
}
