package com.qiuji.codemeter.data

import android.content.Context
import com.qiuji.codemeter.model.AppSettings
import com.qiuji.codemeter.model.ResetDisplayMode
import com.qiuji.codemeter.model.UsageDisplayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setUsageDisplayMode(value: UsageDisplayMode) = update { it.copy(usageDisplayMode = value) }
    fun setNearExhaustedNotificationsEnabled(value: Boolean) = update { it.copy(nearExhaustedNotificationsEnabled = value) }
    fun setNearExhaustedThreshold(value: Int) = update { it.copy(nearExhaustedThreshold = value.coerceIn(50, 100)) }
    fun setResetNotificationsEnabled(value: Boolean) = update { it.copy(resetNotificationsEnabled = value) }
    fun setCompactView(value: Boolean) = update { it.copy(compactView = value) }
    fun setResetDisplayMode(value: ResetDisplayMode) = update { it.copy(resetDisplayMode = value) }
    fun setShowHistory(value: Boolean) = update { it.copy(showHistory = value) }
    fun setAutoRefreshEnabled(value: Boolean) = update { it.copy(autoRefreshEnabled = value) }
    fun setBackgroundFetchEnabled(value: Boolean) = update { it.copy(backgroundFetchEnabled = value) }
    fun setAutoRefreshIntervalMinutes(value: Int) = update {
        it.copy(autoRefreshIntervalMinutes = value.coerceIn(1, 120))
    }

    /** Provider retry cooldowns survive process restarts so a cold start cannot immediately re-hammer a 429. */
    fun providerCooldownUntil(profileId: String): Long = prefs.getLong("providerCooldown_$profileId", 0L)

    fun setProviderCooldownUntil(profileId: String, epochMs: Long) {
        if (epochMs <= 0L) prefs.edit().remove("providerCooldown_$profileId").apply()
        else prefs.edit().putLong("providerCooldown_$profileId", epochMs).apply()
    }

    fun clearProviderCooldown(profileId: String) = setProviderCooldownUntil(profileId, 0L)

    private fun update(transform: (AppSettings) -> AppSettings) {
        val value = transform(_settings.value)
        prefs.edit()
            .putString("usageDisplayMode", value.usageDisplayMode.name)
            .putBoolean("nearExhaustedNotificationsEnabled", value.nearExhaustedNotificationsEnabled)
            .putInt("nearExhaustedThreshold", value.nearExhaustedThreshold)
            .putBoolean("resetNotificationsEnabled", value.resetNotificationsEnabled)
            .putBoolean("compactView", value.compactView)
            .putString("resetDisplayMode", value.resetDisplayMode.name)
            .putBoolean("showHistory", value.showHistory)
            .putBoolean("autoRefreshEnabled", value.autoRefreshEnabled)
            .putBoolean("backgroundFetchEnabled", value.backgroundFetchEnabled)
            .putInt("autoRefreshIntervalMinutes", value.autoRefreshIntervalMinutes)
            .apply()
        _settings.value = value
    }

    private fun read(): AppSettings {
        val legacyNotificationsEnabled = prefs.getBoolean("notificationsEnabled", true)
        val migratedNearThreshold = if (prefs.contains("nearExhaustedThreshold")) {
            prefs.getInt("nearExhaustedThreshold", 90)
        } else if (prefs.contains("criticalThreshold")) {
            prefs.getInt("criticalThreshold", 95)
        } else {
            90
        }
        return AppSettings(
        usageDisplayMode = enumValueOrDefault(prefs.getString("usageDisplayMode", null), UsageDisplayMode.USED),
        nearExhaustedNotificationsEnabled = prefs.getBoolean("nearExhaustedNotificationsEnabled", legacyNotificationsEnabled),
        nearExhaustedThreshold = migratedNearThreshold.coerceIn(50, 100),
        resetNotificationsEnabled = prefs.getBoolean("resetNotificationsEnabled", legacyNotificationsEnabled),
        compactView = prefs.getBoolean("compactView", false),
        resetDisplayMode = enumValueOrDefault(prefs.getString("resetDisplayMode", null), ResetDisplayMode.REMAINING),
        showHistory = prefs.getBoolean("showHistory", true),
        autoRefreshEnabled = prefs.getBoolean("autoRefreshEnabled", true),
        backgroundFetchEnabled = prefs.getBoolean("backgroundFetchEnabled", true),
        autoRefreshIntervalMinutes = prefs.getInt("autoRefreshIntervalMinutes", 5).coerceIn(1, 120),
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, fallback: T): T =
        runCatching { raw?.let { enumValueOf<T>(it) } }.getOrNull() ?: fallback
}
