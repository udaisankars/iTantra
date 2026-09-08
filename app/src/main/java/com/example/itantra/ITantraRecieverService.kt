package com.example.itantra

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet

object ITantraProtocolConfig {
    const val GROUP_ID = "ITANTRA-01"
    const val SHARED_SECRET =
        "iTantra-demo-group-secret-change-before-release-2026"
}

object ITantraReceiverBridge {

    interface Listener {
        fun onReceiverStatusChanged(status: String)
        fun onTtsStatusChanged(ready: Boolean, status: String)
        fun onMessageReceived(message: LocalizedMessage)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile private var receiverStatus = "Starting always-ready receiver…"
    @Volatile private var ttsReady = false
    @Volatile private var ttsStatus = "Loading offline voice…"
    @Volatile private var latestMessage: LocalizedMessage? = null

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onReceiverStatusChanged(receiverStatus)
        listener.onTtsStatusChanged(ttsReady, ttsStatus)
        latestMessage?.let(listener::onMessageReceived)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    internal fun publishReceiverStatus(status: String) {
        receiverStatus = status
        listeners.forEach { it.onReceiverStatusChanged(status) }
    }

    internal fun publishTtsStatus(ready: Boolean, status: String) {
        ttsReady = ready
        ttsStatus = status
        listeners.forEach { it.onTtsStatusChanged(ready, status) }
    }

    internal fun publishMessage(message: LocalizedMessage) {
        latestMessage = message
        listeners.forEach { it.onMessageReceived(message) }
    }

    internal fun clearLatestMessage() {
        latestMessage = null
    }
}

class ITantraReceiverService : Service() {

    companion object {
        private const val ACTION_START =
            "com.example.itantra.action.START_ALWAYS_READY"
        private const val ACTION_SPEAK =
            "com.example.itantra.action.SPEAK"
        private const val ACTION_STOP_SPEAKING =
            "com.example.itantra.action.STOP_SPEAKING"
        private const val ACTION_SET_LANGUAGE =
            "com.example.itantra.action.SET_LANGUAGE"
        private const val EXTRA_TEXT = "message_text"
        private const val EXTRA_LANGUAGE = "app_language"

        private const val CHANNEL_ID = "itantra_always_ready"
        private const val CHANNEL_NAME = "iTantra receiver"
        private const val NOTIFICATION_ID = 50505
        private const val MAX_PENDING_MESSAGES = 10

        fun start(context: Context) {
            startServiceCommand(
                context,
                Intent(context, ITantraReceiverService::class.java)
                    .setAction(ACTION_START)
            )
        }

        fun setLanguage(context: Context, language: AppLanguage) {
            LanguagePreferences.set(context, language)
            startServiceCommand(
                context,
                Intent(context, ITantraReceiverService::class.java)
                    .setAction(ACTION_SET_LANGUAGE)
                    .putExtra(EXTRA_LANGUAGE, language.code)
            )
        }

        fun speak(context: Context, text: String) {
            val message = text.trim()
            if (message.isEmpty()) return

            startServiceCommand(
                context,
                Intent(context, ITantraReceiverService::class.java)
                    .setAction(ACTION_SPEAK)
                    .putExtra(EXTRA_TEXT, message)
            )
        }

        fun stopSpeaking(context: Context) {
            startServiceCommand(
                context,
                Intent(context, ITantraReceiverService::class.java)
                    .setAction(ACTION_STOP_SPEAKING)
            )
        }

        private fun startServiceCommand(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private data class PendingSpeech(
        val text: String,
        val language: AppLanguage
    )

    private lateinit var bleReceiver: BleTextTransceiver
    private lateinit var ttsEngine: OfflineTtsEngine
    private lateinit var translationEngine: HybridTranslationEngine

    private var selectedLanguage = AppLanguage.ENGLISH
    private var ttsReady = false
    private var receiverStarted = false
    private val pendingMessages = ArrayDeque<PendingSpeech>()

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

            when (
                intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
            ) {
                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    bleReceiver.stopReceiver()
                    receiverStarted = false
                    val status = "Turn on Bluetooth to receive messages"
                    ITantraReceiverBridge.publishReceiverStatus(status)
                    updateNotification(status)
                }

                BluetoothAdapter.STATE_ON -> startReceiverIfPermitted()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        selectedLanguage = LanguagePreferences.get(this)

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Ready in ${selectedLanguage.nativeName}")
        )

        bleReceiver = BleTextTransceiver(
            context = this,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        ttsEngine = OfflineTtsEngine(this)
        translationEngine = HybridTranslationEngine()
        registerBluetoothStateReceiver()

        loadLanguageEngines(selectedLanguage)
        startReceiverIfPermitted()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_SPEAK -> intent.getStringExtra(EXTRA_TEXT)?.let {
                speakOrQueue(it, selectedLanguage)
            }

            ACTION_STOP_SPEAKING -> {
                ttsEngine.stopPlayback()
                ITantraReceiverBridge.publishTtsStatus(
                    ready = ttsReady,
                    status = if (ttsReady) {
                        "${selectedLanguage.nativeName} offline voice ready"
                    } else {
                        "Loading offline voice…"
                    }
                )
            }

            ACTION_SET_LANGUAGE -> {
                val requested = AppLanguage.fromCode(
                    intent.getStringExtra(EXTRA_LANGUAGE)
                )
                if (requested != selectedLanguage) {
                    selectedLanguage = requested
                    pendingMessages.clear()
                    ITantraReceiverBridge.clearLatestMessage()
                    loadLanguageEngines(requested)
                }
            }

            else -> startReceiverIfPermitted()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: Throwable) {
        }
        if (::bleReceiver.isInitialized) bleReceiver.release()
        if (::ttsEngine.isInitialized) ttsEngine.release()
        if (::translationEngine.isInitialized) translationEngine.close()
        receiverStarted = false
        super.onDestroy()
    }

