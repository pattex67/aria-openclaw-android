package io.openclaw.aria

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ElevenLabsTTS(
    private val apiKey: String,
    private val voiceId: String,
    private val cacheDir: File
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null

    suspend fun speak(text: String) {
        val audioFile = withContext(Dispatchers.IO) { fetchAudio(text) }
        try {
            playAudio(audioFile)
        } catch (e: Exception) {
            audioFile.delete()
            throw e
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
            // MediaPlayer may throw IllegalStateException if in error state
        }
        mediaPlayer = null
    }

    private fun fetchAudio(text: String): File {
        val body = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_multilingual_v2")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
                put("style", 0.0)
                put("use_speaker_boost", true)
            })
        }

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "audio/mpeg")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw Exception("ElevenLabs error ${response.code}: $errorBody")
        }

        val audioFile = File(cacheDir, "aria_tts_${System.currentTimeMillis()}.mp3")
        response.body?.byteStream()?.use { input ->
            FileOutputStream(audioFile).use { output ->
                input.copyTo(output)
            }
        }
        return audioFile
    }

    private suspend fun playAudio(file: File) = suspendCancellableCoroutine { cont ->
        stop()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                mediaPlayer = null
                file.delete()
                if (cont.isActive) cont.resume(Unit)
            }
            setOnErrorListener { _, what, extra ->
                file.delete()
                if (cont.isActive) cont.resumeWithException(
                    Exception("MediaPlayer error: $what/$extra")
                )
                true
            }
            prepare()
            start()
        }
        cont.invokeOnCancellation { stop() }
    }
}
