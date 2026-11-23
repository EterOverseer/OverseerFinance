package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.local.DateDisplayFormat
import com.example.data.local.ThemeMode
import com.example.domain.ImageHashing
import com.example.domain.ThaiDateFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "overseer_prefs")

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dateFormat: DateDisplayFormat = DateDisplayFormat.THAI_BE,
    val primaryLanguage: String = "th",
    val selectedTaxYearBe: Int = ThaiDateFormatter.currentYearBe(),
    val autoScanEnabled: Boolean = true,
    val scanIntervalMinutes: Int = 15,
    val notificationsEnabled: Boolean = true,
    val pHashSimilarityThreshold: Int = ImageHashing.DEFAULT_PHASH_SIMILARITY_THRESHOLD
)

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DATE_FORMAT = stringPreferencesKey("date_format")
        val LANGUAGE = stringPreferencesKey("primary_language")
        val TAX_YEAR_BE = intPreferencesKey("tax_year_be")
        val AUTO_SCAN_ENABLED = booleanPreferencesKey("auto_scan_enabled")
        val SCAN_INTERVAL_MIN = intPreferencesKey("scan_interval_min")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val PHASH_THRESHOLD = intPreferencesKey("phash_threshold")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.DARK,
            dateFormat = prefs[Keys.DATE_FORMAT]?.let { runCatching { DateDisplayFormat.valueOf(it) }.getOrNull() }
                ?: DateDisplayFormat.THAI_BE,
            primaryLanguage = prefs[Keys.LANGUAGE] ?: "th",
            selectedTaxYearBe = prefs[Keys.TAX_YEAR_BE] ?: ThaiDateFormatter.currentYearBe(),
            autoScanEnabled = prefs[Keys.AUTO_SCAN_ENABLED] ?: true,
            scanIntervalMinutes = (prefs[Keys.SCAN_INTERVAL_MIN] ?: 15).coerceAtLeast(15),
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            pHashSimilarityThreshold = (prefs[Keys.PHASH_THRESHOLD] ?: ImageHashing.DEFAULT_PHASH_SIMILARITY_THRESHOLD).coerceIn(2, 16)
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDateFormat(format: DateDisplayFormat) {
        context.dataStore.edit { it[Keys.DATE_FORMAT] = format.name }
    }

    suspend fun setLanguage(lang: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = lang }
    }

    suspend fun setTaxYearBe(yearBe: Int) {
        context.dataStore.edit { it[Keys.TAX_YEAR_BE] = yearBe }
    }

    suspend fun setAutoScanEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SCAN_ENABLED] = enabled }
    }

    suspend fun setScanIntervalMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SCAN_INTERVAL_MIN] = minutes.coerceAtLeast(15) }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setPHashThreshold(threshold: Int) {
        context.dataStore.edit { it[Keys.PHASH_THRESHOLD] = threshold.coerceIn(2, 16) }
    }
}