    private fun loadLanguageEngines(language: AppLanguage) {
        ttsReady = false
        ITantraReceiverBridge.publishTtsStatus(
            false,
            "Loading ${language.nativeName} offline voice…"
        )
        updateNotification("Preparing ${language.nativeName}")

        ttsEngine.initialize(language) { success, message ->
            // Ignore a stale callback if the user changed language again.
            if (language != selectedLanguage) return@initialize

            ttsReady = success
            ITantraReceiverBridge.publishTtsStatus(success, message)
            if (success) {
                drainPendingSpeech(language)
                updateNotification("Ready in ${language.nativeName}")
            } else {
                pendingMessages.clear()
                updateNotification("${language.displayName} voice unavailable")
            }
        }

        translationEngine.prepareForTarget(language) { success, status ->
            if (language != selectedLanguage) return@prepareForTarget
            if (!success) {
                // Same-language and verified emergency messages still work.
                ITantraReceiverBridge.publishReceiverStatus(status)
            }
        }
    }

    private fun startReceiverIfPermitted() {
        if (receiverStarted) return
        if (!hasBluetoothPermissions()) {
            val status = "Nearby devices permission is required"
            ITantraReceiverBridge.publishReceiverStatus(status)
            updateNotification(status)
            return
        }

        receiverStarted = true
        bleReceiver.startReceiver(
            groupId = ITantraProtocolConfig.GROUP_ID,
            sharedSecret = ITantraProtocolConfig.SHARED_SECRET,
            onStatusChange = { status ->
                if (status.isReceiverFailure()) receiverStarted = false
                ITantraReceiverBridge.publishReceiverStatus(status)
                updateNotification(status.toNotificationStatus())
            },
            onMessageReceived = ::processIncomingWireMessage
        )
    }

