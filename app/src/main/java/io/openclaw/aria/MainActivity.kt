package io.openclaw.aria

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.HapticFeedbackConstants
import android.view.animation.AccelerateDecelerateInterpolator
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.decode.VideoFrameDecoder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.openclaw.aria.databinding.ActivityMainBinding
import android.os.Build
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AriaPrefs
    private lateinit var adapter: ChatAdapter
    private lateinit var tts: TextToSpeech
    private lateinit var db: AppDatabase
    private lateinit var dao: ChatDao

    private val messages = mutableListOf<ChatMessage>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var openClawClient: OpenClawClient? = null
    private var elevenTts: ElevenLabsTTS? = null
    private var whisperStt: WhisperSTT? = null
    private var androidStt: AndroidSTT? = null

    private var isListening = false
    private var isSending = false
    private var isStreaming = false
    private var currentLanguage: String = "system"
    private var micPulseAnimator: AnimatorSet? = null
// Retry on background disconnect
    private var pendingRetryMessage: String? = null
    private var pendingRetryMedia: MediaAttachment? = null

    // Reply
    private var replyToMessage: ChatMessage? = null
    private val replySwipeIcon by lazy {
        ContextCompat.getDrawable(this, R.drawable.ic_reply)?.apply {
            setTint(getColor(R.color.purple_light))
        }
    }

    // Search
    private var isSearchActive = false
    private var allMessages = mutableListOf<ChatMessage>()
    private var isAuthenticated = false

    // Conversations
    private var conversationAdapter: ConversationAdapter? = null
    private val conversations = mutableListOf<ConversationEntity>()

    // Media
    private var pendingMediaUri: Uri? = null
    private var pendingMediaType: String? = null
    private var pendingMediaMime: String? = null
    private var cameraImageUri: Uri? = null

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { handlePickedMedia(it) } }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) cameraImageUri?.let { handlePickedMedia(it) } }

    private val takeVideoLauncher = registerForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { success -> if (success) cameraImageUri?.let { handlePickedMedia(it, isVideo = true) } }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) showAttachmentOptions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        prefs = AriaPrefs(this)
        currentLanguage = prefs.language
        applyLanguage(currentLanguage)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(prefs.themeMode)

        // Redirect to onboarding if not configured
        if (!prefs.isConfigured) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tts = TextToSpeech(this, this)
        db = AppDatabase.getInstance(this)
        dao = db.chatDao()

        setupChat()
        setupDrawer()
        setupButtons()
        setupSearch()
        setupMessageSwipe()
        requestMicPermission()
        initElevenLabs()
        initWhisper()
        initAndroidStt()
        connectGateway()
        loadMessages()
        adapter.fontSize = prefs.fontSize

        // Notifications
        AriaNotifications.createChannel(this)
        requestNotificationPermission()
        checkBatteryOptimization()

        // Apply accent color to key UI elements
        applyAccentColor()

        // Handle voice intent from widget
        if (intent?.getBooleanExtra("start_voice", false) == true) {
            binding.root.post { toggleListening() }
        }

        // Biometric lock
        if (prefs.biometricLock && !isAuthenticated) {
            showBiometricPrompt()
        } else {
            isAuthenticated = true
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // Always request if not granted (Android handles "don't ask again" automatically)
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)
                    && prefs.notificationPermissionAsked) {
                    // User selected "Don't ask again" — can't request anymore
                    android.util.Log.w("AriaNotif", "Notification permission permanently denied")
                } else {
                    prefs.notificationPermissionAsked = true
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200
                    )
                }
            }
        }
    }

    private fun isAppInBackground(): Boolean {
        return !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    private fun checkBatteryOptimization() {
        if (prefs.batteryOptimizationAsked) return
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.batteryOptimizationAsked = true
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.battery_optim_title))
                .setMessage(getString(R.string.battery_optim_message))
                .setPositiveButton(getString(R.string.battery_optim_settings)) { _, _ ->
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        // Fallback to general battery settings
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {}
                    }
                }
                .setNegativeButton(getString(R.string.battery_optim_later), null)
                .show()
        }
    }

    private fun connectGateway() {
        openClawClient?.disconnect()
        openClawClient = OpenClawClient(prefs.serverUrl, prefs.apiToken, applicationContext)
        scope.launch {
            try {
                openClawClient?.ensureConnected()
                setStatus(null)
            } catch (e: Exception) {
                setStatus("Offline")
                Toast.makeText(
                    this@MainActivity,
                    "Gateway connection: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupChat() {
        adapter = ChatAdapter(this, messages,
            onLongClick = { msg, _ ->
                binding.recyclerChat.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showMessageOptions(msg)
            },
            onRegenerateClick = { regenerateLastResponse() },
            onRetryClick = { retryLastError() }
        )
        binding.recyclerChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerChat.adapter = adapter

        // Suggestions
        binding.btnSuggestion1.setOnClickListener { sendSuggestion(getString(R.string.suggestion_1)) }
        binding.btnSuggestion2.setOnClickListener { sendSuggestion(getString(R.string.suggestion_2)) }
        binding.btnSuggestion3.setOnClickListener { sendSuggestion(getString(R.string.suggestion_3)) }

        // Scroll-to-bottom FAB
        binding.fabScrollBottom.setOnClickListener {
            if (messages.isNotEmpty()) {
                binding.recyclerChat.smoothScrollToPosition(messages.size - 1)
            }
        }
        binding.recyclerChat.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastCompletelyVisibleItemPosition()
                binding.fabScrollBottom.isVisible = messages.size > 0 && lastVisible < messages.size - 2
            }
        })
    }

    private fun sendSuggestion(text: String) {
        binding.editMessage.setText(text)
        sendMessage()
    }

    private fun updateEmptyState() {
        val empty = messages.isEmpty()
        binding.emptyState.isVisible = empty
        binding.recyclerChat.isVisible = !empty
    }

    // ---- Drawer / Conversations ----

    private fun setupDrawer() {
        binding.btnMenu.setOnClickListener {
            loadConversations()
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.btnDrawerNewChat.setOnClickListener {
            clearChat()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.btnDrawerSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        conversationAdapter = ConversationAdapter(conversations,
            onClick = { conv ->
                switchToConversation(conv.id)
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            },
            onLongClick = { conv -> showConversationOptions(conv) }
        )
        binding.recyclerConversations.layoutManager = LinearLayoutManager(this)
        binding.recyclerConversations.adapter = conversationAdapter

        // Swipe to delete conversation
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos < 0 || pos >= conversations.size) return
                val conv = conversations[pos]
                if (conv.id == prefs.currentConversationId) {
                    conversationAdapter?.notifyItemChanged(pos)
                    Toast.makeText(this@MainActivity, "Cannot delete active conversation", Toast.LENGTH_SHORT).show()
                    return
                }
                conversations.removeAt(pos)
                conversationAdapter?.notifyItemRemoved(pos)
                scope.launch(Dispatchers.IO) {
                    dao.deleteMessagesForConversation(conv.id)
                    dao.deleteConversation(conv)
                }
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.recyclerConversations)
    }

    private fun loadConversations() {
        scope.launch(Dispatchers.IO) {
            val convs = dao.getAllConversations()
            withContext(Dispatchers.Main) {
                conversations.clear()
                conversations.addAll(convs)
                conversationAdapter?.activeId = prefs.currentConversationId
                conversationAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun showConversationOptions(conv: ConversationEntity) {
        val pinText = if (conv.isPinned) getString(R.string.unpin_conversation) else getString(R.string.pin_conversation)
        val options = mutableListOf(
            getString(R.string.rename_conversation),
            getString(R.string.export),
            pinText
        )
        options.add(getString(R.string.move_to_folder))
        MaterialAlertDialogBuilder(this)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> renameConversation(conv)
                    1 -> exportConversation(conv.id)
                    2 -> togglePin(conv)
                    3 -> showFolderPicker(conv)
                }
            }
            .show()
    }

    private fun togglePin(conv: ConversationEntity) {
        val newPinned = !conv.isPinned
        scope.launch(Dispatchers.IO) {
            dao.setPinned(conv.id, newPinned)
            withContext(Dispatchers.Main) { loadConversations() }
        }
    }

    private fun showFolderPicker(conv: ConversationEntity) {
        scope.launch(Dispatchers.IO) {
            val folders = dao.getAllFolders()
            withContext(Dispatchers.Main) {
                val names = mutableListOf(getString(R.string.no_folder))
                names.addAll(folders.map { it.name })
                names.add(getString(R.string.new_folder))

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.move_to_folder))
                    .setItems(names.toTypedArray()) { _, which ->
                        when (which) {
                            0 -> {
                                // Remove from folder
                                scope.launch(Dispatchers.IO) {
                                    dao.setFolder(conv.id, null)
                                    withContext(Dispatchers.Main) { loadConversations() }
                                }
                            }
                            names.size - 1 -> {
                                // New folder
                                showNewFolderDialog(conv)
                            }
                            else -> {
                                val folderName = folders[which - 1].name
                                scope.launch(Dispatchers.IO) {
                                    dao.setFolder(conv.id, folderName)
                                    withContext(Dispatchers.Main) { loadConversations() }
                                }
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun showNewFolderDialog(conv: ConversationEntity? = null) {
        val editText = android.widget.EditText(this).apply {
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            hint = getString(R.string.folder_name)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.new_folder))
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        dao.insertFolder(FolderEntity(id = UUID.randomUUID().toString(), name = name))
                        if (conv != null) {
                            dao.setFolder(conv.id, name)
                        }
                        withContext(Dispatchers.Main) { loadConversations() }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameConversation(conv: ConversationEntity) {
        val editText = android.widget.EditText(this).apply {
            setText(conv.title)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            hint = getString(R.string.new_title)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rename_conversation))
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val newTitle = editText.text.toString().trim()
                if (newTitle.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        dao.updateConversation(conv.id, newTitle, conv.updatedAt)
                        withContext(Dispatchers.Main) { loadConversations() }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var exportConvId: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri ?: return@registerForActivityResult
        val convId = exportConvId ?: return@registerForActivityResult
        scope.launch(Dispatchers.IO) {
            val msgs = dao.getMessagesForConversation(convId)
            val text = msgs.joinToString("\n\n") { msg ->
                val time = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(msg.timestamp))
                val role = if (msg.role == "user") "Me" else prefs.assistantName
                "[$time] $role:\n${msg.content}"
            }
            val written = contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray())
                true
            } ?: false
            withContext(Dispatchers.Main) {
                if (written) {
                    Toast.makeText(this@MainActivity, getString(R.string.conversation_exported), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Error: unable to write file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportConversation(convId: String) {
        exportConvId = convId
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        exportLauncher.launch("aria_export_$date.txt")
    }

    private fun switchToConversation(convId: String) {
        prefs.currentConversationId = convId
        prefs.newSessionKey()
        scope.launch(Dispatchers.IO) {
            val saved = dao.getMessagesForConversation(convId)
            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(saved)
                adapter.refreshLastAssistantPosition()
                adapter.notifyDataSetChanged()
                updateEmptyState()
                if (messages.isNotEmpty()) {
                    binding.recyclerChat.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private fun loadMessages() {
        scope.launch(Dispatchers.IO) {
            val convId = prefs.currentConversationId
            if (dao.getConversation(convId) == null) {
                dao.insertConversation(
                    ConversationEntity(id = convId, title = "New conversation")
                )
            }
            val saved = dao.getMessagesForConversation(convId)
            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(saved)
                adapter.refreshLastAssistantPosition()
                adapter.notifyDataSetChanged()
                updateEmptyState()
                if (messages.isNotEmpty()) {
                    binding.recyclerChat.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    // ---- Buttons ----

    private fun setupButtons() {
        binding.btnSend.setOnClickListener { sendMessage() }

        binding.editMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        binding.btnMic.setOnClickListener { toggleListening() }
        binding.btnNewChat.setOnClickListener { clearChat() }
        binding.btnAttach.setOnClickListener { onAttachClicked() }
        binding.btnRemoveMedia.setOnClickListener { clearPendingMedia() }
        binding.btnCloseReply.setOnClickListener { clearReply() }
        binding.btnSearch.setOnClickListener { toggleSearch() }
    }

    // ---- Search ----

    private fun setupSearch() {
        binding.btnCloseSearch.setOnClickListener { closeSearch() }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterMessages(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun toggleSearch() {
        if (isSearchActive) closeSearch() else openSearch()
    }

    private fun openSearch() {
        isSearchActive = true
        allMessages = messages.toMutableList()
        binding.topBar.isVisible = false
        binding.searchBar.isVisible = true
        binding.editSearch.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.editSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        isSearchActive = false
        binding.searchBar.isVisible = false
        binding.topBar.isVisible = true
        binding.editSearch.text?.clear()
        messages.clear()
        messages.addAll(allMessages)
        adapter.refreshLastAssistantPosition()
        adapter.notifyDataSetChanged()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editSearch.windowToken, 0)
    }

    private fun filterMessages(query: String) {
        if (query.isBlank()) {
            messages.clear()
            messages.addAll(allMessages)
        } else {
            messages.clear()
            messages.addAll(allMessages.filter {
                it.content.contains(query, ignoreCase = true)
            })
        }
        adapter.refreshLastAssistantPosition()
        adapter.notifyDataSetChanged()
    }

    // ---- Message interactions ----

    private fun showMessageOptions(msg: ChatMessage) {
        val codeBlocks = extractCodeBlocks(msg.content)
        val optionsList = mutableListOf(getString(R.string.copy), getString(R.string.share))
        if (codeBlocks.isNotEmpty()) {
            optionsList.add(getString(R.string.copy_code))
        }
        optionsList.add(getString(R.string.react))
        MaterialAlertDialogBuilder(this)
            .setItems(optionsList.toTypedArray()) { _, which ->
                when (which) {
                    0 -> copyMessage(msg)
                    1 -> shareMessage(msg)
                    optionsList.size - 1 -> showReactionPicker(msg)
                    2 -> copyCodeBlock(codeBlocks)
                }
            }
            .show()
    }

    private fun showReactionPicker(msg: ChatMessage) {
        val emojis = arrayOf("\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDD25")
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.react))
            .setItems(emojis) { _, which ->
                val selected = emojis[which]
                // Toggle: if same reaction → remove, else set new
                val newReaction = if (msg.reaction == selected) null else selected
                val idx = messages.indexOf(msg)
                if (idx >= 0 && msg.id > 0) {
                    messages[idx] = msg.copy(reaction = newReaction)
                    adapter.notifyItemChanged(idx)
                    scope.launch(Dispatchers.IO) {
                        dao.setReaction(msg.id, newReaction)
                    }
                }
            }
            .show()
    }

    private fun extractCodeBlocks(content: String): List<String> {
        val regex = Regex("```[\\w]*\\n([\\s\\S]*?)```")
        return regex.findAll(content).map { it.groupValues[1].trim() }.toList()
    }

    private fun copyCodeBlock(blocks: List<String>) {
        if (blocks.size == 1) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Code", blocks[0]))
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        } else {
            // Multiple code blocks — let user choose
            val labels = blocks.mapIndexed { i, b -> "Block ${i + 1}: ${b.take(40)}…" }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.copy_code))
                .setItems(labels) { _, which ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Code", blocks[which]))
                    Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun copyMessage(msg: ChatMessage) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Aria message", msg.content))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareMessage(msg: ChatMessage) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, msg.content)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun setupMessageSwipe() {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos < 0 || pos >= messages.size) return

                if (direction == ItemTouchHelper.RIGHT) {
                    // Reply: reset item position and show reply bar
                    adapter.notifyItemChanged(pos)
                    binding.recyclerChat.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    setReplyTo(messages[pos])
                    return
                }

                // LEFT: delete
                binding.recyclerChat.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val removed = messages[pos]
                messages.removeAt(pos)
                adapter.refreshLastAssistantPosition()
                adapter.notifyItemRemoved(pos)

                Snackbar.make(binding.recyclerChat, getString(R.string.message_deleted), Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.undo)) {
                        val insertPos = pos.coerceAtMost(messages.size)
                        messages.add(insertPos, removed)
                        adapter.refreshLastAssistantPosition()
                        adapter.notifyItemInserted(insertPos)
                    }
                    .addCallback(object : Snackbar.Callback() {
                        override fun onDismissed(bar: Snackbar?, event: Int) {
                            if (event != DISMISS_EVENT_ACTION && removed.id > 0) {
                                scope.launch(Dispatchers.IO) { dao.deleteMessage(removed) }
                            }
                        }
                    })
                    .show()
            }

            override fun onChildDraw(
                c: android.graphics.Canvas, recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float,
                actionState: Int, isCurrentlyActive: Boolean
            ) {
                if (dX > 0) {
                    // RIGHT swipe: draw reply icon, limit displacement
                    val limitedDx = dX.coerceAtMost(200f)
                    val itemView = viewHolder.itemView
                    val icon = replySwipeIcon
                    if (icon != null) {
                        val iconSize = 24 * resources.displayMetrics.density
                        val iconMargin = 16 * resources.displayMetrics.density
                        val iconTop = itemView.top + (itemView.height - iconSize.toInt()) / 2
                        val iconBottom = iconTop + iconSize.toInt()
                        val iconLeft = itemView.left + iconMargin.toInt()
                        val iconRight = iconLeft + iconSize.toInt()
                        icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        icon.draw(c)
                    }
                    super.onChildDraw(c, recyclerView, viewHolder, limitedDx, dY, actionState, isCurrentlyActive)
                } else {
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                return 0.3f
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.recyclerChat)
    }

    // ---- Reply ----

    private fun setReplyTo(msg: ChatMessage) {
        replyToMessage = msg
        binding.txtReplyPreview.text = msg.content.take(100)
        binding.replyPreviewBar.isVisible = true
        binding.editMessage.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.editMessage, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun clearReply() {
        replyToMessage = null
        binding.replyPreviewBar.isVisible = false
    }

    // ---- Regenerate ----

    private fun regenerateLastResponse() {
        if (isStreaming || messages.size < 2) return
        val lastIdx = messages.indexOfLast { it.role == "assistant" }
        if (lastIdx < 0) return
        val lastAssistant = messages[lastIdx]

        messages.removeAt(lastIdx)
        adapter.refreshLastAssistantPosition()
        adapter.notifyItemRemoved(lastIdx)
        if (lastAssistant.id > 0) {
            scope.launch(Dispatchers.IO) { dao.deleteMessage(lastAssistant) }
        }

        val lastUserMsg = messages.lastOrNull { it.role == "user" }
        if (lastUserMsg != null) {
            callAria(lastUserMsg.content)
        }
    }

    private fun retryLastError() {
        if (isStreaming) return
        val lastIdx = messages.indexOfLast { it.role == "assistant" && it.content.startsWith("\u274C") }
        if (lastIdx < 0) return
        val errorMsg = messages[lastIdx]

        messages.removeAt(lastIdx)
        adapter.refreshLastAssistantPosition()
        adapter.notifyItemRemoved(lastIdx)
        if (errorMsg.id > 0) {
            scope.launch(Dispatchers.IO) { dao.deleteMessage(errorMsg) }
        }

        val lastUserMsg = messages.lastOrNull { it.role == "user" }
        if (lastUserMsg != null) {
            callAria(lastUserMsg.content)
        }
    }

    // ---- Chat clear ----

    private fun clearChat() {
        messages.clear()
        adapter.refreshLastAssistantPosition()
        adapter.notifyDataSetChanged()
        updateEmptyState()
        prefs.newSessionKey()
        prefs.newConversationId()
        scope.launch(Dispatchers.IO) {
            dao.insertConversation(
                ConversationEntity(id = prefs.currentConversationId, title = "New conversation")
            )
        }
    }

    // ---- Media ----

    private fun onAttachClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        showAttachmentOptions()
    }

    private fun showAttachmentOptions() {
        val options = arrayOf(
            "Photo from gallery",
            "Video from gallery",
            "Take a photo",
            "Record a video"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Attach media")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickMediaLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                    1 -> pickMediaLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                    2 -> launchCamera()
                    3 -> launchVideoCapture()
                }
            }
            .show()
    }

    private fun launchCamera() {
        val photoFile = File(cacheDir, "camera/photo_${System.currentTimeMillis()}.jpg")
        photoFile.parentFile?.mkdirs()
        cameraImageUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
        takePictureLauncher.launch(cameraImageUri!!)
    }

    private fun launchVideoCapture() {
        val videoFile = File(cacheDir, "camera/video_${System.currentTimeMillis()}.mp4")
        videoFile.parentFile?.mkdirs()
        cameraImageUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", videoFile)
        takeVideoLauncher.launch(cameraImageUri!!)
    }

    private fun getFileSize(uri: Uri): Long {
        return contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
            } else 0L
        } ?: 0L
    }

    private fun handlePickedMedia(uri: Uri, isVideo: Boolean = false) {
        val mimeType = contentResolver.getType(uri) ?: if (isVideo) "video/mp4" else "image/jpeg"
        val type = if (mimeType.startsWith("video")) "video" else "image"

        if (type == "video") {
            val size = getFileSize(uri)
            if (size > 10 * 1024 * 1024) {
                Toast.makeText(this, "Video too large (max 10 MB)", Toast.LENGTH_LONG).show()
                return
            }
        }

        pendingMediaUri = uri
        pendingMediaType = type
        pendingMediaMime = mimeType

        binding.mediaPreviewContainer.isVisible = true
        binding.imgMediaPreview.load(uri) {
            crossfade(true)
            if (type == "video") {
                decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
            }
        }
    }

    private fun clearPendingMedia() {
        pendingMediaUri = null
        pendingMediaType = null
        pendingMediaMime = null
        binding.mediaPreviewContainer.isVisible = false
    }

    private fun encodeMediaToBase64(uri: Uri, type: String): String {
        return if (type == "image") {
            resizeAndEncodeImage(uri)
        } else {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("Cannot read media")
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    private fun resizeAndEncodeImage(uri: Uri, maxDim: Int = 1024): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

        val scale = maxOf(1, minOf(options.outWidth / maxDim, options.outHeight / maxDim))
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
        val bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw Exception("Cannot decode image")

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        bitmap.recycle()
        return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun copyMediaToInternal(uri: Uri, type: String): Uri {
        val ext = if (type == "video") "mp4" else "jpg"
        val dir = File(filesDir, "media")
        dir.mkdirs()
        val file = File(dir, "media_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("Cannot copy media")
        return Uri.fromFile(file)
    }

    // ---- Send message ----

    private fun sendMessage() {
        val text = binding.editMessage.text?.toString()?.trim() ?: ""
        if (text.isEmpty() && pendingMediaUri == null) return
        if (isSending) return

        binding.btnSend.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        binding.editMessage.text?.clear()

        // Capture reply state before clearing
        val reply = replyToMessage
        clearReply()

        val mediaUri = pendingMediaUri
        if (mediaUri != null) {
            val mediaType = pendingMediaType ?: "image"
            val mediaMime = pendingMediaMime ?: "image/jpeg"
            scope.launch(Dispatchers.IO) {
                try {
                    val base64 = encodeMediaToBase64(mediaUri, mediaType)
                    // Copy to internal storage for persistent access
                    val internalUri = copyMediaToInternal(mediaUri, mediaType)
                    val attachment = MediaAttachment(
                        uri = internalUri.toString(),
                        type = mediaType,
                        mimeType = mediaMime,
                        base64 = base64
                    )
                    withContext(Dispatchers.Main) {
                        clearPendingMedia()
                        addMessage(
                            ChatMessage(
                                role = "user", content = text, media = attachment,
                                mediaUri = attachment.uri, mediaType = attachment.type,
                                mediaMimeType = attachment.mimeType,
                                replyToId = reply?.id,
                                replyToContent = reply?.content?.take(100)
                            )
                        )
                        val textToSend = if (reply != null) {
                            "[Replying to: \"${reply.content.take(80)}\"]\n$text"
                        } else text
                        callAria(textToSend, attachment)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Media error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }

        addMessage(ChatMessage(
            role = "user", content = text,
            replyToId = reply?.id,
            replyToContent = reply?.content?.take(100)
        ))
        val textToSend = if (reply != null) {
            "[Replying to: \"${reply.content.take(80)}\"]\n$text"
        } else text
        callAria(textToSend)
    }

    private fun addMessage(msg: ChatMessage) {
        val msgWithConv = msg.copy(conversationId = prefs.currentConversationId)
        messages.add(msgWithConv)
        adapter.refreshLastAssistantPosition()
        adapter.notifyItemInserted(messages.size - 1)
        binding.recyclerChat.scrollToPosition(messages.size - 1)
        updateEmptyState()

        // Don't persist placeholder
        if (msgWithConv.content == "\u2026") return

        scope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            val id = dao.insertMessage(msgWithConv)
            withContext(Dispatchers.Main) {
                val idx = messages.indexOf(msgWithConv)
                if (idx >= 0) {
                    messages[idx] = msgWithConv.copy(id = id)
                }
            }
            // Update conversation
            val convId = prefs.currentConversationId
            val conv = dao.getConversation(convId)
            if (conv != null && msgWithConv.role == "user" && conv.title == "New conversation") {
                dao.updateConversation(convId, msgWithConv.content.take(50), System.currentTimeMillis())
            } else if (conv != null) {
                dao.updateConversation(convId, conv.title, System.currentTimeMillis())
            }
        }
    }

    private fun updateLastMessage(text: String) {
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            messages[messages.size - 1] = messages.last().copy(content = text)
            adapter.notifyItemChanged(messages.size - 1)
            binding.recyclerChat.scrollToPosition(messages.size - 1)
        }
    }

    private fun saveAssistantMessage() {
        if (messages.isEmpty() || messages.last().role != "assistant") return
        val msg = messages.last()
        val msgWithConv = msg.copy(conversationId = prefs.currentConversationId)
        scope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            if (msg.id == 0L) {
                val id = dao.insertMessage(msgWithConv)
                withContext(Dispatchers.Main) {
                    val idx = messages.size - 1
                    if (idx >= 0 && messages[idx].role == "assistant") {
                        messages[idx] = msgWithConv.copy(id = id)
                    }
                }
            } else {
                dao.updateMessage(msgWithConv)
            }
        }
    }

    // ---- Call Aria ----

    private fun callAria(userMessage: String, media: MediaAttachment? = null) {
        isSending = true
        isStreaming = true

        val messageToSend = userMessage.ifBlank {
            if (media != null) "\uD83D\uDCCE" else return
        }

        // Save for potential retry if connection dies in background
        pendingRetryMessage = messageToSend
        pendingRetryMedia = media

        addMessage(ChatMessage(role = "assistant", content = "\u2026"))
        adapter.streamingPosition = messages.size - 1

        scope.launch {
            try {
                // Auto-reconnect if needed
                var client = openClawClient
                if (client == null || !client.isConnected()) {
                    setStatus("Reconnecting\u2026")
                    client?.disconnect()
                    val newClient = OpenClawClient(prefs.serverUrl, prefs.apiToken, applicationContext)
                    openClawClient = newClient
                    client = newClient
                    client.ensureConnected()
                    setStatus(null)
                }

                var completed = false
                client.chat(messageToSend, prefs.effectiveSessionKey, object : OpenClawClient.StreamListener {
                    override fun onStart() {
                        // Typing dots animation in the bubble handles the visual feedback
                    }

                    override fun onTextDelta(delta: String, fullText: String) {
                        runOnUiThread {
                            if (!completed) updateLastMessage(fullText)
                        }
                    }

                    override fun onComplete(fullText: String) {
                        runOnUiThread {
                            if (completed) return@runOnUiThread
                            completed = true

                            // Clear retry — message delivered successfully
                            pendingRetryMessage = null
                            pendingRetryMedia = null

                            adapter.streamingPosition = -1
                            isStreaming = false
                            updateLastMessage(fullText)
                            saveAssistantMessage()
                            isSending = false
                            setStatus(null)

                            if (prefs.autoTts && fullText.isNotBlank()) {
                                speak(fullText)
                            }

                            // Notification if app is in background
                            val inBackground = isAppInBackground()
                            if (prefs.notificationsEnabled && inBackground && fullText.isNotBlank()) {
                                AriaNotifications.showMessageNotification(
                                    this@MainActivity, fullText
                                )
                            }
                        }
                    }

                    override fun onError(error: String) {
                        runOnUiThread {
                            if (completed) return@runOnUiThread
                            completed = true

                            adapter.streamingPosition = -1
                            isStreaming = false
                            isSending = false

                            // Network error while in background → keep pending for auto-retry
                            val isNetworkError = error.contains("SocketException", true)
                                    || error.contains("connection abort", true)
                                    || error.contains("Connection reset", true)
                                    || error.contains("WebSocket error", true)

                            if (isNetworkError && isAppInBackground()) {
                                // Remove the error bubble, will auto-retry on resume
                                if (messages.isNotEmpty() && messages.last().role == "assistant") {
                                    messages.removeAt(messages.size - 1)
                                    adapter.refreshLastAssistantPosition()
                                    adapter.notifyItemRemoved(messages.size)
                                }
                                setStatus(null)
                            } else {
                                // Real error or user is in foreground — show it
                                pendingRetryMessage = null
                                pendingRetryMedia = null
                                updateLastMessage("\u274C $error")
                                setStatus(null)
                            }
                        }
                    }
                }, media)
            } catch (e: Exception) {
                runOnUiThread {
                    adapter.streamingPosition = -1
                    isStreaming = false
                    updateLastMessage("\u274C ${e.message}")
                    pendingRetryMessage = null
                    pendingRetryMedia = null
                    isSending = false
                    setStatus(null)
                }
            }
        }
    }

    private fun setStatus(text: String?) {
        binding.txtStatus.isVisible = text != null
        binding.txtStatus.text = text ?: ""
    }

    // ---- TTS ----

    private fun initElevenLabs() {
        val key = prefs.elevenLabsKey
        elevenTts = if (key.isNotBlank()) {
            ElevenLabsTTS(key, prefs.elevenLabsVoiceId, cacheDir)
        } else null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.FRANCE
            tts.setSpeechRate(1.0f)
        }
    }

    override fun onResume() {
        super.onResume()
        // Recreate if language changed in settings
        if (prefs.language != currentLanguage) {
            recreate()
            return
        }
        initElevenLabs()
        initWhisper()
        initAndroidStt()
        // Refresh font size from settings only if changed
        if (prefs.fontSize != adapter.fontSize) {
            adapter.fontSize = prefs.fontSize
            adapter.notifyDataSetChanged()
        }
        // Auto-reconnect gateway if disconnected, then retry pending message
        if (openClawClient == null || openClawClient?.isConnected() != true) {
            val retryMsg = pendingRetryMessage
            val retryMedia = pendingRetryMedia
            // Reconnect and retry after connection is established
            openClawClient?.disconnect()
            openClawClient = OpenClawClient(prefs.serverUrl, prefs.apiToken, applicationContext)
            scope.launch {
                try {
                    openClawClient?.ensureConnected()
                    setStatus(null)
                    // Now safe to retry — clear pending only after successful reconnect
                    if (retryMsg != null && !isSending) {
                        pendingRetryMessage = null
                        pendingRetryMedia = null
                        callAria(retryMsg, retryMedia)
                    }
                } catch (e: Exception) {
                    setStatus("Offline")
                }
            }
        } else {
            // Already connected — retry immediately if needed
            val retryMsg = pendingRetryMessage
            if (retryMsg != null && !isSending) {
                pendingRetryMessage = null
                val retryMedia = pendingRetryMedia
                pendingRetryMedia = null
                callAria(retryMsg, retryMedia)
            }
        }
    }

    private fun speak(text: String) {
        if (prefs.ttsProvider == "elevenlabs") {
            val eleven = elevenTts
            if (eleven != null) {
                setStatus("Aria speaking\u2026")
                scope.launch {
                    try {
                        eleven.speak(text)
                    } catch (e: Exception) {
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aria_response")
                    } finally {
                        setStatus(null)
                    }
                }
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aria_response")
            }
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aria_response")
        }
    }

    // ---- STT ----

    private fun initWhisper() {
        val key = prefs.openaiKey
        whisperStt?.stopRecording()
        whisperStt = if (key.isNotBlank()) WhisperSTT(key, cacheDir) else null
    }

    private fun initAndroidStt() {
        if (androidStt == null) {
            androidStt = AndroidSTT(this)
        }
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 100
            )
        }
    }

    private fun toggleListening() {
        if (isListening) stopListening() else startListening()
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission()
            return
        }

        if (prefs.sttProvider == "android") {
            startListeningAndroid()
        } else {
            startListeningWhisper()
        }
    }

    private fun startMicPulse() {
        val scaleX = ObjectAnimator.ofFloat(binding.btnMic, "scaleX", 1f, 1.2f, 1f).apply {
            duration = 800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(binding.btnMic, "scaleY", 1f, 1.2f, 1f).apply {
            duration = 800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        micPulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
    }

    private fun stopMicPulse() {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        binding.btnMic.scaleX = 1f
        binding.btnMic.scaleY = 1f
    }

    private fun startListeningWhisper() {
        val whisper = whisperStt
        if (whisper == null) {
            Toast.makeText(this, "OpenAI key not configured", Toast.LENGTH_SHORT).show()
            return
        }

        whisper.startRecording(object : WhisperSTT.Callback {
            override fun onSilenceDetected() {
                runOnUiThread { stopListening() }
            }
        })
        isListening = true
        binding.btnMic.setBackgroundResource(R.drawable.circle_button_accent)
        startMicPulse()
        setStatus("Listening\u2026")
    }

    private fun startListeningAndroid() {
        val stt = androidStt
        if (stt == null || !stt.isAvailable) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            return
        }

        stt.startListening(object : AndroidSTT.Callback {
            override fun onResult(text: String) {
                runOnUiThread {
                    isListening = false
                    stopMicPulse()
                    binding.btnMic.setBackgroundResource(R.drawable.circle_button)
                    setStatus(null)
                    binding.editMessage.setText(text)
                    sendMessage()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    isListening = false
                    stopMicPulse()
                    binding.btnMic.setBackgroundResource(R.drawable.circle_button)
                    setStatus(null)
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        })
        isListening = true
        binding.btnMic.setBackgroundResource(R.drawable.circle_button_accent)
        startMicPulse()
        setStatus("Listening\u2026")
    }

    private fun stopListening() {
        if (!isListening) return

        stopMicPulse()

        if (prefs.sttProvider == "android") {
            androidStt?.stopListening()
            isListening = false
            binding.btnMic.setBackgroundResource(R.drawable.circle_button)
            setStatus(null)
            return
        }

        val whisper = whisperStt ?: return
        whisper.stopRecording()
        isListening = false
        binding.btnMic.setBackgroundResource(R.drawable.circle_button)
        setStatus("Transcribing\u2026")

        scope.launch {
            try {
                val text = whisper.transcribe()
                setStatus(null)
                if (text.isNotBlank()) {
                    binding.editMessage.setText(text)
                    sendMessage()
                } else {
                    Toast.makeText(this@MainActivity, "No text detected", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                setStatus(null)
                Toast.makeText(this@MainActivity, "Whisper error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Use onBackPressedDispatcher instead")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else if (isSearchActive) {
            closeSearch()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---- Biometric lock ----

    private fun showBiometricPrompt() {
        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Biometric not available, skip
            isAuthenticated = true
            return
        }

        // Hide content until authenticated
        binding.root.alpha = 0f

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isAuthenticated = true
                    binding.root.animate().alpha(1f).setDuration(200).start()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        finishAffinity()
                    } else {
                        isAuthenticated = true
                        binding.root.animate().alpha(1f).setDuration(200).start()
                    }
                }

                override fun onAuthenticationFailed() {
                    // Keep waiting for retry
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_cancel))
            .build()

        prompt.authenticate(promptInfo)
    }

    // ---- Accent color ----

    private fun applyLanguage(lang: String) {
        if (lang == "system") return
        val locale = if (lang == "fr") java.util.Locale.FRENCH else java.util.Locale.ENGLISH
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun applyAccentColor() {
        val color = prefs.accentColor
        val tint = android.content.res.ColorStateList.valueOf(color)
        // Apply tint to preserve the round drawable shape
        binding.btnSend.backgroundTintList = tint
        binding.fabScrollBottom.backgroundTintList = tint
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        openClawClient?.disconnect()
        elevenTts?.stop()
        androidStt?.destroy()
        if (::tts.isInitialized) tts.shutdown()
    }
}
