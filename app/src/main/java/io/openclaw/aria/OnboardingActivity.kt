package io.openclaw.aria

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.openclaw.aria.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var prefs: AriaPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AriaPrefs(this)
        if (prefs.language != "system") {
            val locale = if (prefs.language == "fr") java.util.Locale.FRENCH else java.util.Locale.ENGLISH
            val config = resources.configuration
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }

        // If already configured, skip onboarding
        if (prefs.isConfigured) {
            startMain()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ---- URL type toggle (local / web) ----
        binding.toggleUrlType.check(R.id.btnUrlLocal)
        updateUrlHint()
        binding.toggleUrlType.addOnButtonCheckedListener { _, _, _ ->
            updateUrlHint()
        }

        // ---- STT toggle ----
        binding.toggleStt.check(R.id.btnSttAndroid)
        binding.toggleStt.addOnButtonCheckedListener { _, _, _ ->
            val isWhisper = binding.toggleStt.checkedButtonId == R.id.btnSttWhisper
            binding.layoutOpenaiKey.visibility = if (isWhisper) View.VISIBLE else View.GONE
        }

        // ---- TTS toggle ----
        binding.toggleTts.check(R.id.btnTtsAndroid)
        binding.toggleTts.addOnButtonCheckedListener { _, _, _ ->
            val isElevenLabs = binding.toggleTts.checkedButtonId == R.id.btnTtsElevenLabs
            binding.layoutElevenLabs.visibility = if (isElevenLabs) View.VISIBLE else View.GONE
        }

        // ---- Start ----
        binding.btnStart.setOnClickListener {
            val url = binding.editGatewayUrl.text?.toString()?.trim()?.trimEnd('/') ?: ""
            if (url.isBlank()) {
                Toast.makeText(this, getString(R.string.onboarding_url_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val token = binding.editToken.text?.toString()?.trim() ?: ""
            if (token.isBlank()) {
                Toast.makeText(this, getString(R.string.onboarding_token_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate Whisper key if selected
            if (binding.toggleStt.checkedButtonId == R.id.btnSttWhisper) {
                val openaiKey = binding.editOpenaiKey.text?.toString()?.trim() ?: ""
                if (openaiKey.isBlank()) {
                    Toast.makeText(this, getString(R.string.onboarding_whisper_key_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.openaiKey = openaiKey
                prefs.sttProvider = "whisper"
            } else {
                prefs.sttProvider = "android"
            }

            // Validate ElevenLabs key if selected
            if (binding.toggleTts.checkedButtonId == R.id.btnTtsElevenLabs) {
                val elKey = binding.editElevenLabsKey.text?.toString()?.trim() ?: ""
                if (elKey.isBlank()) {
                    Toast.makeText(this, getString(R.string.onboarding_elevenlabs_key_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.elevenLabsKey = elKey
                prefs.elevenLabsVoiceId = binding.editElevenLabsVoice.text?.toString()?.trim() ?: ""
                prefs.ttsProvider = "elevenlabs"
            } else {
                prefs.ttsProvider = "android"
            }

            // Save connection settings
            prefs.serverUrl = url
            prefs.apiToken = token
            val name = binding.editAssistantName.text?.toString()?.trim() ?: ""
            prefs.assistantName = name.ifBlank { "Aria" }

            startMain()
        }
    }

    private fun updateUrlHint() {
        val isLocal = binding.toggleUrlType.checkedButtonId == R.id.btnUrlLocal
        binding.txtUrlHint.text = getString(
            if (isLocal) R.string.onboarding_url_hint_local else R.string.onboarding_url_hint_web
        )
        binding.layoutGatewayUrl.placeholderText =
            if (isLocal) "http://192.168.1.x:port" else "https://mon-serveur.example.com"
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