    private fun processIncomingWireMessage(wireMessage: String) {
        val message = try {
            ITantraMessage.decodeOrLegacy(wireMessage)
        } catch (error: Throwable) {
            val status = "Invalid multilingual message: ${error.message ?: "decode failed"}"
            ITantraReceiverBridge.publishReceiverStatus(status)
            updateNotification("Invalid message rejected")
            return
        }

        val targetAtStart = selectedLanguage
        translationEngine.translate(
            message = message,
            targetLanguage = targetAtStart,
            onStatusChange = { status ->
                if (targetAtStart == selectedLanguage) {
                    ITantraReceiverBridge.publishReceiverStatus(status)
                    updateNotification(status)
                }
            },
            onResult = { result ->
                if (targetAtStart != selectedLanguage) {
                    // Re-run with the newly selected receiver language.
                    processIncomingWireMessage(wireMessage)
                    return@translate
                }

                result.onSuccess { localized ->
                    ITantraReceiverBridge.publishMessage(localized)
                    val status = if (localized.verifiedPhrase) {
                        "Verified emergency message received"
                    } else if (localized.translated) {
                        "Translated to ${localized.targetLanguage.nativeName}"
                    } else {
                        "Message received in ${localized.targetLanguage.nativeName}"
                    }
                    ITantraReceiverBridge.publishReceiverStatus(status)
                    updateNotification("Message received · playing now")
                    speakOrQueue(localized.localizedText, localized.targetLanguage)
                }.onFailure { error ->
                    // Display the source for safety, but do not synthesize it
                    // with the wrong language voice.
                    ITantraReceiverBridge.publishMessage(
                        LocalizedMessage(
                            messageId = message.messageId,
                            senderName = message.senderName,
                            sourceLanguage = message.sourceLanguage,
                            targetLanguage = targetAtStart,
                            originalText = message.originalText,
                            localizedText = message.originalText,
                            verifiedPhrase = false,
                            translated = false
                        )
                    )
                    val status = "Translation failed — original shown, speech stopped: " +
                            (error.message ?: "model unavailable")
                    ITantraReceiverBridge.publishReceiverStatus(status)
                    updateNotification("Translation failed · original shown")
                }
            }
        )
    }

    private fun speakOrQueue(text: String, language: AppLanguage) {
        val message = text.trim()
        if (message.isEmpty()) return

        if (ttsReady && language == selectedLanguage) {
            speakNow(message, language)
        } else {
            if (pendingMessages.size >= MAX_PENDING_MESSAGES) {
                pendingMessages.removeFirst()
            }
            pendingMessages.addLast(PendingSpeech(message, language))
            ITantraReceiverBridge.publishTtsStatus(
                ready = false,
                status = "Message queued while ${language.nativeName} voice loads"
            )
        }
    }

    private fun drainPendingSpeech(language: AppLanguage) {
        val retained = ArrayDeque<PendingSpeech>()
        while (pendingMessages.isNotEmpty()) {
            val pending = pendingMessages.removeFirst()
            if (pending.language == language) {
                speakNow(pending.text, language)
            } else {
                retained.addLast(pending)
            }
        }
        pendingMessages.addAll(retained)
    }

    private fun speakNow(message: String, language: AppLanguage) {
        ttsEngine.speak(message, language) { status ->
            ITantraReceiverBridge.publishTtsStatus(ttsReady, status)
            updateNotification(
                if (status.contains("Speaking", ignoreCase = true)) {
                    "Speaking in ${language.nativeName}"
                } else {
                    "Ready in ${selectedLanguage.nativeName}"
                }
            )
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps iTantra available for translated Bluetooth messages"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun registerBluetoothStateReceiver() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                bluetoothStateReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(status: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("iTantra · ${selectedLanguage.nativeName}")
            .setContentText(status)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}

private fun String.isReceiverFailure(): Boolean =
    contains("failed", ignoreCase = true) ||
            contains("error", ignoreCase = true) ||
            contains("unable", ignoreCase = true) ||
            contains("not supported", ignoreCase = true) ||
            contains("turn on", ignoreCase = true) ||
            contains("cannot advertise", ignoreCase = true)

private fun String.toNotificationStatus(): String = when {
    contains("active", ignoreCase = true) -> "Ready for nearby messages"
    contains("starting", ignoreCase = true) -> "Starting Bluetooth receiver"
    else -> this
}
