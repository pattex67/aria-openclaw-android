package io.openclaw.aria

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.openclaw.aria.databinding.ActivitySettingsBinding
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AriaPrefs
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val backupExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportBackup(it) }
    }

    private val backupImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importBackup(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AriaPrefs(this)

        // Load values
        binding.editServerUrl.setText(prefs.serverUrl)
        binding.editApiToken.setText(prefs.apiToken)
        binding.editAssistantName.setText(prefs.assistantName)
        binding.editOpenaiKey.setText(prefs.openaiKey)
        binding.editElevenLabsKey.setText(prefs.elevenLabsKey)
        binding.editElevenLabsVoice.setText(prefs.elevenLabsVoiceId)
        binding.switchAutoTts.isChecked = prefs.autoTts

        // Session mode toggle
        binding.toggleSessionMode.check(
            if (prefs.sessionMode == "shared") R.id.btnSessionShared else R.id.btnSessionSeparate
        )
        binding.editCustomSessionKey.setText(prefs.customSessionKey)
        updateSessionFieldVisibility()
        binding.toggleSessionMode.addOnButtonCheckedListener { _, _, _ ->
            updateSessionFieldVisibility()
        }

        // STT provider toggle
        binding.toggleSttProvider.check(
            if (prefs.sttProvider == "android") R.id.btnSttAndroid else R.id.btnSttWhisper
        )
        updateSttFieldVisibility()
        binding.toggleSttProvider.addOnButtonCheckedListener { _, _, _ ->
            updateSttFieldVisibility()
        }

        // TTS provider toggle
        binding.toggleTtsProvider.check(
            if (prefs.ttsProvider == "android") R.id.btnTtsAndroid else R.id.btnTtsElevenLabs
        )
        updateTtsFieldVisibility()
        binding.toggleTtsProvider.addOnButtonCheckedListener { _, _, _ ->
            updateTtsFieldVisibility()
        }

        // Theme toggle
        binding.toggleTheme.check(
            when (prefs.themeMode) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.btnThemeLight
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> R.id.btnThemeSystem
                else -> R.id.btnThemeDark
            }
        )

        // Font size
        binding.seekFontSize.progress = prefs.fontSize
        binding.txtFontSizeValue.text = "${prefs.fontSize}sp"
        binding.txtFontPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSize.toFloat())
        binding.seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress.coerceIn(12, 24)
                binding.txtFontSizeValue.text = "${size}sp"
                binding.txtFontPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Language
        binding.toggleLanguage.check(
            when (prefs.language) {
                "en" -> R.id.btnLangEn
                "fr" -> R.id.btnLangFr
                else -> R.id.btnLangSystem
            }
        )

        // Features
        setupFeatures()

        // Save
        binding.btnSave.setOnClickListener {
            prefs.serverUrl = binding.editServerUrl.text.toString().trimEnd('/')
            prefs.apiToken = binding.editApiToken.text.toString()
            prefs.assistantName = binding.editAssistantName.text.toString().trim()
                .ifBlank { getString(R.string.default_assistant_name) }
            prefs.sessionMode = if (binding.toggleSessionMode.checkedButtonId == R.id.btnSessionShared) "shared" else "separate"
            prefs.customSessionKey = binding.editCustomSessionKey.text.toString().trim()
            prefs.openaiKey = binding.editOpenaiKey.text.toString().trim()
            prefs.elevenLabsKey = binding.editElevenLabsKey.text.toString().trim()
            prefs.elevenLabsVoiceId = binding.editElevenLabsVoice.text.toString().trim()
            prefs.autoTts = binding.switchAutoTts.isChecked
            prefs.sttProvider = if (binding.toggleSttProvider.checkedButtonId == R.id.btnSttAndroid) "android" else "whisper"
            prefs.ttsProvider = if (binding.toggleTtsProvider.checkedButtonId == R.id.btnTtsAndroid) "android" else "elevenlabs"
            prefs.fontSize = binding.seekFontSize.progress.coerceIn(12, 24)

            val newTheme = when (binding.toggleTheme.checkedButtonId) {
                R.id.btnThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.btnThemeSystem -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_YES
            }
            val themeChanged = prefs.themeMode != newTheme
            prefs.themeMode = newTheme

            val newLang = when (binding.toggleLanguage.checkedButtonId) {
                R.id.btnLangEn -> "en"
                R.id.btnLangFr -> "fr"
                else -> "system"
            }
            val langChanged = prefs.language != newLang
            prefs.language = newLang

            if (langChanged) {
                applyLanguage(newLang)
            }

            Toast.makeText(this, getString(R.string.save), Toast.LENGTH_SHORT).show()
            if (themeChanged) {
                AppCompatDelegate.setDefaultNightMode(newTheme)
            }
            finish()
        }
    }

    private fun setupFeatures() {
        // Biometric lock
        binding.switchBiometric.isChecked = prefs.biometricLock
        binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Check hardware availability first
                val biometricManager = BiometricManager.from(this)
                val canAuth = biometricManager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
                if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                    binding.switchBiometric.isChecked = false
                    Toast.makeText(this, getString(R.string.biometric_not_available), Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }

                // Require biometric verification before enabling
                binding.switchBiometric.isChecked = false // revert until verified
                verifyBiometricToEnable()
            } else {
                prefs.biometricLock = false
            }
        }

        // Backup
        binding.btnBackupExport.setOnClickListener {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            backupExportLauncher.launch("aria_backup_$date.json")
        }
        binding.btnBackupImport.setOnClickListener {
            backupImportLauncher.launch(arrayOf("application/json", "*/*"))
        }

        // Notifications
        binding.switchNotifications.isChecked = prefs.notificationsEnabled
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.notificationsEnabled = isChecked
            if (isChecked) {
                // Request notification permission if needed
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            this, android.Manifest.permission.POST_NOTIFICATIONS
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                            this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 201
                        )
                    }
                }
            }
        }

        // Accent color
        setupAccentColorPicker()
    }

    private fun setupAccentColorPicker() {
        val currentIndex = prefs.accentColorIndex
        highlightAccentColor(currentIndex)

        // Set click listeners on color circles
        val colorViews = arrayOf(
            binding.colorCircle0, binding.colorCircle1, binding.colorCircle2, binding.colorCircle3,
            binding.colorCircle4, binding.colorCircle5, binding.colorCircle6, binding.colorCircle7
        )
        colorViews.forEachIndexed { index, view ->
            val bg = view.background as? GradientDrawable
            bg?.setColor(AriaPrefs.ACCENT_COLORS[index])
            view.setOnClickListener {
                prefs.accentColorIndex = index
                highlightAccentColor(index)
            }
        }
    }

    private fun highlightAccentColor(selectedIndex: Int) {
        val colorViews = arrayOf(
            binding.colorCircle0, binding.colorCircle1, binding.colorCircle2, binding.colorCircle3,
            binding.colorCircle4, binding.colorCircle5, binding.colorCircle6, binding.colorCircle7
        )
        colorViews.forEachIndexed { index, view ->
            val bg = view.background as? GradientDrawable
            if (index == selectedIndex) {
                bg?.setStroke(4, getColor(R.color.text_primary))
            } else {
                bg?.setStroke(0, Color.TRANSPARENT)
            }
        }
    }

    private fun verifyBiometricToEnable() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prefs.biometricLock = true
                    binding.switchBiometric.setOnCheckedChangeListener(null)
                    binding.switchBiometric.isChecked = true
                    // Re-attach listener
                    setupBiometricListener()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancelled or error — leave switch off
                    Toast.makeText(this@SettingsActivity, errString, Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationFailed() {
                    // Keep waiting for retry
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_lock_desc))
            .setNegativeButtonText(getString(R.string.biometric_cancel))
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun setupBiometricListener() {
        binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val biometricManager = BiometricManager.from(this)
                val canAuth = biometricManager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
                if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                    binding.switchBiometric.isChecked = false
                    Toast.makeText(this, getString(R.string.biometric_not_available), Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                binding.switchBiometric.isChecked = false
                verifyBiometricToEnable()
            } else {
                prefs.biometricLock = false
            }
        }
    }

