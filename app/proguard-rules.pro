# ===== OkHttp =====
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ===== Room =====
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ===== Google Play Billing =====
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.** { *; }

# ===== Coroutines =====
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ===== Markwon =====
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# ===== Coil =====
-keep class coil.** { *; }
-dontwarn coil.**

# ===== EdDSA (Ed25519) =====
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn sun.security.x509.X509Key

# ===== Biometric =====
-keep class androidx.biometric.** { *; }

# ===== App classes =====
-keep class io.openclaw.aria.ChatMessage { *; }
-keep class io.openclaw.aria.ChatMessage$* { *; }
-keep class io.openclaw.aria.ConversationEntity { *; }
-keep class io.openclaw.aria.FolderEntity { *; }
-keep class io.openclaw.aria.BackupManager$* { *; }

# ===== Prevent R8 from stripping interfaces =====
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ===== Keep JSON serialization =====
-keepclassmembers class * {
    @org.json.* <fields>;
}
