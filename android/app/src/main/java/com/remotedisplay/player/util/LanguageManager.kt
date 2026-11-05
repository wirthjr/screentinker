package com.remotedisplay.player.util

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.remotedisplay.player.R

object LanguageManager {

    private const val PREFS_NAME = "remote_display_language"
    private const val KEY_LANGUAGE = "selected_language"

    data class LanguageItem(
        val code: String, // "" for system default, "pt-BR", "en", "es", "de", "fr", "hi"
        val displayNameRes: Int
    )

    val SUPPORTED_LANGUAGES = listOf(
        LanguageItem("", R.string.lang_system_default),
        LanguageItem("pt-BR", R.string.lang_pt_br),
        LanguageItem("en", R.string.lang_en),
        LanguageItem("es", R.string.lang_es),
        LanguageItem("de", R.string.lang_de),
        LanguageItem("fr", R.string.lang_fr),
        LanguageItem("hi", R.string.lang_hi)
    )

    fun getSelectedLanguageCode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, "") ?: ""
    }

    fun setLanguage(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()

        val localeList = if (languageCode.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageCode)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun applySavedLanguage(context: Context) {
        val savedCode = getSelectedLanguageCode(context)
        if (savedCode.isNotEmpty()) {
            val currentLocales = AppCompatDelegate.getApplicationLocales()
            if (currentLocales.toLanguageTags() != savedCode) {
                val localeList = LocaleListCompat.forLanguageTags(savedCode)
                AppCompatDelegate.setApplicationLocales(localeList)
            }
        }
    }

    fun getLanguageDisplayName(context: Context, languageCode: String): String {
        val item = SUPPORTED_LANGUAGES.firstOrNull { it.code.equals(languageCode, ignoreCase = true) }
        return if (item != null) context.getString(item.displayNameRes) else context.getString(R.string.lang_system_default)
    }

    fun showLanguageDialog(activity: Activity, onLanguageSelected: (() -> Unit)? = null) {
        val currentCode = getSelectedLanguageCode(activity)
        val names = SUPPORTED_LANGUAGES.map { activity.getString(it.displayNameRes) }.toTypedArray()
        var selectedIndex = SUPPORTED_LANGUAGES.indexOfFirst { it.code.equals(currentCode, ignoreCase = true) }
        if (selectedIndex < 0) selectedIndex = 0

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.settings_language_title))
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                val selectedItem = SUPPORTED_LANGUAGES[which]
                setLanguage(activity, selectedItem.code)
                dialog.dismiss()
                onLanguageSelected?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
