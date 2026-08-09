package com.example.myapp;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public class LocationSyncWorker extends Worker {
    private static final String TAG = "LocationSyncWorker";
    // Ganti dengan IP VPS atau Domain API kamu
    private static final String API_URL = "https://api.domainkamu.com/v1/sync-location"; 

    public LocationSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "WorkManager berjalan: Memulai sinkronisasi ke server...");
        AppDatabase db = AppDatabase.getDatabase(getApplicationContext());
        
        // 1. Ambil semua data lokasi yang sempat tertimbun di Room DB saat offline
        List<LocationEntity> pendingLocations = db.locationDao().getAllPendingLocation();

        if (pendingLocations == null || pendingLocations.isEmpty()) {
            Log.d(TAG, "Tidak ada data offline yang perlu disinkronkan.");
            return Result.success();
        }

        try {
            // 2. Bungkus data koordinat ke dalam format JSON Array
            JSONArray jsonArray = new JSONArray();
            for (LocationEntity loc : pendingLocations) {
                JSONObject jsonLoc = new JSONObject();
                jsonLoc.put("latitude", loc.getLatitude());
                jsonLoc.put("longitude", loc.getLongitude());
                jsonLoc.put("timestamp", loc.getTimestamp());
                jsonArray.put(jsonLoc);
            }

            JSONObject mainPayload = new JSONObject();
            mainPayload.put("device_id", "HP_LOKASI_01"); // Identitas HP pelacak
            mainPayload.put("data", jsonArray);

            // 3. Kirim data ke Server VPS via HTTP POST menggunakan OkHttp
            OkHttpClient client = new OkHttpClient();
            RequestBody body = RequestBody.create(
                    mainPayload.toString(), 
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(API_URL)
                    .post(body)
                    .build();

            Response response = client.newCall(request).execute();

            if (response.isSuccessful()) {
                Log.d(TAG, "Sinkronisasi sukses! Server menerima data.");
                
                // 4. HAPUS data di Room DB karena sudah aman di server
                db.locationDao().deleteBatch(pendingLocations);
                
                return Result.success();
            } else {
                Log.e(TAG, "Server merespon dengan error: " + response.code());
                return Result.retry(); // Coba lagi nanti jika server down
            }

        } catch (Exception e) {
            Log.e(TAG, "Gagal mengirim data ke server: " + e.getMessage());
            return Result.retry(); // Coba lagi beberapa saat lagi
        }
    }
}
