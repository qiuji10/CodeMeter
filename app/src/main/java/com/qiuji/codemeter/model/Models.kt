package com.qiuji.codemeter.model

enum class ProviderId(val displayName: String) {
    CLAUDE("Claude Code"),
    CODEX("Codex")
}

data class Profile(
    val id: String,
    val provider: ProviderId,
    val name: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

enum class UsageDisplayMode {
    USED,
    LEFT,
}

enum class ResetDisplayMode {
    REMAINING,
    EXACT,
}

data class AppSettings(
    val usageDisplayMode: UsageDisplayMode = UsageDisplayMode.USED,
    val nearExhaustedNotificationsEnabled: Boolean = true,
    val nearExhaustedThreshold: Int = 90,
    val resetNotificationsEnabled: Boolean = true,
    val compactView: Boolean = false,
    val resetDisplayMode: ResetDisplayMode = ResetDisplayMode.REMAINING,
    val showHistory: Boolean = true,
    val autoRefreshEnabled: Boolean = true,
    val backgroundFetchEnabled: Boolean = true,
    val autoRefreshIntervalMinutes: Int = 5,
)

data class UsageWindow(
    val key: String,
    val label: String,
    val usedPercent: Double,
    val resetsAtEpochMs: Long?,
    val windowSeconds: Long? = null,
)

data class ProviderUsage(
    val provider: ProviderId,
    val profileId: String = "",
    val profileName: String = provider.displayName,
    val plan: String? = null,
    val windows: List<UsageWindow>,
    val creditsText: String? = null,
    /** Last successful provider fetch. Stale/error annotations must not overwrite this timestamp. */
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val isStale: Boolean = false,
    val statusText: String? = null,
    val retryAtEpochMs: Long? = null,
)


/** True when the provider reports that the normal 5-hour/session window has started. */
fun ProviderUsage.hasActiveSessionWindow(): Boolean {
    val session = windows.firstOrNull { window ->
        window.label.equals("Session", ignoreCase = true) ||
            window.key.equals("session", ignoreCase = true) ||
            window.key.equals("five_hour", ignoreCase = true)
    } ?: return false
    return session.resetsAtEpochMs != null || session.usedPercent > 0.0
}

/**
 * Session priming is only offered from a successful, current provider snapshot. A missing Session row
 * is treated as "not started" because both Claude and Codex can omit an inactive 5-hour window.
 */
fun ProviderUsage.canStartSessionWindow(): Boolean = !isStale && !hasActiveSessionWindow()

data class SessionStartResult(
    val started: Boolean,
    val alreadyActive: Boolean,
    val usage: ProviderUsage?,
    val refreshConfirmed: Boolean,
)

data class StoredTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String? = null,
    val accountId: String? = null,
    val expiresAtEpochMs: Long? = null,
    val scope: String? = null,
)

data class UsageSnapshot(
    val profileId: String,
    val provider: ProviderId,
    val windowKey: String,
    val label: String,
    val usedPercent: Double,
    val resetsAtEpochMs: Long?,
    val capturedAtEpochMs: Long,
)
