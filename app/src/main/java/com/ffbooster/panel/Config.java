package com.ffbooster.panel;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;
import android.provider.Settings;
import android.content.Context;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class Config {
    private static final String PREFS_NAME = "FFBoosterConfig";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_LAST_UPDATE = "last_update";
    
    private static final String DEFAULT_GITHUB_CONFIG = "https://raw.githubusercontent.com/Oxide-ox/oxide/main/x.json";
    private static final String SERVER_URL_BACKUP = "http://192.168.1.100:3000";
    private static final String DEFAULT_USERNAME = "kael_Xz";
    
    private static SharedPreferences prefs;
    private static SecretKey encryptionKey;
    
    public static void init(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initEncryption();
        syncConfigFromGithub();
    }
    
    private static void initEncryption() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(128);
            encryptionKey = keyGen.generateKey();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static String getServerUrl() {
        String url = prefs.getString(KEY_SERVER_URL, SERVER_URL_BACKUP);
        if (url == null || url.isEmpty()) {
            url = SERVER_URL_BACKUP;
        }
        return url;
    }
    
    public static void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }
    
    public static String getDeviceId(Context context) {
    String deviceId;
    try {
        deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (deviceId == null) {
            deviceId = "unknown_" + System.currentTimeMillis();
        }
    } catch (Exception e) {
        deviceId = "unknown_" + System.currentTimeMillis();
    }
    return deviceId;
}

    
    public static String syncConfigFromGithub() {
        new Thread(() -> {
            try {
                String configUrl = DEFAULT_GITHUB_CONFIG;
                String response = HttpClient.get(configUrl);
                
                if (response != null && !response.isEmpty()) {
                    org.json.JSONObject config = new org.json.JSONObject(response);
                    
                    if (config.has("server_url")) {
                        String newUrl = config.getString("server_url");
                        setServerUrl(newUrl);
                    }                    
                    
                    prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
    
    public static String getUsername(Context context) {
        try {
            InputStream is = context.getAssets().open("@kaell_Xz");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String username = reader.readLine();
            reader.close();
            is.close();
            return username != null ? username.trim() : "default";
        } catch (Exception e) {
            return "default";
        }
    }
    
    public static String encrypt(String text) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey);
            byte[] encryptedData = cipher.doFinal(text.getBytes());
            return Base64.getEncoder().encodeToString(encryptedData);
        } catch (Exception e) {
            return text;
        }
    }
    
    public static String decrypt(String encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey);
            byte[] decryptedData = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            return new String(decryptedData);
        } catch (Exception e) {
            return encrypted;
        }
    }
}
