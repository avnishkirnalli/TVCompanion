package com.avnishgamedev.tvcompanioncontroller.pairing;

import android.content.Context;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.UUID;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class KeyStoreManager {
    private static final String KEYSTORE_FILENAME = "androidtv.keystore";
    private static final char[] KEYSTORE_PASSWORD = "KeyStore_Password".toCharArray();
    private static final String CLIENT_ALIAS = "tvcompanion-client";

    private final Context context;
    private KeyStore keyStore;

    public KeyStoreManager(Context context) {
        this.context = context;
        initialize();
    }

    private void initialize() {
        try {
            // Ensure BouncyCastle is registered
            if (java.security.Security.getProvider("BC") == null) {
                java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            }

            keyStore = KeyStore.getInstance("BKS");
            try (FileInputStream fis = context.openFileInput(KEYSTORE_FILENAME)) {
                keyStore.load(fis, KEYSTORE_PASSWORD); // Password required for BKS
            } catch (IOException e) {
                // Keystore not found, create new
                keyStore.load(null, KEYSTORE_PASSWORD);
            }

            if (!keyStore.containsAlias(CLIENT_ALIAS)) {
                createIdentity(CLIENT_ALIAS);
                saveKeyStore();
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize KeyStore", e);
        }
    }

    private void createIdentity(String alias) throws GeneralSecurityException {
        KeyPair keyPair = SslUtil.generateRsaKeyPair();
        String commonName = "CN=atvremote/" + UUID.randomUUID().toString();
        
        Date startDate = new Date(); // Now
        Date expiryDate = new Date(System.currentTimeMillis() + (10L * 365 * 24 * 60 * 60 * 1000)); // 10 years
        BigInteger serialNumber = BigInteger.valueOf(Math.abs(System.currentTimeMillis()));

        X509Certificate cert = SslUtil.generateX509V3Certificate(
                keyPair, commonName, startDate, expiryDate, serialNumber);

        keyStore.setKeyEntry(alias, keyPair.getPrivate(), KEYSTORE_PASSWORD, new Certificate[]{cert});
    }

    private void saveKeyStore() throws GeneralSecurityException, IOException {
        try (FileOutputStream fos = context.openFileOutput(KEYSTORE_FILENAME, Context.MODE_PRIVATE)) {
            keyStore.store(fos, KEYSTORE_PASSWORD);
        }
    }

    public KeyStore getKeyStore() {
        return keyStore;
    }

    public KeyManager[] getKeyManagers() throws GeneralSecurityException {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD);
        return kmf.getKeyManagers();
    }
    
    public TrustManager[] getTrustManagers() {
         return new TrustManager[] {
             new X509TrustManager() {
                 public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                 public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                 public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
             }
         };
    }
    
    public SSLContext createSSLContext() throws GeneralSecurityException {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(getKeyManagers(), getTrustManagers(), new java.security.SecureRandom());
        return sslContext;
    }
}
