package com.ffbooster.panel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() != null &&
                (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED) ||
                 intent.getAction().equals("android.intent.action.QUICKBOOT_POWERON") ||
                 intent.getAction().equals("com.htc.intent.action.QUICKBOOT_POWERON"))) {
            
            Log.d("FFBooster_Boot", "Device boot detected, starting service");
            
            Intent serviceIntent = new Intent(context, BackgroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
