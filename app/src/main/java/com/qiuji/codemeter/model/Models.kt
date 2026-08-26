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
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
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
