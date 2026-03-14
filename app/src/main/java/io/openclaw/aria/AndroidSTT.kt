package io.openclaw.aria

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import io.openclaw.aria.BuildConfig

class AndroidSTT(context: Context) {

    interface Callback {
        fun onResult(text: String)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "AndroidSTT"
    }

    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null

    var isListening: Boolean = false
        private set

    val isAvailable: Boolean get() = recognizer != null

    fun startListening(cb: Callback) {
        val rec = recognizer
        if (rec == null) {
            cb.onError("Speech recognition not available on this device")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                if (BuildConfig.DEBUG) Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (BuildConfig.DEBUG) Log.d(TAG, "Speech ended")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No text detected"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No voice detected"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
                    else -> "Recognition error ($error)"
                }
                if (BuildConfig.DEBUG) Log.e(TAG, "Error: $msg")
                cb.onError(msg)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                if (BuildConfig.DEBUG) Log.d(TAG, "Result: $text")
                if (text.isNotBlank()) {
                    cb.onResult(text)
                } else {
                    cb.onError("No text detected")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        rec.startListening(intent)
        isListening = true
        if (BuildConfig.DEBUG) Log.d(TAG, "Listening started")
    }

    fun stopListening() {
        recognizer?.stopListening()
        isListening = false
    }

    fun destroy() {
        recognizer?.destroy()
    }
}
