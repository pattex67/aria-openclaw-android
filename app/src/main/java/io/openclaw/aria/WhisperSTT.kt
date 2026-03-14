package io.openclaw.aria

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import io.openclaw.aria.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class WhisperSTT(
    private val apiKey: String,
    private val cacheDir: File
) {
    interface Callback {
        fun onSilenceDetected()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var audioRecord: AudioRecord? = null
    private val recording = AtomicBoolean(false)
    private var rawPcmFile: File? = null
    private var recordThread: Thread? = null
    private var callback: Callback? = null

    val isRecording: Boolean get() = recording.get()

    companion object {
        private const val TAG = "WhisperSTT"
        private const val SAMPLE_RATE = 16000
        private const val SILENCE_THRESHOLD_RMS = 800.0
        private const val SILENCE_DURATION_MS = 1500L
        private const val MIN_RECORDING_MS = 500L
    }

    fun startRecording(cb: Callback? = null) {
        if (recording.get()) return
        callback = cb

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat) * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            if (BuildConfig.DEBUG) Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return
        }

        rawPcmFile = File(cacheDir, "whisper_recording.pcm")

        recording.set(true)
        audioRecord?.startRecording()

        recordThread = Thread {
            try {
                val buffer = ByteArray(bufferSize)
                val startTime = System.currentTimeMillis()
                var silenceStart = 0L
                var hasSpeech = false

                FileOutputStream(rawPcmFile!!).use { fos ->
                    while (recording.get()) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (read > 0) {
                            fos.write(buffer, 0, read)

                            val rms = computeRms(buffer, read)
                            val elapsed = System.currentTimeMillis() - startTime

                            if (rms > SILENCE_THRESHOLD_RMS) {
                                hasSpeech = true
                                silenceStart = 0L
                            } else if (hasSpeech && elapsed > MIN_RECORDING_MS) {
                                if (silenceStart == 0L) {
                                    silenceStart = System.currentTimeMillis()
                                } else if (System.currentTimeMillis() - silenceStart >= SILENCE_DURATION_MS) {
                                    if (BuildConfig.DEBUG) Log.d(TAG, "Silence detected after ${elapsed}ms, stopping")
                                    recording.set(false)
                                    callback?.onSilenceDetected()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Recording thread error", e)
                recording.set(false)
            }
        }.apply { start() }

        if (BuildConfig.DEBUG) Log.d(TAG, "Recording started")
    }

    private fun computeRms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        val samples = length / 2
        for (i in 0 until samples) {
            val sample = (buffer[i * 2 + 1].toInt() shl 8) or (buffer[i * 2].toInt() and 0xFF)
            sum += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sum / samples)
    }

    fun stopRecording() {
        recording.set(false)
        recordThread?.join(2000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        callback = null
        if (BuildConfig.DEBUG) Log.d(TAG, "Recording stopped")
    }

    suspend fun transcribe(): String = withContext(Dispatchers.IO) {
        val pcmFile = rawPcmFile ?: throw Exception("No recording found")
        if (!pcmFile.exists() || pcmFile.length() == 0L) {
            throw Exception("Empty recording")
        }

        val wavFile = File(cacheDir, "whisper_recording.wav")
        pcmToWav(pcmFile, wavFile, SAMPLE_RATE, 1, 16)
        pcmFile.delete()

        if (BuildConfig.DEBUG) Log.d(TAG, "WAV file size: ${wavFile.length()} bytes")

        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    wavFile.asRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", "fr")
                .build()

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Whisper API error ${response.code}: $body")
                throw Exception("Whisper error ${response.code}")
            }

            val json = JSONObject(body)
            val text = json.optString("text", "").trim()
            if (BuildConfig.DEBUG) Log.d(TAG, "Transcription: $text")
            text
        } finally {
            wavFile.delete()
        }
    }

    private fun pcmToWav(pcmFile: File, wavFile: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val pcmData = pcmFile.readBytes()
        val dataSize = pcmData.size
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        FileOutputStream(wavFile).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)

            fos.write(header.array())
            fos.write(pcmData)
        }
    }
}
