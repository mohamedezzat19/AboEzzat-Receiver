package com.aboezzat.receiver;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.security.auth.x500.X500Principal;

/**
 * Stable, per-install TLS identity for the receiver.
 *
 * The private key is generated and retained by Android Keystore. Only the
 * public certificate fingerprint is exposed for out-of-band verification.
 */
public final class ReceiverTlsIdentity {
    private static final String KEY_ALIAS = "AboEzzatReceiver.TlsIdentity.v1";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";

    private ReceiverTlsIdentity() { }

    private static synchronized KeyStore loadIdentityStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            long now = System.currentTimeMillis();
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,\n                            KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(new X500Principal("CN=AboEzzat Receiver"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(new Date(now - 24L * 60L * 60L * 1000L))
                    .setCertificateNotAfter(new Date(now + 10L * 365L * 24L * 60L * 60L * 1000L))
                    .setUserAuthenticationRequired(false)
                    .build();
            generator.initialize(spec);
            generator.generateKeyPair();
            keyStore.load(null);
        }
        return keyStore;
    }

    private static X509Certificate getCertificate() throws Exception {
        Certificate certificate = loadIdentityStore().getCertificate(KEY_ALIAS);
        if (!(certificate instanceof X509Certificate)) {
            throw new IllegalStateException("Receiver TLS certificate is unavailable");
        }
        X509Certificate x509 = (X509Certificate) certificate;
        x509.checkValidity();
        return x509;
    }

    /** SHA-256 of the complete DER certificate, encoded as unpadded Base64URL. */
    public static String getCertificateFingerprint() throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(getCertificate().getEncoded());
        return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /** Creates and binds the TLS-only listener. Plain HTTP is not accepted here. */
    public static synchronized ServerSocket createServerSocket(int port) throws Exception {
        KeyStore identity = loadIdentityStore();
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(identity, null);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());

        SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket();
        String[] supported = server.getSupportedProtocols();
        boolean tls13 = Arrays.asList(supported).contains("TLSv1.3");
        boolean tls12 = Arrays.asList(supported).contains("TLSv1.2");
        if (tls13 && tls12) {
            server.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        } else if (tls12) {
            server.setEnabledProtocols(new String[]{"TLSv1.2"});
        } else {
            server.close();
            throw new IllegalStateException("No supported secure TLS protocol is available");
        }
        server.setReuseAddress(true);
        server.setNeedClientAuth(false);
        server.bind(new InetSocketAddress(port));
        return server;
    }
}
