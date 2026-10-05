package com.frooty.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var messageInput: EditText
    private lateinit var chatText: TextView
    private lateinit var statusText: TextView
    private lateinit var orbStateText: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var orbView: ImageView
    private lateinit var tts: TextToSpeech

    private val aiService = AIService()
    private var speechRecognizer: SpeechRecognizer? = null

    companion object {
        private const val MIC_PERMISSION_REQUEST = 1001
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

        tts = TextToSpeech(this, this)

        findViewById<Button>(R.id.sendButton).setOnClickListener {
            sendTypedMessage()
        }

        findViewById<ImageButton>(R.id.voiceButton).setOnClickListener {
            startVoiceInput()
        }

        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedMessage()
                true
            } else {
                false
            }
        }

        requestMicPermission()
    }

    private fun sendTypedMessage() {
        val message = messageInput.text.toString().trim()
        if (message.isEmpty()) return

        messageInput.text.clear()
        handleMessage(message)
    }

    private fun handleMessage(message: String) {
        appendChat("आप", message)
        setState("THINKING", "FROOTY सोच रही है...")

        lifecycleScope.launch {
            try {
                val response = aiService.ask(message)

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

    private fun appendChat(sender: String, message: String) {
        chatText.append("\n\n$sender:\n$message")

        chatScroll.post {
            chatScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun setState(state: String, status: String) {
        orbStateText.text = state
        statusText.text = status
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION_REQUEST
            )
        }
    }

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setState("VOICE ERROR", "Speech recognition available नहीं है")
            return
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission()
            return
        }

        setState("LISTENING", "FROOTY सुन रही है...")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

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
            tts.language = Locale("hi", "IN")
            tts.setSpeechRate(0.95f)
            setState("READY", "AI ready")
        } else {
            setState("READY", "TTS unavailable")
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
