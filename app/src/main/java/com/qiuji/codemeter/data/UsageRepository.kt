package com.qiuji.codemeter.data

import com.qiuji.codemeter.db.UsageHistoryDb
import com.qiuji.codemeter.model.Profile
import com.qiuji.codemeter.model.ProviderId
import com.qiuji.codemeter.model.ProviderUsage
import com.qiuji.codemeter.model.StoredTokens
import com.qiuji.codemeter.model.UsageSnapshot
import com.qiuji.codemeter.network.HttpStatusException
import com.qiuji.codemeter.notification.UsageNotifier
import com.qiuji.codemeter.provider.claude.ClaudeAuth
import com.qiuji.codemeter.provider.claude.ClaudeUsageClient
import com.qiuji.codemeter.provider.codex.CodexAuth
import com.qiuji.codemeter.provider.codex.CodexUsageClient
import com.qiuji.codemeter.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class UsageRepository(
    private val secureStore: SecureStore,
    private val profileStore: ProfileStore,
    private val historyDb: UsageHistoryDb,
    private val claudeAuth: ClaudeAuth,
    private val claudeUsageClient: ClaudeUsageClient,
    private val codexAuth: CodexAuth,
    private val codexUsageClient: CodexUsageClient,
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
        notifier.clearProfile(profileId)
    }

    fun removeProfile(profileId: String) {
        secureStore.clearTokens(profileId)
        historyDb.deleteProfile(profileId)
        notifier.clearProfile(profileId)
        _usage.value = _usage.value - profileId
    }

    suspend fun refresh(profile: Profile): ProviderUsage = lockFor(profile.id).withLock {
        var tokens = secureStore.getTokens(profile.id) ?: error("${profile.name} is not connected.")
        if (tokens.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true) {
            tokens = refreshTokens(profile, tokens)
        }

        val fetched = try {
            fetch(profile, tokens)
        } catch (e: HttpStatusException) {
            if (e.statusCode != 401) throw e
            tokens = refreshTokens(profile, tokens)
            fetch(profile, tokens)
        }.copy(profileId = profile.id, profileName = profile.name)

        withContext(Dispatchers.IO) { historyDb.insert(fetched) }
        _usage.value = _usage.value + (profile.id to fetched)
        notifier.maybeNotify(fetched)
        fetched
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

    private suspend fun fetch(profile: Profile, tokens: StoredTokens): ProviderUsage = when (profile.provider) {
        ProviderId.CLAUDE -> claudeUsageClient.fetch(tokens.accessToken)
        ProviderId.CODEX -> codexUsageClient.fetch(
            accessToken = tokens.accessToken,
            accountId = tokens.accountId ?: error("Codex account id is missing. Reconnect ${profile.name}."),
        )
    }

    private suspend fun refreshTokens(profile: Profile, previous: StoredTokens): StoredTokens = when (profile.provider) {
        ProviderId.CLAUDE -> claudeAuth.refresh(profile.id, previous)
        ProviderId.CODEX -> codexAuth.refresh(profile.id, previous)
    }

    private fun lockFor(profileId: String): Mutex = locks.getOrPut(profileId) { Mutex() }
}
