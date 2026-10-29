package com.aboezzat.receiver;

import android.util.Base64;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class ReceiverSessionCrypto {
    private static final int ITERATIONS = 210000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private ReceiverSessionCrypto() {}

    static byte[] randomBytes(int size) {
        byte[] value = new byte[size];
        RANDOM.nextBytes(value);
        return value;
    }

    static byte[] derive(String code, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally { spec.clearPassword(); }
    }

    static String encrypt(byte[] key, byte[] plain) throws Exception {
        byte[] nonce = randomBytes(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] packed = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, packed, 0, nonce.length);
        System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
        return Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    static byte[] decrypt(byte[] key, String packedBase64) throws Exception {
        byte[] packed = Base64.decode(packedBase64, Base64.DEFAULT);
        if (packed.length < 29) throw new IllegalArgumentException("Invalid encrypted payload");
        byte[] nonce = new byte[12];
        byte[] encrypted = new byte[packed.length - 12];
        System.arraycopy(packed, 0, nonce, 0, 12);
        System.arraycopy(packed, 12, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        return cipher.doFinal(encrypted);
    }

    static String b64(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    static byte[] unb64(String value) {
        return Base64.decode(value, Base64.DEFAULT);
    }
}
