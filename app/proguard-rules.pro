# =================================================================
# ATURAN BAWAAN & PROJECT KAMU (VOYRE APP)
# =================================================================
# Menjaga semua class di package kamu agar tidak diacak/dihapus
-keep class com.voyre.app.** { *; }

# Menjaga atribut penting untuk reflection, tipe data generik, dan line number saat crash
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

# =================================================================
# OKHTTP, OKIO, & JSON CONFIGURATION
# =================================================================
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.json.** { *; }

# Meredam warning internal OkHttp akibat dependensi platform alternatif
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# =================================================================
# GOOGLE GSON CONFIGURATION
# =================================================================
# Wajib ditambahkan karena Gson berada di luar package com.voyre.app
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# =================================================================
# ROOM DATABASE CONFIGURATION
# =================================================================
# Wajib ditambahkan agar struktur SQLite bentukan Room tidak dirusak ProGuard
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**
