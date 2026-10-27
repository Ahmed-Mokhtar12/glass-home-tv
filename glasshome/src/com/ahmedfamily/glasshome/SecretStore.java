package com.ahmedfamily.glasshome;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps a secret (the OpenRouter key) encrypted in the app's settings with a key that never leaves the
 * TV's Android Keystore. Never log what this returns.
 */
final class SecretStore {
    private static final String ALIAS = "glasshome-secrets";

    private SecretStore() { }

    static void put(SharedPreferences prefs, String name, String value) {
        if (value == null || value.isEmpty()) { prefs.edit().remove(name).apply(); return; }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] sealed = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            prefs.edit().putString(name, Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(sealed, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't store the key safely", e);
        }
    }

    /** The secret, or "" when none is saved or it can't be read. */
    static String get(SharedPreferences prefs, String name) {
        String stored = prefs.getString(name, "");
        int colon = stored.indexOf(':');
        if (colon < 0) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(stored.substring(0, colon), Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(stored.substring(colon + 1), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return g.generateKey();
    }
}
