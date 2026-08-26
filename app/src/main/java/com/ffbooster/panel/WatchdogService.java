// New file: WatchdogService.java
package com.ffbooster.panel;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Build;
import android.util.Log;

public class WatchdogService extends Service {
    private Handler handler;
    private static final long WATCHDOG_INTERVAL = 30 * 1000; // 30 seconds
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler = new Handler();
        startWatchdog();
        return START_STICKY;
    }
    
    private void startWatchdog() {
        handler.postDelayed(() -> {
            // Check if BackgroundService is running
            if (!isServiceRunning(BackgroundService.class)) {
                Log.d("FFBooster_Watchdog", "BackgroundService not running, restarting...");
                
                Intent serviceIntent = new Intent(this, BackgroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            }
            
            // Continue watching
            startWatchdog();
        }, WATCHDOG_INTERVAL);
    }
    
    private boolean isServiceRunning(Class<?> serviceClass) {
        android.app.ActivityManager manager = 
            (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        
        if (manager != null) {
            for (android.app.ActivityManager.RunningServiceInfo service : 
                manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}