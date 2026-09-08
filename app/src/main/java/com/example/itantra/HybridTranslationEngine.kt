package com.example.itantra

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * Exact, human-verified emergency translations are preferred. Free speech is
 * handled by ML Kit's on-device neural translator after its model is present.
 */
class HybridTranslationEngine {

    private val translators = mutableMapOf<String, Translator>()

    fun prepareForTarget(
        targetLanguage: AppLanguage,
        onComplete: (success: Boolean, status: String) -> Unit
    ) {
        val sourceLanguage = targetLanguage.otherLanguage()
        val translator = translatorFor(sourceLanguage, targetLanguage)

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                onComplete(
                    true,
                    "${sourceLanguage.displayName} → ${targetLanguage.displayName} translation ready"
                )
            }
            .addOnFailureListener { error ->
                onComplete(
                    false,
                    "Translation model unavailable: ${error.message ?: "download it once while online"}"
                )
            }
    }

    fun translate(
        message: ITantraMessage,
        targetLanguage: AppLanguage,
        onStatusChange: (String) -> Unit,
        onResult: (Result<LocalizedMessage>) -> Unit
    ) {
        if (message.sourceLanguage == targetLanguage) {
            onResult(
                Result.success(
                    message.localized(
                        targetLanguage = targetLanguage,
                        text = message.originalText,
                        verified = false,
                        translated = false
                    )
                )
            )
            return
        }

        VerifiedEmergencyPhrasebook.translate(
            text = message.originalText,
            sourceLanguage = message.sourceLanguage,
            targetLanguage = targetLanguage
        )?.let { verifiedText ->
            onStatusChange("Verified emergency translation")
            onResult(
                Result.success(
                    message.localized(
                        targetLanguage = targetLanguage,
                        text = verifiedText,
                        verified = true,
                        translated = true
                    )
                )
            )
            return
        }

        onStatusChange("Translating to ${targetLanguage.nativeName}…")
        val translator = translatorFor(message.sourceLanguage, targetLanguage)

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(message.originalText)
                    .addOnSuccessListener { translatedText ->
                        val cleanTranslation = translatedText.trim()
                        if (cleanTranslation.isEmpty()) {
                            onResult(
                                Result.failure(
                                    IllegalStateException("Translator returned empty text")
                                )
                            )
                        } else {
                            onResult(
                                Result.success(
                                    message.localized(
                                        targetLanguage = targetLanguage,
                                        text = cleanTranslation,
                                        verified = false,
                                        translated = true
                                    )
                                )
                            )
                        }
                    }
                    .addOnFailureListener { onResult(Result.failure(it)) }
            }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    fun close() {
        synchronized(translators) {
            translators.values.forEach(Translator::close)
            translators.clear()
        }
    }

    private fun translatorFor(
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage
    ): Translator {
        require(sourceLanguage != targetLanguage)
        val key = "${sourceLanguage.code}-${targetLanguage.code}"

        return synchronized(translators) {
            translators.getOrPut(key) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLanguage.mlKitCode)
                        .setTargetLanguage(targetLanguage.mlKitCode)
                        .build()
                )
            }
        }
    }
}

private fun AppLanguage.otherLanguage(): AppLanguage = when (this) {
    AppLanguage.ENGLISH -> AppLanguage.TAMIL
    AppLanguage.TAMIL -> AppLanguage.ENGLISH
}

private fun ITantraMessage.localized(
    targetLanguage: AppLanguage,
    text: String,
    verified: Boolean,
    translated: Boolean
): LocalizedMessage = LocalizedMessage(
    messageId = messageId,
    senderName = senderName,
    sourceLanguage = sourceLanguage,
    targetLanguage = targetLanguage,
    originalText = originalText,
    localizedText = text,
    verifiedPhrase = verified,
    translated = translated
)

private object VerifiedEmergencyPhrasebook {

    private data class Phrase(
        val english: String,
        val tamil: String
    )

    // These are intentionally exact phrases. Avoiding fuzzy matching prevents
    // a different safety instruction from being announced accidentally.
    private val phrases = listOf(
        Phrase("Help", "உதவி செய்யுங்கள்"),
        Phrase("Emergency", "அவசரநிலை"),
        Phrase("Fire", "தீ விபத்து"),
        Phrase("There is a fire", "தீ விபத்து ஏற்பட்டுள்ளது"),
        Phrase("Evacuate immediately", "உடனடியாக வெளியேறவும்"),
        Phrase("Move to a safe area", "பாதுகாப்பான பகுதிக்குச் செல்லவும்"),
        Phrase("Medical help is required", "மருத்துவ உதவி தேவை"),
        Phrase("Call the rescue team", "மீட்புக் குழுவை அழைக்கவும்"),
        Phrase("A worker is trapped", "ஒரு தொழிலாளர் சிக்கியுள்ளார்"),
        Phrase("Workers are trapped", "தொழிலாளர்கள் சிக்கியுள்ளனர்"),
        Phrase("The oxygen level is low", "ஆக்சிஜன் அளவு குறைவாக உள்ளது"),
        Phrase("Gas leak detected", "வாயுக் கசிவு கண்டறியப்பட்டுள்ளது"),
        Phrase("Do not enter", "உள்ளே செல்ல வேண்டாம்"),
        Phrase("Stop the machine", "இயந்திரத்தை நிறுத்தவும்"),
        Phrase("Power supply is off", "மின்சாரம் துண்டிக்கப்பட்டுள்ளது"),
        Phrase("The path is blocked", "பாதை அடைக்கப்பட்டுள்ளது"),
        Phrase("I am safe", "நான் பாதுகாப்பாக இருக்கிறேன்"),
        Phrase("We are safe", "நாங்கள் பாதுகாப்பாக இருக்கிறோம்"),
        Phrase("Send help immediately", "உடனடியாக உதவி அனுப்பவும்"),
        Phrase("Wait for instructions", "அறிவுறுத்தலுக்காக காத்திருக்கவும்")
    )

    fun translate(
        text: String,
        sourceLanguage: AppLanguage,
        targetLanguage: AppLanguage
    ): String? {
        if (sourceLanguage == targetLanguage) return text.trim()
        val normalizedInput = normalize(text)

        return phrases.firstNotNullOfOrNull { phrase ->
            when {
                sourceLanguage == AppLanguage.ENGLISH &&
                        targetLanguage == AppLanguage.TAMIL &&
                        normalize(phrase.english) == normalizedInput -> phrase.tamil

                sourceLanguage == AppLanguage.TAMIL &&
                        targetLanguage == AppLanguage.ENGLISH &&
                        normalize(phrase.tamil) == normalizedInput -> phrase.english

                else -> null
            }
        }
    }

    private fun normalize(text: String): String = text
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[.!?…]+$"), "")
        .replace(Regex("\\s+"), " ")
}
