package com.voyre.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ScreenControlService extends AccessibilityService {
    private static final String TAG = "ScreenControlService";
    private static ScreenControlService instance;
    private static final String PREFS_NAME = "screen_control";
    
    private WindowManager windowManager;
    private View overlayView;
    private GestureDetector gestureDetector;
    private BlockingQueue<JSONObject> gestureQueue = new LinkedBlockingQueue<>();
    private Handler handler = new Handler();
    private boolean isActive = false;
    private float screenWidth;
    private float screenHeight;
    
    public static ScreenControlService getInstance() {
        return instance;
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Passive service
    }
    
    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }
    
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                     AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE |
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenWidth = getResources().getDisplayMetrics().widthPixels;
        screenHeight = getResources().getDisplayMetrics().heightPixels;
        
        createOverlay();
        startGestureProcessor();
        
        isActive = true;
        Log.d(TAG, "Screen control service connected. Screen: " + screenWidth + "x" + screenHeight);
    }
    
    private void createOverlay() {
        try {
            int layoutFlag;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
            }
            
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            );
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            
            overlayView = new View(this);
            overlayView.setBackgroundColor(0x00000000);
            
            gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    handleTap(e.getX(), e.getY());
                    return true;
                }
            });
            
            overlayView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
            
            windowManager.addView(overlayView, params);
            Log.d(TAG, "Overlay created");
        } catch (Exception e) {
            Log.e(TAG, "Create overlay failed", e);
        }
    }
    
    private void handleTap(float x, float y) {
        Log.d(TAG, "Tap detected at: " + x + ", " + y);
        // Kirim ke WebSocket sebagai event
        try {
            JSONObject event = new JSONObject();
            event.put("type", "touch_event");
            event.put("action", "tap");
            event.put("x", x);
            event.put("y", y);
            // Kirim via BackgroundService
            // Ini akan di-handle oleh WebSocket
        } catch (Exception e) {}
    }
    
    private void startGestureProcessor() {
        new Thread(() -> {
            while (isActive) {
                try {
                    JSONObject gesture = gestureQueue.take();
                    executeGesture(gesture);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Gesture processor error", e);
                }
            }
        }).start();
    }
    
    private void executeGesture(JSONObject gesture) {
        try {
            String action = gesture.getString("action");
            boolean success = false;
            
            switch (action) {
                case "tap":
                    float x = (float) gesture.getDouble("x");
                    float y = (float) gesture.getDouble("y");
                    success = performTap(x, y);
                    break;
                case "swipe":
                    float sx = (float) gesture.getDouble("start_x");
                    float sy = (float) gesture.getDouble("start_y");
                    float ex = (float) gesture.getDouble("end_x");
                    float ey = (float) gesture.getDouble("end_y");
                    success = performSwipe(sx, sy, ex, ey);
                    break;
                case "input_text":
                    String text = gesture.getString("text");
                    success = performTextInput(text);
                    break;
                case "find_and_tap":
                    String search = gesture.getString("search");
                    success = findAndTap(search);
                    break;
                case "find_and_input":
                    String searchInput = gesture.getString("search");
                    String inputText = gesture.getString("input");
                    success = findAndInput(searchInput, inputText);
                    break;
                case "back":
                    success = performBack();
                    break;
                case "home":
                    success = performHome();
                    break;
                case "recent":
                    success = performRecentApps();
                    break;
                default:
                    Log.w(TAG, "Unknown gesture: " + action);
            }
            
            Log.d(TAG, "Gesture executed: " + action + " -> " + (success ? "success" : "failed"));
            
        } catch (Exception e) {
            Log.e(TAG, "Execute gesture failed", e);
        }
    }
    
    private boolean performTap(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
            return dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            Log.e(TAG, "Tap failed", e);
            return false;
        }
    }
    
    private boolean performSwipe(float startX, float startY, float endX, float endY) {
        try {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 300));
            return dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            Log.e(TAG, "Swipe failed", e);
            return false;
        }
    }
    
    private boolean performTextInput(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            
            AccessibilityNodeInfo target = findFocusableNode(root);
            if (target != null) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
                target.recycle();
                root.recycle();
                return result;
            }
            root.recycle();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Text input failed", e);
            return false;
        }
    }
    
    private AccessibilityNodeInfo findFocusableNode(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isFocusable() && root.isEditable()) return root;
        
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo result = findFocusableNode(child);
                if (result != null) {
                    child.recycle();
                    return result;
                }
                child.recycle();
            }
        }
        return null;
    }
    
    private boolean findAndTap(String searchText) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            
            List<AccessibilityNodeInfo> nodes = findNodesByText(root, searchText);
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    node.recycle();
                    root.recycle();
                    return true;
                }
                node.recycle();
            }
            root.recycle();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Find and tap failed", e);
            return false;
        }
    }
    
    private boolean findAndInput(String searchText, String inputText) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            
            List<AccessibilityNodeInfo> nodes = findNodesByText(root, searchText);
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isEditable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    
                    Bundle arguments = new Bundle();
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inputText);
                    boolean result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
                    node.recycle();
                    root.recycle();
                    return result;
                }
                node.recycle();
            }
            root.recycle();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Find and input failed", e);
            return false;
        }
    }
    
    private List<AccessibilityNodeInfo> findNodesByText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> results = new ArrayList<>();
        findNodesByTextRecursive(root, text.toLowerCase(), results);
        return results;
    }
    
    private void findNodesByTextRecursive(AccessibilityNodeInfo node, String searchText, List<AccessibilityNodeInfo> results) {
        if (node == null) return;
        
        CharSequence nodeText = node.getText();
        CharSequence nodeContentDesc = node.getContentDescription();
        
        if (nodeText != null && nodeText.toString().toLowerCase().contains(searchText)) {
            results.add(node);
        } else if (nodeContentDesc != null && nodeContentDesc.toString().toLowerCase().contains(searchText)) {
            results.add(node);
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findNodesByTextRecursive(child, searchText, results);
                child.recycle();
            }
        }
    }
    
    private boolean performBack() {
        try {
            return performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Exception e) { return false; }
    }
    
    private boolean performHome() {
        try {
            return performGlobalAction(GLOBAL_ACTION_HOME);
        } catch (Exception e) { return false; }
    }
    
    private boolean performRecentApps() {
        try {
            return performGlobalAction(GLOBAL_ACTION_RECENTS);
        } catch (Exception e) { return false; }
    }
    
    public void queueGesture(JSONObject gesture) {
        try {
            gestureQueue.offer(gesture);
        } catch (Exception e) {}
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
    
    public void performBackAction() {
        try {
            JSONObject gesture = new JSONObject();
            gesture.put("action", "back");
            queueGesture(gesture);
        } catch (Exception e) {}
    }
    
    public void performHomeAction() {
        try {
            JSONObject gesture = new JSONObject();
            gesture.put("action", "home");
            queueGesture(gesture);
        } catch (Exception e) {}
    }
    
    public void performRecentAction() {
        try {
            JSONObject gesture = new JSONObject();
            gesture.put("action", "recent");
            queueGesture(gesture);
        } catch (Exception e) {}
    }
    
    @Override
    public void onDestroy() {
        isActive = false;
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {}
        }
        instance = null;
        super.onDestroy();
    }
    
    public boolean isActive() {
        return isActive;
    }
}