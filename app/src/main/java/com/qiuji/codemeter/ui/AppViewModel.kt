package com.qiuji.codemeter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qiuji.codemeter.CodeMeterApplication
import com.qiuji.codemeter.model.AppSettings
import com.qiuji.codemeter.model.Profile
import com.qiuji.codemeter.model.ProviderId
import com.qiuji.codemeter.model.ProviderUsage
import com.qiuji.codemeter.model.ResetDisplayMode
import com.qiuji.codemeter.model.UsageDisplayMode
import com.qiuji.codemeter.model.UsageSnapshot
import com.qiuji.codemeter.network.HttpStatusException
import com.qiuji.codemeter.provider.codex.CodexAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    data class ClaudeLoginState(
        val profileId: String,
        val authorizeUrl: String,
        val code: String = "",
    )

    data class CodexLoginState(
        val profileId: String,
        val deviceCode: CodexAuth.DeviceCode,
    )

    data class UiState(
        val profiles: List<Profile> = emptyList(),
        val usage: Map<String, ProviderUsage> = emptyMap(),
        val connected: Set<String> = emptySet(),
        val refreshing: Set<String> = emptySet(),
        val history: Map<String, List<UsageSnapshot>> = emptyMap(),
        val settings: AppSettings = AppSettings(),
        val claudeLogin: ClaudeLoginState? = null,
        val codexLogin: CodexLoginState? = null,
        val message: String? = null,
    )

    private val app = application as CodeMeterApplication
    private val graph = app.graph
    private val repository = graph.repository
    private val profileStore = graph.profileStore
    private val settingsStore = graph.settingsStore
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            profileStore.profiles.collect { profiles ->
                repository.reloadCachedUsage(profiles)
                _state.update { current ->
                    current.copy(
                        profiles = profiles,
                        connected = profiles.filter { repository.isConnected(it.id) }.mapTo(linkedSetOf()) { it.id },
                        history = current.history.filterKeys { id -> profiles.any { it.id == id } },
                    )
                }
                loadHistory()
            }
        }
        viewModelScope.launch {
            repository.usage.collect { usage -> _state.update { it.copy(usage = usage) } }
        }
        viewModelScope.launch {
            settingsStore.settings.collect { settings -> _state.update { it.copy(settings = settings) } }
        }

        graph.codexAuth.pendingDeviceCode()?.let { pending ->
            if (profileStore.get(pending.profileId) != null) {
                _state.update { it.copy(codexLogin = CodexLoginState(pending.profileId, pending)) }
            } else {
                graph.codexAuth.clearPending()
            }
        }
    }

    fun addProfile(provider: ProviderId) {
        val profile = profileStore.add(provider)
        connect(profile.id)
    }

    fun connect(profileId: String) {
        val profile = profileStore.get(profileId) ?: return
        when (profile.provider) {
            ProviderId.CLAUDE -> beginClaudeLogin(profile)
            ProviderId.CODEX -> beginCodexLogin(profile)
        }
    }

    fun refreshAll() {
        _state.value.profiles.filter { repository.isConnected(it.id) }.forEach { refresh(it.id) }
    }

    fun refresh(profileId: String) {
        val profile = profileStore.get(profileId) ?: return
        if (!repository.isConnected(profileId) || profileId in _state.value.refreshing) return
        _state.update { it.copy(refreshing = it.refreshing + profileId) }
        viewModelScope.launch {
            try {
                repository.refresh(profile)
                loadHistory(profile)
            } catch (e: Throwable) {
                showError(profile, e)
            } finally {
                _state.update { it.copy(refreshing = it.refreshing - profileId) }
            }
        }
    }

    fun startSessionWindow(profileId: String) {
        val profile = profileStore.get(profileId) ?: return
        if (!repository.isConnected(profileId) || profileId in _state.value.refreshing) return
        _state.update { it.copy(refreshing = it.refreshing + profileId) }
        viewModelScope.launch {
            try {
                val result = repository.startSessionWindow(profile)
                if (result.alreadyActive) {
                    _state.update { it.copy(message = "${profile.name} session window is already active.") }
                } else if (result.started && result.refreshConfirmed) {
                    val session = result.usage?.windows?.firstOrNull { window ->
                        window.label.equals("Session", ignoreCase = true) ||
                            window.key.equals("session", ignoreCase = true) ||
                            window.key.equals("five_hour", ignoreCase = true)
                    }
                    val reset = session?.resetsAtEpochMs?.let { com.qiuji.codemeter.util.TimeFormat.remaining(it) }
                    _state.update {
                        it.copy(message = buildString {
                            append("${profile.name} session window started")
                            if (!reset.isNullOrBlank() && reset != "No reset time") append(" · $reset")
                            append(".")
                        })
                    }
                } else {
                    _state.update {
                        it.copy(message = "${profile.name} session-start request succeeded. Usage may take a moment to update.")
                    }
                }
                loadHistory(profile)
            } catch (e: Throwable) {
                showSessionStartError(profile, e)
            } finally {
                _state.update { it.copy(refreshing = it.refreshing - profileId) }
            }
        }
    }

    fun updateClaudeCode(value: String) {
        _state.update { current ->
            current.copy(claudeLogin = current.claudeLogin?.copy(code = value))
        }
    }

    fun cancelClaudeLogin() {
        graph.claudeAuth.clearPending()
        _state.update { it.copy(claudeLogin = null) }
    }

    fun finishClaudeLogin() {
        val login = _state.value.claudeLogin ?: return
        val profile = profileStore.get(login.profileId) ?: return
        if (login.code.isBlank()) {
            _state.update { it.copy(message = "Paste the Claude authorization code first.") }
            return
        }
        _state.update { it.copy(refreshing = it.refreshing + profile.id) }
        viewModelScope.launch {
            try {
                graph.claudeAuth.completeLogin(profile.id, login.code)
                _state.update { current ->
                    current.copy(
                        claudeLogin = null,
                        connected = current.connected + profile.id,
                        message = "${profile.name} connected.",
                    )
                }
                repository.refresh(profile)
                loadHistory(profile)
            } catch (e: Throwable) {
                showError(profile, e)
            } finally {
                _state.update { it.copy(refreshing = it.refreshing - profile.id) }
            }
        }
    }

    fun cancelCodexLogin() {
        graph.codexAuth.clearPending()
        _state.update { it.copy(codexLogin = null) }
    }

    fun finishCodexLogin() {
        val login = _state.value.codexLogin ?: return
        val profile = profileStore.get(login.profileId) ?: return
        if (profile.id in _state.value.refreshing) return
        _state.update { it.copy(refreshing = it.refreshing + profile.id) }
        viewModelScope.launch {
            try {
                repeat(18) { attempt ->
                    when (graph.codexAuth.pollOnce(profile.id)) {
                        CodexAuth.PollResult.Pending -> {
                            if (attempt == 17) error("Codex is still waiting for authorization. Complete the browser sign-in and try again.")
                            delay(login.deviceCode.intervalSeconds.coerceIn(1, 10) * 1000L)
                        }
                        is CodexAuth.PollResult.Complete -> {
                            _state.update { current ->
                                current.copy(
                                    codexLogin = null,
                                    connected = current.connected + profile.id,
                                    message = "${profile.name} connected.",
                                )
                            }
                            repository.refresh(profile)
                            loadHistory(profile)
                            return@launch
                        }
                    }
                }
            } catch (e: Throwable) {
                showError(profile, e)
            } finally {
                _state.update { it.copy(refreshing = it.refreshing - profile.id) }
            }
        }
    }

    fun disconnect(profileId: String) {
        val profile = profileStore.get(profileId) ?: return
        repository.disconnect(profileId)
        _state.update {
            it.copy(
                connected = it.connected - profileId,
                message = "${profile.name} disconnected. Stored OAuth tokens were removed.",
            )
        }
    }

    fun renameProfile(profileId: String, name: String) {
        val cleaned = name.trim()
        if (cleaned.isBlank()) return
        profileStore.rename(profileId, cleaned)
        _state.update { current ->
            current.copy(
                usage = current.usage.mapValues { (id, usage) -> if (id == profileId) usage.copy(profileName = cleaned) else usage },
            )
        }
    }

    fun reorderProfiles(profileIds: List<String>) {
        profileStore.reorder(profileIds)
    }

    fun removeProfile(profileId: String) {
        val profile = profileStore.get(profileId) ?: return
        if (_state.value.claudeLogin?.profileId == profileId) cancelClaudeLogin()
        if (_state.value.codexLogin?.profileId == profileId) cancelCodexLogin()
        repository.removeProfile(profileId)
        profileStore.remove(profileId)
        _state.update {
            it.copy(
                connected = it.connected - profileId,
                history = it.history - profileId,
                message = "${profile.name} removed.",
            )
        }
    }

    fun setUsageDisplayMode(value: UsageDisplayMode) = settingsStore.setUsageDisplayMode(value)
    fun setNearExhaustedNotificationsEnabled(value: Boolean) = settingsStore.setNearExhaustedNotificationsEnabled(value)
    fun setNearExhaustedThreshold(value: Int) = settingsStore.setNearExhaustedThreshold(value)
    fun setResetNotificationsEnabled(value: Boolean) = settingsStore.setResetNotificationsEnabled(value)
    fun setCompactView(value: Boolean) = settingsStore.setCompactView(value)
    fun setResetDisplayMode(value: ResetDisplayMode) = settingsStore.setResetDisplayMode(value)
    fun setShowHistory(value: Boolean) = settingsStore.setShowHistory(value)
    fun setAutoRefreshEnabled(value: Boolean) = settingsStore.setAutoRefreshEnabled(value)
    fun setBackgroundFetchEnabled(value: Boolean) = settingsStore.setBackgroundFetchEnabled(value)
    fun setAutoRefreshIntervalMinutes(value: Int) = settingsStore.setAutoRefreshIntervalMinutes(value)

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun beginClaudeLogin(profile: Profile) {
        runCatching { graph.claudeAuth.beginLogin(profile.id) }
            .onSuccess { url -> _state.update { it.copy(claudeLogin = ClaudeLoginState(profile.id, url)) } }
            .onFailure { error -> _state.update { it.copy(message = error.message ?: "Could not start Claude login.") } }
    }

    private fun beginCodexLogin(profile: Profile) {
        if (profile.id in _state.value.refreshing) return
        _state.update { it.copy(refreshing = it.refreshing + profile.id) }
        viewModelScope.launch {
            try {
                val device = graph.codexAuth.beginDeviceLogin(profile.id)
                _state.update { it.copy(codexLogin = CodexLoginState(profile.id, device)) }
            } catch (e: Throwable) {
                showError(profile, e)
            } finally {
                _state.update { it.copy(refreshing = it.refreshing - profile.id) }
            }
        }
    }

    private fun loadHistory(profile: Profile? = null) {
        viewModelScope.launch {
            val profiles = profile?.let(::listOf) ?: profileStore.profiles.value
            if (profiles.isEmpty()) return@launch
            val since = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            val updates = profiles.associate { it.id to repository.history(it, since) }
            _state.update { it.copy(history = it.history + updates) }
        }
    }

    private fun showSessionStartError(profile: Profile, error: Throwable) {
        val detail = when (error) {
            is HttpStatusException -> when (error.statusCode) {
                400 -> "${profile.name} rejected the minimal session-start request. The provider request format may have changed."
                401 -> "Authentication expired. Reconnect ${profile.name}."
                403 -> "${profile.name} does not allow session starting with this login."
                404 -> "${profile.name} session-start endpoint/model is currently unavailable."
                429 -> "${profile.name} rejected the session-start request because of a rate/entitlement limit."
                else -> "${profile.name} session start returned HTTP ${error.statusCode}."
            }
            else -> error.message ?: "Could not start ${profile.name}'s session window."
        }
        _state.update { it.copy(message = detail) }
    }

    private fun showError(profile: Profile, error: Throwable) {
        val detail = when (error) {
            is HttpStatusException -> when (error.statusCode) {
                401 -> "Authentication expired. Reconnect ${profile.name}."
                403 -> if (profile.provider == ProviderId.CLAUDE) {
                    "Claude usage access was denied (403). Reconnect ${profile.name} to refresh its OAuth scopes."
                } else {
                    "Codex rejected usage access (403). The private API may have changed."
                }
                429 -> "${profile.name} rate-limited the refresh. Try again later."
                else -> "${profile.name} returned HTTP ${error.statusCode}."
            }
            else -> error.message ?: "${profile.name} request failed."
        }
        _state.update { it.copy(message = detail) }
    }
}
