package com.qiuji.codemeter.provider.claude

import android.net.Uri
import com.qiuji.codemeter.model.StoredTokens
import com.qiuji.codemeter.network.Http
import com.qiuji.codemeter.network.HttpStatusException
import com.qiuji.codemeter.security.SecureStore
import com.qiuji.codemeter.util.Pkce
import org.json.JSONObject

class ClaudeAuth(
    private val http: Http,
    private val secureStore: SecureStore,
) {
    companion object {
        const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        const val AUTH_URL = "https://claude.com/cai/oauth/authorize"
        const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
        const val REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
        const val SCOPE = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"
        private const val PENDING_VERIFIER = "claude_pending_verifier"
        private const val PENDING_STATE = "claude_pending_state"
        private const val PENDING_PROFILE_ID = "claude_pending_profile_id"
    }

    fun beginLogin(profileId: String): String {
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        secureStore.put(PENDING_VERIFIER, verifier)
        secureStore.put(PENDING_STATE, state)
        secureStore.put(PENDING_PROFILE_ID, profileId)

        return buildAuthorizeUrl(verifier, state)
    }

    fun pendingProfileId(): String? = secureStore.get(PENDING_PROFILE_ID)

    fun clearPending() {
        secureStore.put(PENDING_VERIFIER, null)
        secureStore.put(PENDING_STATE, null)
        secureStore.put(PENDING_PROFILE_ID, null)
    }

    suspend fun completeLogin(profileId: String, pastedCode: String): StoredTokens {
        val pendingProfileId = secureStore.get(PENDING_PROFILE_ID)
            ?: error("Claude login expired. Start Connect Claude again.")
        require(pendingProfileId == profileId) { "Claude login belongs to another profile. Start again." }
        val verifier = secureStore.get(PENDING_VERIFIER)
            ?: error("Claude login expired. Start Connect Claude again.")
        val expectedState = secureStore.get(PENDING_STATE)
            ?: error("Claude login state is missing. Start again.")

        val trimmed = pastedCode.trim()
        require(trimmed.isNotEmpty()) { "Paste the authorization code shown by Claude." }
        val code = trimmed.substringBefore('#').trim()
        val returnedState = trimmed.substringAfter('#', "").trim()
        if (returnedState.isNotEmpty() && returnedState != expectedState) {
            error("Claude login state did not match. Start the connection again.")
        }

        val fields = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to CLIENT_ID,
            "code_verifier" to verifier,
            "state" to expectedState,
        )

        val body = postTokenWithEncodingFallback(fields)
        val tokens = parseTokens(body, previous = null)
        secureStore.putTokens(profileId, tokens)
        clearPending()
        return tokens
    }

    suspend fun refresh(profileId: String, previous: StoredTokens): StoredTokens {
        val refresh = previous.refreshToken ?: error("Claude refresh token is unavailable. Reconnect Claude.")
        val fields = linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to CLIENT_ID,
            "scope" to (previous.scope ?: SCOPE),
        )
        val body = postTokenWithEncodingFallback(fields)
        val tokens = parseTokens(body, previous)
        secureStore.putTokens(profileId, tokens)
        return tokens
    }

    private fun buildAuthorizeUrl(verifier: String, state: String): String = Uri.parse(AUTH_URL).buildUpon()
        .appendQueryParameter("code", "true")
        .appendQueryParameter("client_id", CLIENT_ID)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("redirect_uri", REDIRECT_URI)
        .appendQueryParameter("scope", SCOPE)
        .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
        .appendQueryParameter("code_challenge_method", "S256")
        .appendQueryParameter("state", state)
        .build()
        .toString()

    private suspend fun postTokenWithEncodingFallback(fields: Map<String, String>): String {
        val json = JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } }
        return try {
            http.postJson(TOKEN_URL, json)
        } catch (first: HttpStatusException) {
            if (first.statusCode !in 400..499) throw first
            http.postForm(TOKEN_URL, fields)
        }
    }

    private fun parseTokens(raw: String, previous: StoredTokens?): StoredTokens {
        val json = JSONObject(raw)
        val accessToken = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: error("Claude token response did not contain access_token.")
        val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: previous?.refreshToken
        val expiresIn = json.optLong("expires_in", 0L).takeIf { it > 0 }
        return StoredTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = json.optString("id_token").takeIf { it.isNotBlank() } ?: previous?.idToken,
            accountId = previous?.accountId,
            expiresAtEpochMs = expiresIn?.let { System.currentTimeMillis() + it * 1000L - 60_000L },
            scope = json.optString("scope").takeIf { it.isNotBlank() } ?: previous?.scope ?: SCOPE,
        )
    }
}
