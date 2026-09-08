package com.example.itantra

import org.json.JSONObject
import java.util.UUID

data class ITantraMessage(
    val version: Int,
    val messageId: String,
    val sourceLanguage: AppLanguage,
    val originalText: String,
    val senderName: String,
    val sentAtEpochMs: Long
) {
    fun toWireText(): String {
        val cleanText = originalText.trim()
        require(cleanText.isNotEmpty()) { "Message is empty" }
        require(cleanText.length <= MAX_TEXT_CHARACTERS) { "Message is too long" }

        return JSONObject()
            .put("version", version)
            .put("messageId", messageId)
            .put("sourceLanguage", sourceLanguage.code)
            .put("originalText", cleanText)
            .put("senderName", senderName.take(MAX_SENDER_NAME_CHARACTERS))
            .put("sentAtEpochMs", sentAtEpochMs)
            .toString()
    }

    companion object {
        private const val CURRENT_VERSION = 2
        // Keeps UTF-8 Tamil plus JSON metadata below the BLE layer's 8 KiB cap.
        private const val MAX_TEXT_CHARACTERS = 1_500
        private const val MAX_SENDER_NAME_CHARACTERS = 60

        fun create(
            sourceLanguage: AppLanguage,
            originalText: String,
            senderName: String
        ): ITantraMessage = ITantraMessage(
            version = CURRENT_VERSION,
            messageId = UUID.randomUUID().toString(),
            sourceLanguage = sourceLanguage,
            originalText = originalText.trim(),
            senderName = senderName.trim(),
            sentAtEpochMs = System.currentTimeMillis()
        )

        /**
         * Plain-text packets from older English-only iTantra builds remain
         * readable during the migration to protocol version 2.
         */
        fun decodeOrLegacy(wireText: String): ITantraMessage {
            val cleanWireText = wireText.trim()
            require(cleanWireText.isNotEmpty()) { "Received message is empty" }

            return try {
                val json = JSONObject(cleanWireText)
                val text = json.getString("originalText").trim()
                require(text.isNotEmpty() && text.length <= MAX_TEXT_CHARACTERS)

                ITantraMessage(
                    version = json.optInt("version", CURRENT_VERSION),
                    messageId = json.optString(
                        "messageId",
                        UUID.randomUUID().toString()
                    ),
                    sourceLanguage = AppLanguage.fromCode(
                        json.optString("sourceLanguage", AppLanguage.ENGLISH.code)
                    ),
                    originalText = text,
                    senderName = json.optString("senderName", "Nearby iTantra"),
                    sentAtEpochMs = json.optLong("sentAtEpochMs", 0L)
                )
            } catch (error: Throwable) {
                if (cleanWireText.startsWith("{")) {
                    throw IllegalArgumentException(
                        "Malformed version-2 packet",
                        error
                    )
                }
                require(cleanWireText.length <= MAX_TEXT_CHARACTERS)
                ITantraMessage(
                    version = 1,
                    messageId = UUID.randomUUID().toString(),
                    sourceLanguage = AppLanguage.ENGLISH,
                    originalText = cleanWireText,
                    senderName = "Legacy iTantra",
                    sentAtEpochMs = 0L
                )
            }
        }
    }
}

data class LocalizedMessage(
    val messageId: String,
    val senderName: String,
    val sourceLanguage: AppLanguage,
    val targetLanguage: AppLanguage,
    val originalText: String,
    val localizedText: String,
    val verifiedPhrase: Boolean,
    val translated: Boolean
)
