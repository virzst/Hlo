package com.voyre.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

public class App extends Application {
    private static final String TAG = "App";
    private static Context context;
    
    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        
        Log.d(TAG, "Application created");
        
        // Init config dari GitHub
        Config.loadConfig(this);
        
        // Save device ID
        saveDeviceId();
    }
    
    private void saveDeviceId() {
        try {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = "unknown_" + System.currentTimeMillis();
            }
            
            SharedPreferences prefs = getSharedPreferences("app_config", MODE_PRIVATE);
            prefs.edit().putString("device_id", deviceId).apply();
            
            Log.d(TAG, "Device ID saved: " + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Save device ID failed", e);
        }
    }
    
    public static Context getContext() {
        return context;
    }
}