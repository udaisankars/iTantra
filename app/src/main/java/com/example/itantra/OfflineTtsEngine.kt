package com.example.itantra

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * English uses the existing Piper voice. Tamil uses Meta MMS Tamil VITS
 * converted with sherpa-onnx's official MMS exporter. Only the selected voice
 * stays in memory, which is important on low-power Android phones.
 */
class OfflineTtsEngine(context: Context) {

    companion object {
        private const val ENGLISH_MODEL = "tts_en/model.onnx"
        private const val ENGLISH_TOKENS = "tts_en/tokens.txt"
        private const val ENGLISH_ESPEAK_ASSET_DIR = "tts_en/espeak-ng-data"
        private const val TAMIL_MODEL = "tts_ta/model.onnx"
        private const val TAMIL_TOKENS = "tts_ta/tokens.txt"
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var activeLanguage: AppLanguage? = null
    @Volatile private var currentAudioTrack: AudioTrack? = null

    fun initialize(
        language: AppLanguage,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        executor.execute {
            try {
                if (tts == null || activeLanguage != language) {
                    stopPlayback()
                    releaseModelOnWorker()
                    validateModelAssets(language)

                    val vitsConfig = when (language) {
                        AppLanguage.ENGLISH -> {
                            val espeakDataDir = copyEspeakDataToPrivateStorage()
                            OfflineTtsVitsModelConfig(
                                model = ENGLISH_MODEL,
                                tokens = ENGLISH_TOKENS,
                                dataDir = espeakDataDir.absolutePath,
                                noiseScale = 0.667f,
                                noiseScaleW = 0.8f,
                                lengthScale = 1.0f
                            )
                        }

                        AppLanguage.TAMIL -> OfflineTtsVitsModelConfig(
                            model = TAMIL_MODEL,
                            tokens = TAMIL_TOKENS,
                            // MMS has a character frontend; no espeak directory.
                            dataDir = "",
                            noiseScale = 0.667f,
                            noiseScaleW = 0.8f,
                            lengthScale = 1.0f
                        )
                    }

                    tts = OfflineTts(
                        assetManager = appContext.assets,
                        config = OfflineTtsConfig(
                            model = OfflineTtsModelConfig(
                                vits = vitsConfig,
                                numThreads = Runtime.getRuntime()
                                    .availableProcessors()
                                    .coerceIn(2, 4),
                                debug = false,
                                provider = "cpu"
                            )
                        )
                    )
                    activeLanguage = language
                }

                postComplete(
                    onComplete,
                    true,
                    "${language.nativeName} offline voice ready"
                )
            } catch (error: Throwable) {
                releaseModelOnWorker()
                postComplete(
                    onComplete,
                    false,
                    "${language.displayName} TTS load error: " +
                            (error.message ?: "Unknown error")
                )
            }
        }
    }

    fun speak(
        text: String,
        language: AppLanguage,
        onStatusChange: (String) -> Unit
    ) {
        val message = text.trim()
        if (message.isEmpty()) {
            onStatusChange("No text available to speak")
            return
        }

        executor.execute {
            try {
                val activeTts = tts
                    ?: throw IllegalStateException("TTS model is not ready")
                check(activeLanguage == language) {
                    "${language.displayName} voice is not ready"
                }

                postStatus(
                    onStatusChange,
                    "Generating ${language.nativeName} speech…"
                )
                val generatedAudio = activeTts.generate(
                    text = message,
                    sid = 0,
                    speed = 1.0f
                )

                if (generatedAudio.samples.isEmpty()) {
                    throw IllegalStateException("TTS generated empty audio")
                }

                playAudio(
                    samples = generatedAudio.samples,
                    sampleRate = generatedAudio.sampleRate,
                    onStatusChange = onStatusChange
                )
            } catch (error: Throwable) {
                postStatus(
                    onStatusChange,
                    "TTS error: ${error.message ?: "Unknown error"}"
                )
            }
        }
    }

    private fun validateModelAssets(language: AppLanguage) {
        val requiredAssets = when (language) {
            AppLanguage.ENGLISH -> listOf(ENGLISH_MODEL, ENGLISH_TOKENS)
            AppLanguage.TAMIL -> listOf(TAMIL_MODEL, TAMIL_TOKENS)
        }

        requiredAssets.forEach { assetPath ->
            appContext.assets.open(assetPath).use { input ->
                check(input.read() >= 0) { "Empty model asset: $assetPath" }
            }
        }
    }

    private fun copyEspeakDataToPrivateStorage(): File {
        val storageRoot = appContext.getExternalFilesDir(null)
            ?: appContext.filesDir
        val destination = File(storageRoot, ENGLISH_ESPEAK_ASSET_DIR)

        copyAssetTree(ENGLISH_ESPEAK_ASSET_DIR, destination)
        check(destination.exists() && destination.isDirectory) {
            "Failed to prepare espeak-ng-data"
        }
        return destination
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = appContext.assets.list(assetPath)
            ?: throw IllegalStateException("Unable to list asset: $assetPath")

        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { inputStream ->
                FileOutputStream(destination, false).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } else {
            if (!destination.exists() && !destination.mkdirs()) {
                throw IllegalStateException(
                    "Unable to create directory: ${destination.absolutePath}"
                )
            }
            children.forEach { childName ->
                copyAssetTree(
                    "$assetPath/$childName",
                    File(destination, childName)
                )
            }
        }
    }

    private fun playAudio(
        samples: FloatArray,
        sampleRate: Int,
        onStatusChange: (String) -> Unit
    ) {
        stopPlayback()

        val minimumBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val requiredBufferSize = samples.size * Float.SIZE_BYTES
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(
                requiredBufferSize.coerceAtLeast(minimumBufferSize)
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        currentAudioTrack = audioTrack
        audioTrack.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) {
                    if (currentAudioTrack === track) currentAudioTrack = null
                    try {
                        track.stop()
                    } catch (_: Throwable) {
                    }
                    track.release()
                    postStatus(onStatusChange, "Ready")
                }

                override fun onPeriodicNotification(track: AudioTrack) = Unit
            },
            mainHandler
        )

        val writtenSamples = audioTrack.write(
            samples,
            0,
            samples.size,
            AudioTrack.WRITE_BLOCKING
        )
        if (writtenSamples <= 0) {
            audioTrack.release()
            currentAudioTrack = null
            throw IllegalStateException("AudioTrack write failed: $writtenSamples")
        }

        audioTrack.notificationMarkerPosition = writtenSamples
        audioTrack.play()
        postStatus(onStatusChange, "Speaking…")
    }

    fun stopPlayback() {
        val track = currentAudioTrack
        currentAudioTrack = null
        if (track != null) {
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (_: Throwable) {
            }
            try {
                track.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun release() {
        stopPlayback()
        executor.execute { releaseModelOnWorker() }
        executor.shutdown()
    }

    private fun releaseModelOnWorker() {
        try {
            tts?.release()
        } catch (_: Throwable) {
        }
        tts = null
        activeLanguage = null
    }

    private fun postComplete(
        callback: (Boolean, String) -> Unit,
        success: Boolean,
        status: String
    ) {
        mainHandler.post { callback(success, status) }
    }

    private fun postStatus(callback: (String) -> Unit, status: String) {
        mainHandler.post { callback(status) }
    }
}
