package com.qiuji.codemeter.data

import com.qiuji.codemeter.db.UsageHistoryDb
import com.qiuji.codemeter.model.Profile
import com.qiuji.codemeter.model.ProviderId
import com.qiuji.codemeter.model.ProviderUsage
import com.qiuji.codemeter.model.SessionStartResult
import com.qiuji.codemeter.model.StoredTokens
import com.qiuji.codemeter.model.UsageSnapshot
import com.qiuji.codemeter.model.hasActiveSessionWindow
import com.qiuji.codemeter.network.HttpStatusException
import com.qiuji.codemeter.notification.UsageNotifier
import com.qiuji.codemeter.provider.claude.ClaudeAuth
import com.qiuji.codemeter.provider.claude.ClaudeSessionClient
import com.qiuji.codemeter.provider.claude.ClaudeUsageClient
import com.qiuji.codemeter.provider.codex.CodexAuth
import com.qiuji.codemeter.provider.codex.CodexSessionClient
import com.qiuji.codemeter.provider.codex.CodexUsageClient
import com.qiuji.codemeter.security.SecureStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class UsageRepository(
    private val secureStore: SecureStore,
    private val profileStore: ProfileStore,
    private val settingsStore: SettingsStore,
    private val historyDb: UsageHistoryDb,
    private val claudeAuth: ClaudeAuth,
    private val claudeUsageClient: ClaudeUsageClient,
    private val claudeSessionClient: ClaudeSessionClient,
    private val codexAuth: CodexAuth,
    private val codexUsageClient: CodexUsageClient,
    private val codexSessionClient: CodexSessionClient,
    private val notifier: UsageNotifier,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _usage = MutableStateFlow<Map<String, ProviderUsage>>(emptyMap())
    val usage: StateFlow<Map<String, ProviderUsage>> = _usage.asStateFlow()

    init {
        reloadCachedUsage(profileStore.profiles.value)
    }

    fun reloadCachedUsage(profiles: List<Profile>) {
        val current = _usage.value
        _usage.value = profiles.mapNotNull { profile ->
            val usage = current[profile.id]?.copy(profileName = profile.name) ?: historyDb.latest(profile)
            usage?.let { profile.id to it }
        }.toMap()
    }

    fun isConnected(profileId: String): Boolean = secureStore.getTokens(profileId) != null

    fun disconnect(profileId: String) {
        secureStore.clearTokens(profileId)
        settingsStore.clearProviderCooldown(profileId)
        notifier.clearProfile(profileId)
    }

    fun removeProfile(profileId: String) {
        secureStore.clearTokens(profileId)
        settingsStore.clearProviderCooldown(profileId)
        historyDb.deleteProfile(profileId)
        notifier.clearProfile(profileId)
        _usage.value = _usage.value - profileId
    }

    suspend fun refresh(profile: Profile): ProviderUsage = lockFor(profile.id).withLock {
        val now = System.currentTimeMillis()
        val cooldownUntil = settingsStore.providerCooldownUntil(profile.id)
        if (cooldownUntil > now) {
            return@withLock staleUsage(profile, "Rate limited", cooldownUntil)
        }
        if (cooldownUntil != 0L) settingsStore.clearProviderCooldown(profile.id)

        var tokens = secureStore.getTokens(profile.id) ?: error("${profile.name} is not connected.")

        val fetched = try {
            if (tokens.expiresAtEpochMs?.let { it <= now } == true || needsClaudeUsageScope(profile, tokens)) {
                tokens = refreshTokens(profile, tokens)
            }
            fetchWithAuthRetry(profile, tokens)
        } catch (e: HttpStatusException) {
            return@withLock handleUsageHttpFailure(profile, e, now)
        } catch (_: IOException) {
            return@withLock staleUsage(profile, "Network unavailable", null)
        }

        settingsStore.clearProviderCooldown(profile.id)
        persistFresh(profile, fetched)
    }

    /**
     * Starts a real provider session window with one tiny inference request. This is always an explicit
     * user action; background/automatic refresh code never calls it.
     */
    suspend fun startSessionWindow(profile: Profile): SessionStartResult = lockFor(profile.id).withLock {
        val now = System.currentTimeMillis()
        var tokens = secureStore.getTokens(profile.id) ?: error("${profile.name} is not connected.")
        if (tokens.expiresAtEpochMs?.let { it <= now } == true || needsInferenceScope(profile, tokens)) {
            tokens = refreshTokens(profile, tokens)
        }

        // A recent successful dashboard refresh is enough to avoid an extra /usage poll. Otherwise do
        // one preflight fetch so we never consume quota just to restart an already active 5-hour window.
        val cached = _usage.value[profile.id]
        val preflight = if (
            cached != null &&
            !cached.isStale &&
            cached.updatedAtEpochMs > 0L &&
            now - cached.updatedAtEpochMs <= SESSION_START_CACHE_MAX_AGE_MS
        ) {
            cached
        } else {
            val cooldownUntil = settingsStore.providerCooldownUntil(profile.id)
            if (cooldownUntil > now) {
                error("Usage status is currently rate limited. Wait for the cooldown before starting a session window.")
            }
            val fresh = try {
                fetchWithAuthRetry(profile, tokens)
            } catch (e: HttpStatusException) {
                if (e.statusCode == 429) {
                    val retryAt = e.retryAfterEpochMs(now) ?: (now + DEFAULT_RATE_LIMIT_COOLDOWN_MS)
                    settingsStore.setProviderCooldownUntil(profile.id, retryAt)
                    staleUsage(profile, "Rate limited", retryAt)
                    error("Usage status is rate limited. Wait for the cooldown before starting a session window.")
                }
                throw e
            }
            persistFresh(profile, fresh)
        }

        if (preflight.hasActiveSessionWindow()) {
            return@withLock SessionStartResult(
                started = false,
                alreadyActive = true,
                usage = preflight,
                refreshConfirmed = true,
            )
        }

        // The preflight can refresh/rotate OAuth credentials, so re-read the canonical encrypted record.
        tokens = secureStore.getTokens(profile.id) ?: tokens
        triggerSessionWithAuthRetry(profile, tokens)

        // Give the quota backend a short propagation window, then perform only one confirmation fetch to
        // avoid turning this convenience action into an aggressive usage poller.
        delay(SESSION_START_CONFIRM_DELAY_MS)
        val confirmed = try {
            val latestTokens = secureStore.getTokens(profile.id) ?: tokens
            val fetched = fetchWithAuthRetry(profile, latestTokens)
            settingsStore.clearProviderCooldown(profile.id)
            persistFresh(profile, fetched)
        } catch (e: HttpStatusException) {
            if (e.statusCode == 429) {
                val retryAt = e.retryAfterEpochMs() ?: (System.currentTimeMillis() + DEFAULT_RATE_LIMIT_COOLDOWN_MS)
                settingsStore.setProviderCooldownUntil(profile.id, retryAt)
                staleUsage(profile, "Rate limited", retryAt)
            }
            null
        } catch (_: IOException) {
            null
        }

        SessionStartResult(
            started = true,
            alreadyActive = false,
            usage = confirmed ?: preflight,
            refreshConfirmed = confirmed?.hasActiveSessionWindow() == true,
        )
    }

    suspend fun refreshAll(): Map<String, Result<ProviderUsage>> {
        val results = linkedMapOf<String, Result<ProviderUsage>>()
        for (profile in profileStore.profiles.value) {
            if (isConnected(profile.id)) results[profile.id] = runCatching { refresh(profile) }
        }
        return results
    }

    suspend fun history(profile: Profile, sinceEpochMs: Long): List<UsageSnapshot> =
        withContext(Dispatchers.IO) { historyDb.history(profile, sinceEpochMs) }

    private suspend fun fetchWithAuthRetry(profile: Profile, initialTokens: StoredTokens): ProviderUsage {
        var tokens = initialTokens
        return try {
            fetch(profile, tokens)
        } catch (e: HttpStatusException) {
            if (e.statusCode != 401) throw e
            tokens = refreshTokens(profile, tokens)
            fetch(profile, tokens)
        }
    }

    private suspend fun triggerSessionWithAuthRetry(profile: Profile, initialTokens: StoredTokens) {
        var tokens = initialTokens
        try {
            triggerSession(profile, tokens)
        } catch (e: HttpStatusException) {
            if (e.statusCode != 401) throw e
            tokens = refreshTokens(profile, tokens)
            triggerSession(profile, tokens)
        }
    }

    private suspend fun fetch(profile: Profile, tokens: StoredTokens): ProviderUsage = when (profile.provider) {
        ProviderId.CLAUDE -> claudeUsageClient.fetch(tokens.accessToken)
        ProviderId.CODEX -> codexUsageClient.fetch(
            accessToken = tokens.accessToken,
            accountId = tokens.accountId ?: error("Codex account id is missing. Reconnect ${profile.name}."),
        )
    }

    private suspend fun triggerSession(profile: Profile, tokens: StoredTokens) = when (profile.provider) {
        ProviderId.CLAUDE -> claudeSessionClient.start(tokens.accessToken)
        ProviderId.CODEX -> codexSessionClient.start(
            accessToken = tokens.accessToken,
            accountId = tokens.accountId ?: error("Codex account id is missing. Reconnect ${profile.name}."),
        )
    }

    private suspend fun refreshTokens(profile: Profile, previous: StoredTokens): StoredTokens = when (profile.provider) {
        ProviderId.CLAUDE -> claudeAuth.refresh(profile.id, previous)
        ProviderId.CODEX -> codexAuth.refresh(profile.id, previous)
    }

    private fun needsClaudeUsageScope(profile: Profile, tokens: StoredTokens): Boolean {
        if (profile.provider != ProviderId.CLAUDE) return false
        val scope = tokens.scope ?: return false
        return scope.split(Regex("\\s+")).none { it == "user:profile" }
    }

    private fun needsInferenceScope(profile: Profile, tokens: StoredTokens): Boolean {
        if (profile.provider != ProviderId.CLAUDE) return false
        val scope = tokens.scope ?: return false
        return scope.split(Regex("\\s+")).none { it == "user:inference" }
    }

    private suspend fun persistFresh(profile: Profile, usage: ProviderUsage): ProviderUsage {
        val fresh = usage.copy(
            profileId = profile.id,
            profileName = profile.name,
            isStale = false,
            statusText = null,
            retryAtEpochMs = null,
        )
        withContext(Dispatchers.IO) { historyDb.insert(fresh) }
        _usage.value = _usage.value + (profile.id to fresh)
        notifier.maybeNotify(fresh)
        return fresh
    }

    private fun handleUsageHttpFailure(profile: Profile, error: HttpStatusException, now: Long): ProviderUsage = when {
        error.statusCode == 429 -> {
            val retryAt = error.retryAfterEpochMs(now) ?: (now + DEFAULT_RATE_LIMIT_COOLDOWN_MS)
            settingsStore.setProviderCooldownUntil(profile.id, retryAt)
            staleUsage(profile, "Rate limited", retryAt)
        }
        error.statusCode == 401 || error.statusCode == 400 ->
            staleUsage(profile, "Authentication expired · reconnect", null)
        error.statusCode == 403 ->
            staleUsage(profile, "Usage access denied · reconnect", null)
        error.statusCode >= 500 -> staleUsage(profile, "Provider unavailable", null)
        else -> throw error
    }

    /**
     * Keep the last successful bars visible during transient provider/network failures.
     * Stale snapshots are never inserted into history and never trigger quota notifications.
     */
    private fun staleUsage(profile: Profile, status: String, retryAtEpochMs: Long?): ProviderUsage {
        val cached = _usage.value[profile.id] ?: historyDb.latest(profile)
        val stale = (cached ?: ProviderUsage(
            provider = profile.provider,
            profileId = profile.id,
            profileName = profile.name,
            windows = emptyList(),
            updatedAtEpochMs = 0L,
        )).copy(
            profileId = profile.id,
            profileName = profile.name,
            isStale = true,
            statusText = status,
            retryAtEpochMs = retryAtEpochMs,
        )
        _usage.value = _usage.value + (profile.id to stale)
        return stale
    }

    private fun lockFor(profileId: String): Mutex = locks.getOrPut(profileId) { Mutex() }

    private companion object {
        const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 5L * 60 * 1000
        const val SESSION_START_CACHE_MAX_AGE_MS = 2L * 60 * 1000
        const val SESSION_START_CONFIRM_DELAY_MS = 1_500L
    }
}
