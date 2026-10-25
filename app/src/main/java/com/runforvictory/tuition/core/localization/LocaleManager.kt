package com.runforvictory.tuition.core.localization

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Supported language representation for Run for Victory localization.
 */
data class SupportedLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String
)

/**
 * Interface contract for managing application locale and persistent language preferences.
 */
interface LocaleManager {
    val currentLanguage: StateFlow<String>
    fun setLanguage(languageCode: String)
    fun getSupportedLanguages(): List<SupportedLanguage>
    fun getActiveLocale(): Locale
}

/**
 * Default implementation backed by SharedPreferences for persistent language preservation across app restarts.
 */
class DefaultLocaleManager(
    private val context: Context? = null,
    private val prefs: SharedPreferences? = null
) : LocaleManager {

    companion object {
        const val PREFS_NAME = "run_for_victory_locale_prefs"
        const val KEY_LANGUAGE = "selected_language"
        const val LANG_ENGLISH = "en"
        const val LANG_TELUGU = "te"
    }

    private val sharedPreferences: SharedPreferences? by lazy {
        prefs ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _currentLanguage: MutableStateFlow<String>

    init {
        val initialLanguage = sharedPreferences?.getString(KEY_LANGUAGE, LANG_ENGLISH) ?: LANG_ENGLISH
        val validatedLanguage = if (initialLanguage in listOf(LANG_ENGLISH, LANG_TELUGU)) initialLanguage else LANG_ENGLISH
        _currentLanguage = MutableStateFlow(validatedLanguage)
        applyLocaleToProcess(validatedLanguage)
    }

    override val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    override fun setLanguage(languageCode: String) {
        val code = if (languageCode == LANG_TELUGU) LANG_TELUGU else LANG_ENGLISH
        sharedPreferences?.edit()?.putString(KEY_LANGUAGE, code)?.apply()
        _currentLanguage.value = code
        applyLocaleToProcess(code)
    }

    override fun getSupportedLanguages(): List<SupportedLanguage> {
        return listOf(
            SupportedLanguage(code = LANG_ENGLISH, displayName = "English", nativeName = "English"),
            SupportedLanguage(code = LANG_TELUGU, displayName = "Telugu", nativeName = "తెలుగు")
        )
    }

    override fun getActiveLocale(): Locale {
        return Locale(_currentLanguage.value)
    }

    private fun applyLocaleToProcess(languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
    }
}
