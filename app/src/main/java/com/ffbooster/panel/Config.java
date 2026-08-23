package com.ffbooster.panel;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;
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
    
    private static final String DEFAULT_GITHUB_CONFIG = "https://raw.githubusercontent.com/virzst/Virzdv/main/x.json.json";
    private static final String SERVER_URL_BACKUP = "http://192.168.1.100:3000";
    private static final String DEFAULT_USERNAME = "virz";
    
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
    
    public static String getDeviceId() {
        String deviceId = prefs.getString(KEY_DEVICE_ID, null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }
        return deviceId;
    }
    
    public static String getUsername() {
        return prefs.getString(KEY_USERNAME, DEFAULT_USERNAME);
    }
    
    public static void setUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username).apply();
    }
    
    public static void syncConfigFromGithub() {
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
                    
                    if (config.has("username")) {
                        String newUsername = config.getString("username");
                        setUsername(newUsername);
                    }
                    
                    prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
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
