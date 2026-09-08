package com.example.itantra

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage

enum class AppLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val whisperCode: String,
    val mlKitCode: String
) {
    ENGLISH(
        code = "en",
        displayName = "English",
        nativeName = "English",
        whisperCode = "en",
        mlKitCode = TranslateLanguage.ENGLISH
    ),
    TAMIL(
        code = "ta",
        displayName = "Tamil",
        nativeName = "தமிழ்",
        whisperCode = "ta",
        mlKitCode = TranslateLanguage.TAMIL
    );

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull {
            it.code.equals(code, ignoreCase = true)
        } ?: ENGLISH
    }
}

object LanguagePreferences {
    private const val PREFERENCES_NAME = "itantra_language_preferences"
    private const val KEY_APP_LANGUAGE = "app_language"

    fun get(context: Context): AppLanguage {
        val code = context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).getString(KEY_APP_LANGUAGE, AppLanguage.ENGLISH.code)

        return AppLanguage.fromCode(code)
    }

    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().putString(KEY_APP_LANGUAGE, language.code).apply()
    }
}
