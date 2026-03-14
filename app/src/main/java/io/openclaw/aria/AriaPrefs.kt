package io.openclaw.aria

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

class AriaPrefs(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("aria_prefs", Context.MODE_PRIVATE)

    // Encrypted storage for sensitive keys (API tokens, passwords)
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "aria_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to regular prefs if encryption fails (e.g. rooted device issues)
            Log.w("AriaPrefs", "EncryptedSharedPreferences failed, using fallback", e)
            prefs
        }
    }

    // ---- Connection ----

    var serverUrl: String
        get() = prefs.getString("server_url", null)
            ?: context.getString(R.string.default_server_url)
        set(value) = prefs.edit().putString("server_url", value).apply()

    var apiToken: String
        get() {
            // Migrate from unencrypted if present
            val old = prefs.getString("api_token", null)
            if (old != null && old.isNotBlank()) {
                securePrefs.edit().putString("api_token", old).apply()
                prefs.edit().remove("api_token").apply()
                return old
            }
            return securePrefs.getString("api_token", "") ?: ""
        }
        set(value) = securePrefs.edit().putString("api_token", value).apply()

    var assistantName: String
        get() = prefs.getString("assistant_name", null)
            ?: context.getString(R.string.default_assistant_name)
        set(value) = prefs.edit().putString("assistant_name", value).apply()

    // ---- Session ----

    var sessionMode: String
        get() = prefs.getString("session_mode", null)
            ?: context.getString(R.string.default_session_mode)
        set(value) = prefs.edit().putString("session_mode", value).apply()

    var customSessionKey: String
        get() = prefs.getString("custom_session_key", null)
            ?: context.getString(R.string.default_session_key)
        set(value) = prefs.edit().putString("custom_session_key", value).apply()

    var sessionKey: String
        get() = prefs.getString("session_key", null) ?: newSessionKey()
        set(value) = prefs.edit().putString("session_key", value).apply()

    val effectiveSessionKey: String
        get() = if (sessionMode == "shared" && customSessionKey.isNotBlank())
            customSessionKey else sessionKey

    fun newSessionKey(): String {
        val key = "app:aria:${UUID.randomUUID()}"
        sessionKey = key
        return key
    }

    // ---- TTS / STT ----

    var autoTts: Boolean
        get() = prefs.getBoolean("auto_tts", true)
        set(value) = prefs.edit().putBoolean("auto_tts", value).apply()

    var elevenLabsKey: String
        get() {
            val old = prefs.getString("elevenlabs_key", null)
            if (old != null && old.isNotBlank()) {
                securePrefs.edit().putString("elevenlabs_key", old).apply()
                prefs.edit().remove("elevenlabs_key").apply()
                return old
            }
            return securePrefs.getString("elevenlabs_key", "") ?: ""
        }
        set(value) = securePrefs.edit().putString("elevenlabs_key", value).apply()

    var elevenLabsVoiceId: String
        get() = prefs.getString("elevenlabs_voice_id", null)
            ?: context.getString(R.string.default_voice_id)
        set(value) = prefs.edit().putString("elevenlabs_voice_id", value).apply()

    var openaiKey: String
        get() {
            val old = prefs.getString("openai_key", null)
            if (old != null && old.isNotBlank()) {
                securePrefs.edit().putString("openai_key", old).apply()
                prefs.edit().remove("openai_key").apply()
                return old
            }
            return securePrefs.getString("openai_key", "") ?: ""
        }
        set(value) = securePrefs.edit().putString("openai_key", value).apply()

    var sttProvider: String
        get() = prefs.getString("stt_provider", null)
            ?: context.getString(R.string.default_stt_provider)
        set(value) = prefs.edit().putString("stt_provider", value).apply()

    var ttsProvider: String
        get() = prefs.getString("tts_provider", null)
            ?: context.getString(R.string.default_tts_provider)
        set(value) = prefs.edit().putString("tts_provider", value).apply()

    // ---- Display ----

    var fontSize: Int
        get() = prefs.getInt("font_size", 15)
        set(value) = prefs.edit().putInt("font_size", value).apply()

    var themeMode: Int
        get() = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_YES)
        set(value) = prefs.edit().putInt("theme_mode", value).apply()

    // "system", "en", "fr"
    var language: String
        get() = prefs.getString("language", context.getString(R.string.default_language)) ?: "en"
        set(value) = prefs.edit().putString("language", value).apply()

    // ---- Conversations ----

    var currentConversationId: String
        get() = prefs.getString("current_conversation_id", null) ?: newConversationId()
        set(value) = prefs.edit().putString("current_conversation_id", value).apply()

    fun newConversationId(): String {
        val id = "conv-${UUID.randomUUID()}"
        currentConversationId = id
        return id
    }

    // ---- State ----

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank()

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean("notif_permission_asked", false)
        set(value) = prefs.edit().putBoolean("notif_permission_asked", value).apply()

    var batteryOptimizationAsked: Boolean
        get() = prefs.getBoolean("battery_optim_asked", false)
        set(value) = prefs.edit().putBoolean("battery_optim_asked", value).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) = prefs.edit().putBoolean("notifications_enabled", value).apply()

    // All features are unlocked (open source)
    val isPro: Boolean get() = true

    var biometricLock: Boolean
        get() = prefs.getBoolean("biometric_lock", false)
        set(value) = prefs.edit().putBoolean("biometric_lock", value).apply()

    var accentColorIndex: Int
        get() = prefs.getInt("accent_color_index", 0)
        set(value) = prefs.edit().putInt("accent_color_index", value).apply()

    val accentColor: Int
        get() = ACCENT_COLORS.getOrElse(accentColorIndex) { ACCENT_COLORS[0] }

    companion object {
        val ACCENT_COLORS = intArrayOf(
            0xFF7C3AED.toInt(), // Violet
            0xFF3B82F6.toInt(), // Bleu
            0xFF10B981.toInt(), // Vert
            0xFFEF4444.toInt(), // Rouge
            0xFFF97316.toInt(), // Orange
            0xFFEC4899.toInt(), // Rose
            0xFF14B8A6.toInt(), // Sarcelle
            0xFFEAB308.toInt()  // Jaune
        )

        val ACCENT_NAMES = arrayOf(
            "Violet", "Bleu", "Vert", "Rouge", "Orange", "Rose", "Sarcelle", "Jaune"
        )
    }
}
