package com.ffbooster.panel;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class DeviceAdmin extends DeviceAdminReceiver {
    
    public void lockDevice(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isAdminActive(new android.content.ComponentName(context, DeviceAdmin.class))) {
                dpm.lockNow();
            }
        } catch (Exception e) {
            Log.e("FFBooster_Admin", "Lock error", e);
        }
    }
    
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.d("FFBooster_Admin", "Device admin enabled");
    }
    
    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.d("FFBooster_Admin", "Device admin disabled");
    }
}