private fun exportBackup(uri: Uri) {
        val db = AppDatabase.getInstance(this)
        val dao = db.chatDao()
        val backup = BackupManager(this, dao)

        scope.launch(Dispatchers.IO) {
            try {
                val json = backup.exportToJson()
                contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray())
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.backup_export_success), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun importBackup(uri: Uri) {
        val db = AppDatabase.getInstance(this)
        val dao = db.chatDao()
        val backup = BackupManager(this, dao)

        scope.launch(Dispatchers.IO) {
            val result = backup.importFromJson(uri)
            withContext(Dispatchers.Main) {
                if (result.success) {
                    Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@SettingsActivity, getString(R.string.backup_import_error) + ": " + result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateSttFieldVisibility() {
        val isWhisper = binding.toggleSttProvider.checkedButtonId == R.id.btnSttWhisper
        binding.layoutOpenaiKey.visibility = if (isWhisper) View.VISIBLE else View.GONE
    }

    private fun updateTtsFieldVisibility() {
        val isElevenLabs = binding.toggleTtsProvider.checkedButtonId == R.id.btnTtsElevenLabs
        binding.layoutElevenLabsFields.visibility = if (isElevenLabs) View.VISIBLE else View.GONE
    }

    private fun updateSessionFieldVisibility() {
        val isShared = binding.toggleSessionMode.checkedButtonId == R.id.btnSessionShared
        binding.txtSessionDesc.visibility = if (isShared) View.VISIBLE else View.GONE
        binding.layoutCustomSessionKey.visibility = if (isShared) View.VISIBLE else View.GONE
        binding.txtSessionKeyInfo.visibility = if (isShared) View.VISIBLE else View.GONE

        // Pre-fill with default if empty
        if (isShared && binding.editCustomSessionKey.text.isNullOrBlank()) {
            binding.editCustomSessionKey.setText("agent:main")
        }
    }

    private fun applyLanguage(lang: String) {
        val locale = when (lang) {
            "en" -> java.util.Locale.ENGLISH
            "fr" -> java.util.Locale.FRENCH
            else -> java.util.Locale.getDefault()
        }
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
