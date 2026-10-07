package com.frooty.ai

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var messageInput: EditText
    private lateinit var chatText: TextView
    private lateinit var statusText: TextView
    private lateinit var orbStateText: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var orbView: ImageView
    private lateinit var wakeWordButton: Button
    private lateinit var assistantSetupButton: Button
    private lateinit var videoGenerationButton: Button
    private lateinit var videoGenerationStatus: TextView
    private lateinit var screenContextButton: Button
    private lateinit var tts: TextToSpeech
    private var orbAnimator: AnimatorSet? = null

    private val aiService by lazy { AIService() }
    private lateinit var memoryStore: MemoryStore
    private lateinit var screenContextStore: ScreenContextStore
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeWordRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeWordListening = false
    private var pendingMicAction: (() -> Unit)? = null
    private val videoGenerationService by lazy { VideoGenerationService(this) }
    private var videoPollingJob: Job? = null
    private var pendingVideoSaveUrl: String? = null
    private val saveVideoLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { destination ->
        val videoUrl = pendingVideoSaveUrl
        pendingVideoSaveUrl = null
        if (destination != null && videoUrl != null) saveVideo(videoUrl, destination)
    }

    companion object {
        private const val WAKE_WORD_RETRY_DELAY_MS = 900L
        const val EXTRA_START_VOICE_INPUT = "com.frooty.ai.START_VOICE_INPUT"
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) action?.invoke()
        else setState("READY", "Microphone permission नहीं मिली")
    }

    override fun onResume() {
        super.onResume()
        if (::assistantSetupButton.isInitialized) {
            val isActive = VoiceInteractionService.isActiveService(
                this,
                ComponentName(this, FrootyVoiceInteractionService::class.java)
            )
            assistantSetupButton.text =
                if (isActive) "SYSTEM ASSISTANT: ACTIVE" else "SET AS SYSTEM ASSISTANT"
        }
        if (::videoGenerationButton.isInitialized) resumePendingVideoGeneration()
        if (::screenContextButton.isInitialized) refreshScreenContextButton()
    }

    private fun openVideoGenerator() {
        val pending = videoGenerationService.pending()
        if (pending != null) {
            resumePendingVideoGeneration()
            return
        }

        val promptInput = EditText(this).apply {
            hint = "Describe the video you want to create..."
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(40, 24, 40, 24)
        }
        val promptDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Create an AI video")
            .setMessage(
                "Veo 3.1 creates an approximately 8-second video. No daily limit is configured. " +
                    "Generation may incur Google AI charges and can take several minutes " +
                    "Your prompt is sent to Google's video service."
            )
            .setView(promptInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Generate video", null)
            .create()

        promptDialog.setOnShowListener {
            promptDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val prompt = promptInput.text.toString().trim()
                    if (prompt.length !in 10..1500) {
                        promptInput.error = "Enter 10–1500 characters"
                        return@setOnClickListener
                    }
                    promptDialog.dismiss()
                    startVideoGeneration(prompt)
                }
        }
        promptDialog.show()
    }

    private fun startVideoGeneration(prompt: String) {
        if (videoPollingJob?.isActive == true) return
        setVideoBusy(true, "Submitting request to Veo 3.1…")
        videoPollingJob = lifecycleScope.launch {
            try {
                val operationName = videoGenerationService.start(prompt)
                videoGenerationService.savePending(operationName, prompt)
                awaitVideoGeneration(operationName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setVideoBusy(false, "Video request failed: ${e.message ?: "check Firebase setup"}")
            }
        }
    }

    private fun resumePendingVideoGeneration() {
        if (videoPollingJob?.isActive == true) return
        val pending = videoGenerationService.pending() ?: return
        pollVideoGeneration(pending.operationName)
    }

    private fun pollVideoGeneration(operationName: String) {
        if (videoPollingJob?.isActive == true) return
        setVideoBusy(true, "Veo is generating your video…")
        videoPollingJob = lifecycleScope.launch {
            try {
                awaitVideoGeneration(operationName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setVideoBusy(false, "Video is still pending or failed. Tap to check again.")
                Toast.makeText(
                    this@MainActivity,
                    e.message ?: "Could not check video status",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun awaitVideoGeneration(operationName: String) {
        val videoUrl = videoGenerationService.waitForVideo(operationName) {
            videoGenerationStatus.text = "Veo is generating your video…"
        }
        videoGenerationService.clearPending()
        setVideoBusy(false, "Your video is ready.")
        showGeneratedVideo(videoUrl)
    }

    private fun setVideoBusy(busy: Boolean, status: String) {
        videoGenerationButton.isEnabled = !busy
        videoGenerationButton.text =
            if (busy) "GENERATING VIDEO…" else "CREATE AI VIDEO · VEO"
        videoGenerationStatus.text = status
        videoGenerationStatus.visibility = View.VISIBLE
    }

    private fun showGeneratedVideo(videoUrl: String) {
        val preview = VideoView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 220).toInt()
            )
            setVideoURI(Uri.parse(videoUrl))
            setOnPreparedListener { player ->
                player.isLooping = true
                start()
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@MainActivity, "Video preview could not be played.", Toast.LENGTH_LONG).show()
                true
            }
        }
        val controls = MediaController(this)
        controls.setAnchorView(preview)
        preview.setMediaController(controls)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Your Veo video")
            .setView(preview)
            .setNegativeButton("Close", null)
            .setPositiveButton("Save MP4") { _, _ ->
                pendingVideoSaveUrl = videoUrl
                saveVideoLauncher.launch("frooty-video.mp4")
            }
            .setOnDismissListener { preview.stopPlayback() }
            .show()
    }

    private fun saveVideo(videoUrl: String, destination: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(destination)
                        ?: error("Could not open the selected destination")
                    output.use { stream ->
                        val connection = URL(videoUrl).openConnection().apply {
                            connectTimeout = 15_000
                            readTimeout = 60_000
                        }
                        connection.getInputStream().use { input -> input.copyTo(stream) }
                    }
                }
                Toast.makeText(this@MainActivity, "Video saved.", Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Video could not be saved: ${e.message ?: "download failed"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messageInput = findViewById(R.id.messageInput)
        chatText = findViewById(R.id.chatText)
        statusText = findViewById(R.id.statusText)
        orbStateText = findViewById(R.id.orbStateText)
        chatScroll = findViewById(R.id.chatScroll)
        orbView = findViewById(R.id.orbView)
        wakeWordButton = findViewById(R.id.wakeWordButton)
        assistantSetupButton = findViewById(R.id.assistantSetupButton)
        videoGenerationButton = findViewById(R.id.videoGenerationButton)
        videoGenerationStatus = findViewById(R.id.videoGenerationStatus)
        screenContextButton = findViewById(R.id.screenContextButton)
        memoryStore = MemoryStore(this)
        screenContextStore = ScreenContextStore(this)

        tts = TextToSpeech(this, this)

        findViewById<Button>(R.id.sendButton).setOnClickListener {
            sendTypedMessage()
        }

        findViewById<ImageButton>(R.id.voiceButton).setOnClickListener {
            startVoiceInput()
        }

        findViewById<Button>(R.id.memoryButton).setOnClickListener { showMemories() }
        wakeWordButton.setOnClickListener { toggleWakeWordListening() }
        assistantSetupButton.setOnClickListener { showAssistantSetup() }
        videoGenerationButton.setOnClickListener { openVideoGenerator() }
        screenContextButton.setOnClickListener { openScreenContext() }

        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedMessage()
                true
            } else {
                false
            }
        }

        startVoiceInputIfRequested(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startVoiceInputIfRequested(intent)
    }

    private fun isScreenReaderServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { info ->
            info.resolveInfo.serviceInfo.packageName == packageName &&
                info.resolveInfo.serviceInfo.name == FrootyAccessibilityService::class.java.name
        }
    }

    private fun refreshScreenContextButton() {
        val serviceEnabled = isScreenReaderServiceEnabled()
        val enabled = screenContextStore.isCaptureEnabled() && serviceEnabled
        if (!serviceEnabled && screenContextStore.current().isNotEmpty()) {
            screenContextStore.clear()
        }
        screenContextButton.text = if (enabled) {
            "SCREEN READING · ON"
        } else {
            "SCREEN READING · OFF"
        }
    }

    private fun openScreenContext() {
        val isEnabled = screenContextStore.isCaptureEnabled() && isScreenReaderServiceEnabled()
        if (!isEnabled) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Screen reading चालू करें?")
                .setMessage(
                    "Android की Accessibility Settings में FROOTY को manually enable करना होगा। " +
                        "चालू रहने पर FROOTY अन्य apps की दिखाई देने वाली text locally पढ़ेगी; " +
                        "password fields छोड़ दिए जाते हैं। Text अपने-आप upload नहीं होता। " +
                        "जब आप “Ask FROOTY” दबाएँगे, selected screen text आपके configured AI provider को भेजा जाएगा।"
                )
                .setNegativeButton("अभी नहीं", null)
                .setPositiveButton("सहमति देकर Settings खोलें") { _, _ ->
                    screenContextStore.setCaptureEnabled(true)
                    try {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        screenContextStore.setCaptureEnabled(false)
                        setState("ERROR", "इस device पर Accessibility Settings उपलब्ध नहीं हैं")
                    }
                }
                .show()
            return
        }

        val snapshot = screenContextStore.current()
        if (snapshot.isBlank()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Screen text उपलब्ध नहीं")
                .setMessage("जिस app के बारे में पूछना है उसे खोलें, फिर FROOTY पर लौटकर दोबारा दबाएँ।")
                .setPositiveButton("ठीक है", null)
                .setNeutralButton("Screen reading बंद करें") { _, _ ->
                    screenContextStore.setCaptureEnabled(false)
                    refreshScreenContextButton()
                }
                .show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Current screen text")
            .setMessage(snapshot)
            .setNegativeButton("बंद करें", null)
            .setNeutralButton("Screen reading बंद करें") { _, _ ->
                screenContextStore.setCaptureEnabled(false)
                refreshScreenContextButton()
            }
            .setPositiveButton("Ask FROOTY") { _, _ ->
                handleMessage(
                    "इस screen पर क्या दिखाई दे रहा है? मुख्य जानकारी सरल भाषा में बताओ।",
                    screenContext = snapshot
                )
            }
            .show()
    }

    private fun startVoiceInputIfRequested(launchIntent: Intent?) {
        if (launchIntent?.getBooleanExtra(EXTRA_START_VOICE_INPUT, false) != true) return
        launchIntent.removeExtra(EXTRA_START_VOICE_INPUT)
        window.decorView.post {
            ensureMicPermission { startVoiceInput() }
        }
    }

    private fun showAssistantSetup() {
        val isActive = VoiceInteractionService.isActiveService(
            this,
            ComponentName(this, FrootyVoiceInteractionService::class.java)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (isActive) "FROOTY system assistant है" else "FROOTY को system assistant बनाएँ")
            .setMessage(
                if (isActive) {
                    "आपका device assistant gesture/button FROOTY खोल सकता है। “Hey FROOTY” " +
                        "custom hotword अपने-आप उपलब्ध नहीं होता; उसके लिए compatible phone hardware " +
                        "और Android hotword enrollment चाहिए।"
                } else {
                    "Android Settings में Default apps → Digital assistant app खोलकर FROOTY चुनें। " +
                        "फिर device का assistant gesture/button FROOTY खोलेगा और voice input शुरू करेगा। " +
                        "यह “Hey FROOTY” custom hotword अपने-आप चालू नहीं करता।"
                }
            )
            .setNegativeButton("रद्द करें", null)
            .setPositiveButton(if (isActive) "ठीक है" else "Settings खोलें") { _, _ ->
                if (isActive) return@setPositiveButton
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    try {
                        startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        setState("ERROR", "इस device पर assistant Settings उपलब्ध नहीं हैं")
                    }
                }
            }
            .show()
    }

    private fun sendTypedMessage() {
        val message = messageInput.text.toString().trim()
        if (message.isEmpty()) return

        messageInput.text.clear()
        handleMessage(message)
    }

    private fun handleMessage(message: String, screenContext: String = "") {
        if (wakeWordListening) stopWakeWordListening()
        appendChat("आप", message)
        val memoryCommand = Regex(
            """(?i)^(?:याद रखो कि|याद रखना कि|remember that)\s*(.+)$"""
        ).find(message.trim())?.groupValues?.get(1)
            ?: Regex("""(?i)^मेरा नाम\s+(.+?)\s+याद रख(?:ो|ना)$""")
                .find(message.trim())?.let { "User's name is ${it.groupValues[1]}." }

        if (memoryCommand != null) {
            try {
                memoryStore.add(memoryCommand)
                val confirmation = "ठीक है, मैंने यह बात आपके फोन में याद रखी है।"
                appendChat("FROOTY", confirmation)
                setState("READY", "Memory आपके device पर save हुई")
                speak(confirmation)
            } catch (e: Exception) {
                appendChat("FROOTY", "Memory save नहीं हो सकी: ${e.message ?: "storage error"}")
                setState("ERROR", "Memory save नहीं हुई")
            }
            return
        }

        if (Regex("""(?i)^(?:सब भूल जाओ|मेरी सारी यादें मिटा दो|forget all memories)$""")
                .matches(message.trim())) {
            try {
                memoryStore.forgetAll()
                val confirmation = "आपकी सारी saved memories मिटा दी हैं।"
                appendChat("FROOTY", confirmation)
                setState("READY", "Memory cleared")
            } catch (e: Exception) {
                appendChat("FROOTY", "Memory मिटाई नहीं जा सकी: ${e.message ?: "storage error"}")
                setState("ERROR", "Memory clear नहीं हुई")
            }
            return
        }

        setState("THINKING", "FROOTY सोच रही है...")

        lifecycleScope.launch {
            try {
                val response = aiService.ask(
                    message,
                    memoryStore.contextForPrompt(),
                    screenContext
                )

                appendChat("FROOTY", response)
                setState("SPEAKING", "FROOTY बोल रही है...")
                speak(response)

            } catch (e: Exception) {
                val safeMessage =
                    "AI connection नहीं हो पाया। Firebase setup, internet और App Check configuration check करें."

                appendChat("FROOTY", safeMessage)
                setState("ERROR", "AI connection problem")
            }
        }
    }

    private fun showMemories() {
        if (wakeWordListening) stopWakeWordListening()
        val memories = memoryStore.all()
        val description = if (memories.isEmpty()) {
            "अभी कोई memory save नहीं है।\n\nआप कह सकते हैं: “याद रखो कि मेरा नाम Naresh है।”"
        } else {
            memories.mapIndexed { index, item -> "${index + 1}. $item" }.joinToString("\n") +
                "\n\nRelevant saved facts are sent with your messages to the configured AI provider."
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("FROOTY Memory")
            .setMessage(description)
            .setPositiveButton("ठीक है", null)

        if (memories.isNotEmpty()) {
            dialog.setNeutralButton("एक memory हटाएँ") { _, _ ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("कौन-सी memory हटानी है?")
                    .setItems(memories.toTypedArray()) { _, index ->
                        val selectedMemory = memories[index]
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("यह memory हटाएँ?")
                            .setMessage(selectedMemory)
                            .setNegativeButton("रद्द करें", null)
                            .setPositiveButton("हटाएँ") { _, _ ->
                                try {
                                    memoryStore.forget(selectedMemory)
                                    setState("READY", "Memory removed")
                                } catch (e: Exception) {
                                    setState("ERROR", "Memory नहीं हटाई जा सकी: ${e.message ?: "storage error"}")
                                }
                            }
                            .show()
                    }
                    .setNegativeButton("रद्द करें", null)
                    .show()
            }
            dialog.setNegativeButton("सारी memory मिटाएँ") { _, _ ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("सारी memory मिटाएँ?")
                    .setMessage("यह वापस नहीं किया जा सकेगा।")
                    .setNegativeButton("रद्द करें", null)
                    .setPositiveButton("मिटाएँ") { _, _ ->
                        try {
                            memoryStore.forgetAll()
                            setState("READY", "Memory cleared")
                        } catch (e: Exception) {
                            setState("ERROR", "Memory clear नहीं हुई: ${e.message ?: "storage error"}")
                        }
                    }
                    .show()
            }
        }
        dialog.show()
    }

    private fun appendChat(sender: String, message: String) {
        chatText.append("\n\n$sender:\n$message")

        chatScroll.post {
            chatScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun setState(state: String, status: String) {
        orbStateText.text = state
        statusText.text = status
        orbAnimator?.cancel()
        orbView.animate().cancel()

        if (state in setOf("LISTENING", "THINKING", "SPEAKING")) {
            orbAnimator = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(orbView, View.SCALE_X, 1f, 1.07f, 1f).apply {
                        repeatCount = ValueAnimator.INFINITE
                    },
                    ObjectAnimator.ofFloat(orbView, View.SCALE_Y, 1f, 1.07f, 1f).apply {
                        repeatCount = ValueAnimator.INFINITE
                    }
                )
                duration = 1500
                start()
            }
        } else {
            orbView.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun ensureMicPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
        } else {
            pendingMicAction = onGranted
            requestMicPermission()
        }
    }

    private fun toggleWakeWordListening() {
        if (wakeWordListening) {
            stopWakeWordListening()
            setState("READY", "Wake word बंद है")
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Wake word चालू करें?")
            .setMessage(
                "FROOTY सिर्फ इस screen के खुले और सामने रहने तक microphone से “Hey FROOTY” सुनेगी। " +
                    "App background में जाते ही सुनना बंद होगा। Android की speech recognition service " +
                    "audio को process कर सकती है; यह on-device-only guarantee नहीं है।"
            )
            .setNegativeButton("अभी नहीं", null)
            .setPositiveButton("सहमति देकर शुरू करें") { _, _ ->
                ensureMicPermission { startWakeWordListening() }
            }
            .show()
    }

    private fun startWakeWordListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setState("VOICE ERROR", "Speech recognition available नहीं है")
            return
        }
        if (wakeWordListening) return

        speechRecognizer?.destroy()
        speechRecognizer = null
        wakeWordListening = true
        wakeWordButton.text = "STOP WAKE WORD"
        setState("LISTENING", "“Hey FROOTY” सुन रही है — बंद करने के लिए बटन दबाएँ")
        startWakeWordRecognition()
    }

    private fun startWakeWordRecognition() {
        if (!wakeWordListening || isFinishing || isDestroyed) return

        wakeWordRecognizer?.destroy()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        wakeWordRecognizer = recognizer
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() {
                    setState("LISTENING", "“Hey FROOTY” सुन रही है")
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    if (wakeWordRecognizer !== recognizer || !wakeWordListening) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stopWakeWordListening()
                        setState("ERROR", "Microphone permission हट गई")
                        return
                    }
                    setState("LISTENING", "Speech recognition फिर connect हो रही है...")
                    mainHandler.postDelayed(
                        { if (wakeWordListening) startWakeWordRecognition() },
                        WAKE_WORD_RETRY_DELAY_MS
                    )
                }

                override fun onResults(results: Bundle?) {
                    if (wakeWordRecognizer !== recognizer || !wakeWordListening) return
                    val phrase = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    val command = extractWakeWordCommand(phrase)
                    if (command == null) {
                        mainHandler.post { startWakeWordRecognition() }
                    } else {
                        stopWakeWordListening()
                        if (command.isBlank()) {
                            setState("LISTENING", "जी, बताइए...")
                            mainHandler.postDelayed({ startVoiceInput() }, 350)
                        } else {
                            messageInput.setText(command)
                            handleMessage(command)
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }
        )
        recognizer.startListening(createHindiRecognitionIntent())
    }

    private fun extractWakeWordCommand(phrase: String): String? {
        val pattern =
            """^(?:hey\s+)?(?:frooty|fruity|हे\s*फ्रूटी|फ्रूटी)(?:\s+|[,.:;-]+|$)(.*)$"""
        return Regex(pattern, RegexOption.IGNORE_CASE)
            .find(phrase.trim())
            ?.groupValues
            ?.get(1)
            ?.trim()
    }

    private fun stopWakeWordListening() {
        wakeWordListening = false
        mainHandler.removeCallbacksAndMessages(null)
        wakeWordRecognizer?.destroy()
        wakeWordRecognizer = null
        wakeWordButton.text = "START WAKE WORD"
    }

    private fun createHindiRecognitionIntent() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ensureMicPermission { startVoiceInput() }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setState("VOICE ERROR", "Speech recognition available नहीं है")
            return
        }

        stopWakeWordListening()

        setState("LISTENING", "FROOTY सुन रही है...")

        val intent = createHindiRecognitionIntent()

        speechRecognizer?.destroy()

        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->

                recognizer.setRecognitionListener(
                    object : RecognitionListener {

                        override fun onReadyForSpeech(params: Bundle?) = Unit

                        override fun onBeginningOfSpeech() {
                            setState("LISTENING", "FROOTY सुन रही है...")
                        }

                        override fun onRmsChanged(rmsdB: Float) = Unit

                        override fun onBufferReceived(buffer: ByteArray?) = Unit

                        override fun onEndOfSpeech() {
                            setState("THINKING", "समझ रही है...")
                        }

                        override fun onError(error: Int) {
                            setState("READY", "Voice input फिर try करें")
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                            val result = matches?.firstOrNull()?.trim()

                            if (!result.isNullOrEmpty()) {
                                messageInput.setText(result)
                                handleMessage(result)
                            } else {
                                setState("READY", "Voice input नहीं मिला")
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) = Unit

                        override fun onEvent(
                            eventType: Int,
                            params: Bundle?
                        ) = Unit
                    }
                )

                recognizer.startListening(intent)
            }
    }

    private fun speak(text: String) {
        if (!::tts.isInitialized) return

        tts.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "FROOTY_REPLY"
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.forLanguageTag("hi-IN")
            tts.setSpeechRate(0.95f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (orbStateText.text == "SPEAKING") setState("READY", "AI ready")
                    }
                }

                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        if (orbStateText.text == "SPEAKING") {
                            setState("READY", "Voice output unavailable")
                        }
                    }
                }
            })
            setState("READY", "AI ready")
        } else {
            setState("READY", "TTS unavailable")
        }
    }

    override fun onDestroy() {
        stopWakeWordListening()
        orbAnimator?.cancel()
        orbView.animate().cancel()
        speechRecognizer?.destroy()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        if (wakeWordListening) {
            stopWakeWordListening()
            setState("READY", "App background में गई — wake word बंद")
        }
        mainHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }
}
