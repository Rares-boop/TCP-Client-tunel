package com.example.tcpclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import chat.CryptoHelper;

public class ClientKeyManager {
    private static final String PREF_FILE_NAME = "secure_chat_keys";
    private SharedPreferences securePrefs;

    public ClientKeyManager(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            this.securePrefs = EncryptedSharedPreferences.create(
                    context,
                    PREF_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            this.securePrefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
        }
    }

    public boolean hasKey(int chatId) {
        return securePrefs.contains(String.valueOf(chatId));
    }

    public void saveKey(int chatId, String base64Key) {
        securePrefs.edit().putString(String.valueOf(chatId), base64Key).apply();
    }

    public SecretKey getKey(int chatId) {
        String base64 = securePrefs.getString(String.valueOf(chatId), null);
        if (base64 == null) return null;

        byte[] decoded = Base64.decode(base64, Base64.NO_WRAP);
        return new SecretKeySpec(decoded, "AES");
    }

    public String generateAndSaveKey(int chatId) {
        try {
            SecretKey key = CryptoHelper.generateAESKey(256);
            String base64 = Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP);

            saveKey(chatId, base64);
            return base64;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}