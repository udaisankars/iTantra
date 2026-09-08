package com.example.itantra

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class AppMode {
    SENDER,
    RECEIVER
}

private fun String.isImportantReceiverStatus(): Boolean {
    return contains("message received", ignoreCase = true) ||
            contains("error", ignoreCase = true) ||
            contains("failed", ignoreCase = true) ||
            contains("unable", ignoreCase = true) ||
            contains("permission", ignoreCase = true) ||
            contains("turn on", ignoreCase = true) ||
            contains("not supported", ignoreCase = true)
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MAX_RECORD_SECONDS = 20
        private const val MAX_SAMPLES = SAMPLE_RATE * MAX_RECORD_SECONDS
    }

    private val sttWorker = Executors.newSingleThreadExecutor()
    private val recordingFlag = AtomicBoolean(false)
    private val timerHandler = Handler(Looper.getMainLooper())

    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    private var capturedSamples = FloatArray(MAX_SAMPLES)
    private var capturedSampleCount = 0
    private var recordingStartedAt = 0L

    private val bleTextTransceiver by lazy {
        BleTextTransceiver(
            context = this,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }
    private lateinit var bluetoothPermissionLauncher:
            ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher:
            ActivityResultLauncher<String>

    private var modelReady by mutableStateOf(false)
    private var modelStatus by mutableStateOf("Loading offline STT model...")
    private var recording by mutableStateOf(false)
    private var processing by mutableStateOf(false)
    private var transcript by mutableStateOf("")
    private var receivedOriginalText by mutableStateOf("")
    private var elapsedSeconds by mutableIntStateOf(0)
    private var appLanguage by mutableStateOf(AppLanguage.ENGLISH)

    private var ttsReady by mutableStateOf(false)
    private var ttsStatus by mutableStateOf("Loading offline voice...")

    private var appMode by mutableStateOf(AppMode.SENDER)
    private var networkStatus by mutableStateOf("Preparing Bluetooth...")
    private var autoSendEnabled by mutableStateOf(true)
    private var nearbyReceivers by mutableStateOf<List<BleReceiver>>(emptyList())
    private var selectedReceiver by mutableStateOf<BleReceiver?>(null)

    private val alwaysReadyListener = object : ITantraReceiverBridge.Listener {
        override fun onReceiverStatusChanged(status: String) {
            if (appMode == AppMode.RECEIVER || status.isImportantReceiverStatus()) {
                networkStatus = status
            }
        }

        override fun onTtsStatusChanged(ready: Boolean, status: String) {
            ttsReady = ready
            ttsStatus = status
        }

        override fun onMessageReceived(message: LocalizedMessage) {
            transcript = message.localizedText
            receivedOriginalText = if (message.translated) {
                message.originalText
            } else {
                ""
            }
            modelStatus = if (message.verifiedPhrase) {
                "Verified emergency translation"
            } else if (message.translated) {
                "Translated to ${message.targetLanguage.nativeName}"
            } else {
                "Message received"
            }
            networkStatus = modelStatus
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!recording) return

            elapsedSeconds = (
                    (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000L
                    ).toInt().coerceAtMost(MAX_RECORD_SECONDS)

            timerHandler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLanguage = LanguagePreferences.get(this)

        val microphonePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                Toast.makeText(
                    this,
                    "Permission granted. Hold the button again to speak.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Microphone permission is required for speech recognition.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        bluetoothPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (hasRequiredBluetoothPermissions()) {
                startAlwaysReadyReceiver()
                requestNotificationPermissionIfNeeded()

                if (appMode == AppMode.RECEIVER) {
                    startReceiverMode()
                } else {
                    startSenderDiscovery()
                }
            } else {
                networkStatus =
                    "Nearby devices permission is required for Bluetooth"
                ttsReady = false
                ttsStatus =
                    "TTS not available until Nearby devices permission is granted"
                Toast.makeText(
                    this,
                    networkStatus,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // Receiving still works when notification permission is denied.
            // Android shows the active foreground service in system controls.
        }

        setContent {
            ITantraExpressiveTheme {
                ITantraExpressiveScreen(
                    modelReady = modelReady,
                    modelStatus = modelStatus,
                    recording = recording,
                    processing = processing,
                    transcript = transcript,
                    elapsedSeconds = elapsedSeconds,
                    maxRecordSeconds = MAX_RECORD_SECONDS,
                    ttsReady = ttsReady,
                    ttsStatus = ttsStatus,
                    appMode = appMode,
                    networkStatus = networkStatus,
                    autoSendEnabled = autoSendEnabled,
                    nearbyReceivers = nearbyReceivers,
                    selectedReceiver = selectedReceiver,
                    appLanguage = appLanguage,
                    receivedOriginalText = receivedOriginalText,
                    onTranscriptChange = {
                        transcript = it
                        receivedOriginalText = ""
                    },
                    onLanguageChange = { language ->
                        changeAppLanguage(language)
                    },
                    onAutoSendChange = { autoSendEnabled = it },
                    onReceiverSelected = { receiver ->
                        selectedReceiver = receiver
                        networkStatus = "Selected ${receiver.name}"
                    },
                    onModeChange = { newMode ->
                        changeMode(newMode)
                    },
                    onRefreshNetwork = {
                        if (hasRequiredBluetoothPermissions()) {
                            if (appMode == AppMode.SENDER) {
                                startSenderDiscovery()
                            } else {
                                startReceiverMode()
                            }
                        } else {
                            requestBluetoothPermissions()
                        }
                    },
                    onPressStart = {
                        if (
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            ITantraReceiverService.stopSpeaking(this)
                            if (ttsReady) ttsStatus = "Offline TTS ready"
                            startRecording()
                        } else {
                            microphonePermissionLauncher.launch(
                                Manifest.permission.RECORD_AUDIO
                            )
                        }
                    },
                    onPressEnd = {
                        stopRecordingAndTranscribe()
                    },
                    onSpeak = {
                        ITantraReceiverService.speak(this, transcript)
                    },
                    onSend = {
                        sendNetworkMessage(transcript)
                    }
                )
            }
        }

        if (hasRequiredBluetoothPermissions()) {
            startAlwaysReadyReceiver()
            requestNotificationPermissionIfNeeded()
            startSenderDiscovery()
        } else {
            requestBluetoothPermissions()
        }
        initializeRecognizer(appLanguage)
    }

    private fun sendNetworkMessage(message: String) {
        val target = selectedReceiver

        if (target == null) {
            networkStatus = "Select a nearby receiver first"
            Toast.makeText(
                this,
                networkStatus,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!hasRequiredBluetoothPermissions()) {
            networkStatus = "Grant Nearby devices permission first"
            requestBluetoothPermissions()
            return
        }

        networkStatus = "Sending encrypted Bluetooth message..."

        val wireMessage = try {
            ITantraMessage.create(
                sourceLanguage = appLanguage,
                originalText = message,
                senderName = "${Build.MANUFACTURER} ${Build.MODEL}"
            ).toWireText()
        } catch (error: Throwable) {
            networkStatus = error.message ?: "Unable to prepare message"
            Toast.makeText(this, networkStatus, Toast.LENGTH_LONG).show()
            return
        }

        bleTextTransceiver.sendMessage(
            receiver = target,
            groupId = ITantraProtocolConfig.GROUP_ID,
            sharedSecret = ITantraProtocolConfig.SHARED_SECRET,
            message = wireMessage
        ) { success, status ->
            networkStatus = status

            Toast.makeText(
                this,
                status,
                if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun changeMode(newMode: AppMode) {
        if (appMode == newMode || recording || processing) return

        appMode = newMode

        if (!hasRequiredBluetoothPermissions()) {
            nearbyReceivers = emptyList()
            selectedReceiver = null
            requestBluetoothPermissions()
            return
        }

        if (newMode == AppMode.RECEIVER) {
            bleTextTransceiver.stopScan()
            nearbyReceivers = emptyList()
            selectedReceiver = null
            startReceiverMode()
        } else {
            startSenderDiscovery()
        }
    }

    private fun startSenderDiscovery() {
        if (appMode != AppMode.SENDER) return

        if (!hasRequiredBluetoothPermissions()) {
            networkStatus = "Grant Nearby devices permission to search"
            return
        }

        nearbyReceivers = emptyList()
        networkStatus = "Searching for nearby Bluetooth receivers..."

        bleTextTransceiver.startScan(
            onStatusChange = { status ->
                networkStatus = status
            },
            onReceiversChanged = { receivers ->
                nearbyReceivers = receivers

                val currentTarget = selectedReceiver
                selectedReceiver = when {
                    currentTarget != null -> receivers.firstOrNull {
                        it.id == currentTarget.id
                    }
                    receivers.size == 1 -> receivers.first()
                    else -> null
                }

                if (receivers.size == 1 && currentTarget == null) {
                    networkStatus = "Selected ${receivers.first().name}"
                }
            }
        )
    }

    private fun startReceiverMode() {
        if (!hasRequiredBluetoothPermissions()) {
            networkStatus = "Grant Nearby devices permission to receive"
            return
        }

        startAlwaysReadyReceiver()
        networkStatus = "Always ready for nearby messages"
    }

    private fun startAlwaysReadyReceiver() {
        ITantraReceiverService.start(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    private fun requiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasRequiredBluetoothPermissions(): Boolean {
        return requiredBluetoothPermissions().all { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        networkStatus = "Allow Nearby devices for direct Bluetooth messages"
        bluetoothPermissionLauncher.launch(requiredBluetoothPermissions())
    }

    private fun changeAppLanguage(language: AppLanguage) {
        if (language == appLanguage || recording || processing) return

        appLanguage = language
        transcript = ""
        receivedOriginalText = ""
        LanguagePreferences.set(this, language)
        ITantraReceiverService.setLanguage(this, language)
        initializeRecognizer(language)
    }

    private fun initializeRecognizer(language: AppLanguage) {
        modelReady = false
        modelStatus = "Loading ${language.nativeName} Whisper STT…"

        sttWorker.execute {
            try {
                try {
                    recognizer?.release()
                } catch (_: Throwable) {
                }
                recognizer = null

                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = 80
                    ),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = "stt_whisper_small/small-encoder.int8.onnx",
                            decoder = "stt_whisper_small/small-decoder.int8.onnx",
                            language = language.whisperCode,
                            task = "transcribe"
                        ),
                        tokens = "stt_whisper_small/small-tokens.txt",
                        numThreads = Runtime.getRuntime()
                            .availableProcessors()
                            .coerceIn(2, 4),
                        debug = false,
                        provider = "cpu",
                        modelType = "whisper"
                    ),
                    decodingMethod = "greedy_search"
                )

                recognizer = OfflineRecognizer(
                    assetManager = assets,
                    config = config
                )

                runOnUiThread {
                    if (language == appLanguage) {
                        modelReady = true
                        modelStatus = "${language.nativeName} offline STT ready"
                    }
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (language != appLanguage) return@runOnUiThread
                    modelReady = false
                    modelStatus = "STT load error: ${error.message ?: "Unknown error"}"
                    Toast.makeText(
                        this,
                        modelStatus,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (!modelReady || processing || recordingFlag.get()) return

        receivedOriginalText = ""

        val minimumBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minimumBufferSize <= 0) {
            Toast.makeText(
                this,
                "Unable to create microphone buffer.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val shortBufferSize = maxOf(minimumBufferSize / 2, 2048)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            shortBufferSize * 2
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Toast.makeText(
                this,
                "Microphone initialization failed.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        capturedSamples = FloatArray(MAX_SAMPLES)
        capturedSampleCount = 0
        elapsedSeconds = 0
        recordingStartedAt = SystemClock.elapsedRealtime()
        modelStatus = "Listening..."

        audioRecord = recorder
        recordingFlag.set(true)
        recording = true

        try {
            recorder.startRecording()
        } catch (error: Throwable) {
            recordingFlag.set(false)
            recording = false
            recorder.release()
            audioRecord = null
            modelStatus = "Microphone start failed"
            Toast.makeText(
                this,
                error.message ?: modelStatus,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)

        recordingThread = Thread {
            val shortBuffer = ShortArray(shortBufferSize)

            while (
                recordingFlag.get() &&
                capturedSampleCount < MAX_SAMPLES
            ) {
                val readCount = recorder.read(
                    shortBuffer,
                    0,
                    shortBuffer.size
                )

                if (readCount <= 0) break

                val remaining = MAX_SAMPLES - capturedSampleCount
                val samplesToCopy = minOf(readCount, remaining)

                for (index in 0 until samplesToCopy) {
                    capturedSamples[capturedSampleCount + index] =
                        shortBuffer[index] / 32768.0f
                }

                capturedSampleCount += samplesToCopy
            }

            if (
                capturedSampleCount >= MAX_SAMPLES &&
                recordingFlag.get()
            ) {
                runOnUiThread {
                    stopRecordingAndTranscribe()
                }
            }
        }.also {
            it.name = "iTantra-AudioRecorder"
            it.start()
        }
    }

    private fun stopRecordingAndTranscribe() {
        if (!recordingFlag.compareAndSet(true, false)) return

        recording = false
        processing = true
        elapsedSeconds = (
                (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000L
                ).toInt().coerceIn(0, MAX_RECORD_SECONDS)
        modelStatus = "Transcribing offline..."

        timerHandler.removeCallbacks(timerRunnable)

        val recorder = audioRecord
        val activeRecordingThread = recordingThread

        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }

        sttWorker.execute {
            try {
                activeRecordingThread?.join(1_000L)

                try {
                    recorder?.release()
                } catch (_: Throwable) {
                }

                audioRecord = null
                recordingThread = null

                val samples = capturedSamples.copyOf(capturedSampleCount)

                if (samples.isEmpty()) {
                    throw IllegalStateException("No audio was recorded")
                }

                val activeRecognizer = recognizer
                    ?: throw IllegalStateException("STT model is not ready")

                val stream = activeRecognizer.createStream()

                try {
                    stream.acceptWaveform(
                        samples = samples,
                        sampleRate = SAMPLE_RATE
                    )

                    activeRecognizer.decode(stream)

                    val resultText = activeRecognizer
                        .getResult(stream)
                        .text
                        .trim()

                    runOnUiThread {
                        transcript = resultText
                        receivedOriginalText = ""
                        processing = false
                        modelStatus = if (resultText.isBlank()) {
                            "No speech recognized. Please try again."
                        } else {
                            "Message ready"
                        }

                        if (
                            resultText.isNotBlank() &&
                            appMode == AppMode.SENDER &&
                            autoSendEnabled
                        ) {
                            if (selectedReceiver == null) {
                                networkStatus =
                                    "Auto-send waiting: select a nearby receiver"
                            } else {
                                sendNetworkMessage(resultText)
                            }
                        }
                    }
                } finally {
                    stream.release()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    processing = false
                    modelStatus =
                        "Transcription error: ${error.message ?: "Unknown error"}"

                    Toast.makeText(
                        this,
                        modelStatus,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ITantraReceiverBridge.addListener(alwaysReadyListener)

        if (hasRequiredBluetoothPermissions()) {
            startAlwaysReadyReceiver()
        }
    }

    override fun onStop() {
        ITantraReceiverBridge.removeListener(alwaysReadyListener)
        super.onStop()
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        recordingFlag.set(false)

        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }

        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }

        bleTextTransceiver.release()

        sttWorker.execute {
            try {
                recognizer?.release()
            } catch (_: Throwable) {
            }

            recognizer = null
        }
        sttWorker.shutdown()

        super.onDestroy()
    }
}

@Suppress("unused")
@Composable
private fun ITantraScreen(
    modelReady: Boolean,
    modelStatus: String,
    recording: Boolean,
    processing: Boolean,
    transcript: String,
    elapsedSeconds: Int,
    maxRecordSeconds: Int,
    ttsReady: Boolean,
    ttsStatus: String,
    appMode: AppMode,
    networkStatus: String,
    autoSendEnabled: Boolean,
    nearbyReceivers: List<BleReceiver>,
    selectedReceiver: BleReceiver?,
    onTranscriptChange: (String) -> Unit,
    onAutoSendChange: (Boolean) -> Unit,
    onReceiverSelected: (BleReceiver) -> Unit,
    onModeChange: (AppMode) -> Unit,
    onRefreshNetwork: () -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onSpeak: () -> Unit,
    onSend: () -> Unit
) {
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFF5F7FF),
            Color(0xFFECEFFF),
            Color(0xFFF9FAFF)
        )
    )

    val mainStatus = when {
        recording -> "Recording your message"
        processing -> "Converting speech to text"
        appMode == AppMode.RECEIVER -> networkStatus
        else -> modelStatus
    }

    val statusColor = when {
        recording -> Color(0xFFE53935)
        processing -> Color(0xFFF59E0B)
        modelReady -> Color(0xFF16A36A)
        else -> Color(0xFF64748B)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "iTantra",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF20275C)
                    )

                    Text(
                        text = "Offline AI Bluetooth voice transceiver",
                        fontSize = 13.sp,
                        color = Color(0xFF667085)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFE2E7FF))
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = "EN",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4651C7)
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            ModeSelector(
                selectedMode = appMode,
                enabled = !recording && !processing,
                onModeChange = onModeChange
            )

            Spacer(modifier = Modifier.height(14.dp))

            NetworkPanel(
                appMode = appMode,
                networkStatus = networkStatus,
                autoSendEnabled = autoSendEnabled,
                nearbyReceivers = nearbyReceivers,
                selectedReceiver = selectedReceiver,
                enabled = !recording && !processing,
                onAutoSendChange = onAutoSendChange,
                onReceiverSelected = onReceiverSelected,
                onRefreshNetwork = onRefreshNetwork
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.94f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .background(statusColor, CircleShape)
                        )

                        Text(
                            text = mainStatus,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF273150)
                        )
                    }

                    Spacer(modifier = Modifier.height(7.dp))

                    Text(
                        text = when {
                            recording -> "Release the button when you finish speaking."
                            processing -> "Your speech is being processed completely offline."
                            appMode == AppMode.RECEIVER ->
                                "Keep this screen open while waiting for a message."
                            !modelReady -> "Please wait while the local model loads."
                            else -> "Hold the microphone button and speak clearly."
                        },
                        color = Color(0xFF7A8297),
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (appMode == AppMode.SENDER) {
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .background(
                            color = if (recording) {
                                Color(0xFFFFE7E7)
                            } else {
                                Color(0xFFE1E5FF)
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(154.dp)
                            .background(
                                brush = if (recording) {
                                    Brush.linearGradient(
                                        listOf(
                                            Color(0xFFFF5A63),
                                            Color(0xFFD92D3A)
                                        )
                                    )
                                } else {
                                    Brush.linearGradient(
                                        listOf(
                                            Color(0xFF7178FF),
                                            Color(0xFF4B45D6)
                                        )
                                    )
                                },
                                shape = CircleShape
                            )
                            .pointerInput(modelReady, processing) {
                                detectTapGestures(
                                    onPress = {
                                        if (modelReady && !processing) {
                                            onPressStart()

                                            try {
                                                tryAwaitRelease()
                                            } finally {
                                                onPressEnd()
                                            }
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (recording) "●" else "MIC",
                                color = Color.White,
                                fontSize = if (recording) 34.sp else 25.sp,
                                fontWeight = FontWeight.ExtraBold
                            )

                            Spacer(modifier = Modifier.height(5.dp))

                            Text(
                                text = if (recording) "RELEASE" else "HOLD TO TALK",
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = formatTime(elapsedSeconds),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF30385E)
                )

                Text(
                    text = "Maximum $maxRecordSeconds seconds",
                    fontSize = 12.sp,
                    color = Color(0xFF7A8297)
                )

                Spacer(modifier = Modifier.height(15.dp))

                Waveform(recording = recording)
            } else {
                ReceiverWaitingPanel(networkStatus = networkStatus)
            }

            Spacer(modifier = Modifier.height(26.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp)
                ) {
                    Text(
                        text = if (appMode == AppMode.SENDER) {
                            "Recognized message"
                        } else {
                            "Received message"
                        },
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF273150)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (appMode == AppMode.SENDER) {
                            "You can edit the text before speaking or sending it."
                        } else {
                            "Incoming text is announced automatically using offline TTS."
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF7A8297)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = transcript,
                        onValueChange = onTranscriptChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(132.dp),
                        enabled = !recording && !processing,
                        placeholder = {
                            Text("Your offline transcript will appear here...")
                        },
                        shape = RoundedCornerShape(16.dp),
                        minLines = 4,
                        maxLines = 6
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = onSpeak,
                        enabled = ttsReady &&
                                transcript.isNotBlank() &&
                                !recording &&
                                !processing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5559D9),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFD9DCEC),
                            disabledContentColor = Color(0xFF8A90A5)
                        )
                    ) {
                        Text(
                            text = when (ttsStatus) {
                                "Generating speech..." -> "Generating speech..."
                                "Speaking..." -> "Speaking..."
                                else -> "Speak Message"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Voice status: $ttsStatus",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = if (ttsStatus.startsWith("TTS error")) {
                            Color(0xFFD32F2F)
                        } else {
                            Color(0xFF747C91)
                        },
                        fontSize = 12.sp
                    )

                    if (appMode == AppMode.SENDER) {
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = onSend,
                            enabled = transcript.isNotBlank() &&
                                    selectedReceiver != null &&
                                    !recording &&
                                    !processing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = Color(0xFF5559D9)
                            )
                        ) {
                            Text(
                                text = if (autoSendEnabled) {
                                    "Send Again Manually"
                                } else {
                                    "Send Message"
                                },
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF4E53C7)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "STT and TTS run locally on this device",
                color = Color(0xFF7A8297),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ModeSelector(
    selectedMode: AppMode,
    enabled: Boolean,
    onModeChange: (AppMode) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.9f)
        )
    ) {
        Row(
            modifier = Modifier.padding(7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedMode == AppMode.SENDER) {
                Button(
                    onClick = { onModeChange(AppMode.SENDER) },
                    enabled = enabled,
                    modifier = Modifier.width(132.dp),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text("Sender", fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = { onModeChange(AppMode.SENDER) },
                    enabled = enabled,
                    modifier = Modifier.width(132.dp),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text("Sender")
                }
            }

            if (selectedMode == AppMode.RECEIVER) {
                Button(
                    onClick = { onModeChange(AppMode.RECEIVER) },
                    enabled = enabled,
                    modifier = Modifier.width(132.dp),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text("Receiver", fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = { onModeChange(AppMode.RECEIVER) },
                    enabled = enabled,
                    modifier = Modifier.width(132.dp),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text("Receiver")
                }
            }
        }
    }
}

@Composable
private fun NetworkPanel(
    appMode: AppMode,
    networkStatus: String,
    autoSendEnabled: Boolean,
    nearbyReceivers: List<BleReceiver>,
    selectedReceiver: BleReceiver?,
    enabled: Boolean,
    onAutoSendChange: (Boolean) -> Unit,
    onReceiverSelected: (BleReceiver) -> Unit,
    onRefreshNetwork: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF222A58)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(17.dp)) {
            Text(
                text = if (appMode == AppMode.SENDER) {
                    "Nearby iTantra receivers"
                } else {
                    "Automatic receiver"
                },
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (appMode == AppMode.SENDER) {
                Text(
                    text = "Direct BLE: no Wi-Fi, hotspot, Internet or pairing.",
                    color = Color(0xFFC9CEE8),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (nearbyReceivers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF303A70))
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Searching... Put the other phone in Receiver mode.",
                            color = Color(0xFFD8DCFF),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    nearbyReceivers.forEach { receiver ->
                        val isSelected = selectedReceiver?.id == receiver.id

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) {
                                        Color(0xFF3A477F)
                                    } else {
                                        Color(0xFF303A70)
                                    }
                                )
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = receiver.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )

                                Text(
                                    text = "${receiver.address}  •  ${receiver.rssi} dBm",
                                    color = Color(0xFFBFC6E8),
                                    fontSize = 11.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            if (isSelected) {
                                Button(
                                    onClick = { onReceiverSelected(receiver) },
                                    enabled = enabled,
                                    shape = RoundedCornerShape(11.dp)
                                ) {
                                    Text("Selected", fontSize = 11.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onReceiverSelected(receiver) },
                                    enabled = enabled,
                                    border = BorderStroke(1.dp, Color(0xFF9EA8FF)),
                                    shape = RoundedCornerShape(11.dp)
                                ) {
                                    Text(
                                        text = "Select",
                                        color = Color(0xFFD8DCFF),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                OutlinedButton(
                    onClick = onRefreshNetwork,
                    enabled = enabled,
                    border = BorderStroke(1.dp, Color(0xFF9EA8FF)),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text(
                        text = "Search Again",
                        color = Color(0xFFD8DCFF)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.width(220.dp)) {
                        Text(
                            text = "Auto-send after PTT",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )

                        Text(
                            text = "Release, transcribe and transmit automatically",
                            color = Color(0xFFC9CEE8),
                            fontSize = 10.sp
                        )
                    }

                    Switch(
                        checked = autoSendEnabled,
                        onCheckedChange = onAutoSendChange,
                        enabled = enabled
                    )
                }
            } else {
                Text(
                    text = "Advertises iTantra over BLE and automatically plays encrypted group messages.",
                    color = Color(0xFFC9CEE8),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Group: ITANTRA-01",
                    color = Color(0xFF9EE7C4),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "BLE GATT • AES-GCM • automatic acknowledgement",
                    color = Color(0xFFC9CEE8),
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onRefreshNetwork,
                    enabled = enabled,
                    border = BorderStroke(1.dp, Color(0xFF9EA8FF)),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text(
                        text = "Refresh Status",
                        color = Color(0xFFD8DCFF)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = networkStatus,
                color = Color(0xFFE4E7F5),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ReceiverWaitingPanel(networkStatus: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFE8ECFF)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .background(Color(0xFF5559D9), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "RX",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Waiting for a message",
                color = Color(0xFF29305B),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = networkStatus,
                color = Color(0xFF6D7694),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun Waveform(recording: Boolean) {
    val activeHeights = listOf(16, 30, 46, 26, 54, 36, 48, 24, 40, 18)
    val idleHeights = listOf(8, 12, 10, 14, 9, 13, 8, 11, 9, 12)
    val heights = if (recording) activeHeights else idleHeights

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        heights.forEach { barHeight ->
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (recording) {
                            Color(0xFFE94855)
                        } else {
                            Color(0xFFB8BEDA)
                        }
                    )
            )

            Spacer(modifier = Modifier.width(5.dp))
        }
    }
}

private fun formatTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    return String.format(
        Locale.US,
        "%02d:%02d",
        minutes,
        seconds
    )
}
