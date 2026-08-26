package com.qiuji.codemeter.data

import android.content.Context
import com.qiuji.codemeter.model.Profile
import com.qiuji.codemeter.model.ProviderId
import com.qiuji.codemeter.security.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ProfileStore(
    context: Context,
    private val secureStore: SecureStore,
) {
    companion object {
        const val LEGACY_CLAUDE_PROFILE_ID = "legacy_claude"
        const val LEGACY_CODEX_PROFILE_ID = "legacy_codex"
        private const val KEY_PROFILES = "profiles"
    }

    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val _profiles = MutableStateFlow(readProfiles())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    init {
        migrateLegacyAccount(ProviderId.CLAUDE, LEGACY_CLAUDE_PROFILE_ID)
        migrateLegacyAccount(ProviderId.CODEX, LEGACY_CODEX_PROFILE_ID)
    }

    fun get(profileId: String): Profile? = _profiles.value.firstOrNull { it.id == profileId }

    fun add(provider: ProviderId): Profile {
        val sameProviderCount = _profiles.value.count { it.provider == provider }
        val name = if (sameProviderCount == 0) provider.displayName else "${provider.displayName} ${sameProviderCount + 1}"
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            provider = provider,
            name = name,
        )
        persist(_profiles.value + profile)
        return profile
    }

    fun rename(profileId: String, name: String) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return
        persist(_profiles.value.map { if (it.id == profileId) it.copy(name = cleaned) else it })
    }

    fun reorder(profileIds: List<String>) {
        val current = _profiles.value
        if (current.size < 2) return

        val byId = current.associateBy { it.id }
        val requested = profileIds.mapNotNull(byId::get).distinctBy { it.id }
        val requestedIds = requested.mapTo(mutableSetOf()) { it.id }
        val remainder = current.filterNot { it.id in requestedIds }
        val reordered = requested + remainder

        if (reordered.map { it.id } != current.map { it.id }) {
            persist(reordered)
        }
    }

    fun remove(profileId: String) {
        secureStore.clearTokens(profileId)
        persist(_profiles.value.filterNot { it.id == profileId })
    }

    private fun migrateLegacyAccount(provider: ProviderId, profileId: String) {
        val tokens = secureStore.getLegacyTokens(provider) ?: return
        if (_profiles.value.none { it.id == profileId }) {
            persist(
                _profiles.value + Profile(
                    id = profileId,
                    provider = provider,
                    name = provider.displayName,
                    createdAtEpochMs = 0L,
                ),
            )
        }
        secureStore.putTokens(profileId, tokens)
        secureStore.clearLegacyTokens(provider)
    }

    private fun readProfiles(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val provider = ProviderId.valueOf(item.getString("provider"))
                    add(
                        Profile(
                            id = item.getString("id"),
                            provider = provider,
                            name = item.optString("name").takeIf { it.isNotBlank() } ?: provider.displayName,
                            createdAtEpochMs = item.optLong("createdAtEpochMs", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(value: List<Profile>) {
        val array = JSONArray()
        value.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("provider", profile.provider.name)
                    .put("name", profile.name)
                    .put("createdAtEpochMs", profile.createdAtEpochMs),
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
        _profiles.value = value
    }
}
