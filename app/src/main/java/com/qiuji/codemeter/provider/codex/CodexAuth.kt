package com.qiuji.codemeter.provider.codex

import com.qiuji.codemeter.model.StoredTokens
import com.qiuji.codemeter.network.Http
import com.qiuji.codemeter.network.HttpStatusException
import com.qiuji.codemeter.security.SecureStore
import com.qiuji.codemeter.util.Jwt
import org.json.JSONObject

class CodexAuth(
    private val http: Http,
    private val secureStore: SecureStore,
) {
    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val DEVICE_CODE_URL = "$ISSUER/api/accounts/deviceauth/usercode"
        const val DEVICE_TOKEN_URL = "$ISSUER/api/accounts/deviceauth/token"
        const val TOKEN_URL = "$ISSUER/oauth/token"
        const val VERIFICATION_URL = "$ISSUER/codex/device"
        const val REDIRECT_URI = "$ISSUER/deviceauth/callback"
        private const val PENDING_DEVICE_ID = "codex_pending_device_id"
        private const val PENDING_USER_CODE = "codex_pending_user_code"
        private const val PENDING_INTERVAL = "codex_pending_interval"
        private const val PENDING_PROFILE_ID = "codex_pending_profile_id"
    }

    data class DeviceCode(
        val profileId: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSeconds: Long,
    )

    sealed interface PollResult {
        data object Pending : PollResult
        data class Complete(val tokens: StoredTokens) : PollResult
    }

    suspend fun beginDeviceLogin(profileId: String): DeviceCode {
        val raw = http.postJson(DEVICE_CODE_URL, JSONObject().put("client_id", CLIENT_ID))
        val json = JSONObject(raw)
        val deviceId = json.optString("device_auth_id").takeIf { it.isNotBlank() }
            ?: error("Codex device login response did not contain device_auth_id.")
        val userCode = json.optString("user_code").takeIf { it.isNotBlank() }
            ?: error("Codex device login response did not contain user_code.")
        val interval = json.optLong("interval", 5L).coerceAtLeast(1L)
        secureStore.put(PENDING_DEVICE_ID, deviceId)
        secureStore.put(PENDING_USER_CODE, userCode)
        secureStore.put(PENDING_INTERVAL, interval.toString())
        secureStore.put(PENDING_PROFILE_ID, profileId)
        return DeviceCode(profileId, userCode, VERIFICATION_URL, interval)
    }

    fun pendingDeviceCode(): DeviceCode? {
        val userCode = secureStore.get(PENDING_USER_CODE) ?: return null
        val profileId = secureStore.get(PENDING_PROFILE_ID) ?: return null
        return DeviceCode(
            profileId = profileId,
            userCode = userCode,
            verificationUrl = VERIFICATION_URL,
            intervalSeconds = secureStore.get(PENDING_INTERVAL)?.toLongOrNull() ?: 5L,
        )
    }

    suspend fun pollOnce(profileId: String): PollResult {
        val pendingProfileId = secureStore.get(PENDING_PROFILE_ID)
            ?: error("No Codex device login is pending. Start Connect Codex again.")
        require(pendingProfileId == profileId) { "Codex login belongs to another profile. Start again." }
        val deviceId = secureStore.get(PENDING_DEVICE_ID)
            ?: error("No Codex device login is pending. Start Connect Codex again.")
        val userCode = secureStore.get(PENDING_USER_CODE)
            ?: error("Codex device user code is missing. Start again.")

        val raw = try {
            http.postJson(
                DEVICE_TOKEN_URL,
                JSONObject()
                    .put("device_auth_id", deviceId)
                    .put("user_code", userCode),
            )
        } catch (e: HttpStatusException) {
            if (e.statusCode == 403 || e.statusCode == 404) return PollResult.Pending
            throw e
        }

        val deviceToken = JSONObject(raw)
        val authorizationCode = deviceToken.optString("authorization_code").takeIf { it.isNotBlank() }
            ?: return PollResult.Pending
        val verifier = deviceToken.optString("code_verifier").takeIf { it.isNotBlank() }
            ?: error("Codex device token response did not contain code_verifier.")

        val tokenRaw = http.postForm(
            TOKEN_URL,
            linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to authorizationCode,
                "redirect_uri" to REDIRECT_URI,
                "client_id" to CLIENT_ID,
                "code_verifier" to verifier,
            ),
        )
        val tokens = parseTokens(tokenRaw, previous = null)
        secureStore.putTokens(profileId, tokens)
        clearPending()
        return PollResult.Complete(tokens)
    }

    suspend fun refresh(profileId: String, previous: StoredTokens): StoredTokens {
        val refreshToken = previous.refreshToken ?: error("Codex refresh token is unavailable. Reconnect Codex.")
        val fields = linkedMapOf(
            "client_id" to CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        )
        val json = JSONObject().apply { fields.forEach { (key, value) -> put(key, value) } }
        val raw = try {
            http.postJson(TOKEN_URL, json)
        } catch (first: HttpStatusException) {
            if (first.statusCode !in 400..499) throw first
            http.postForm(TOKEN_URL, fields)
        }
        val tokens = parseTokens(raw, previous)
        secureStore.putTokens(profileId, tokens)
        return tokens
    }

    fun clearPending() {
        secureStore.put(PENDING_DEVICE_ID, null)
        secureStore.put(PENDING_USER_CODE, null)
        secureStore.put(PENDING_INTERVAL, null)
        secureStore.put(PENDING_PROFILE_ID, null)
    }

    private fun parseTokens(raw: String, previous: StoredTokens?): StoredTokens {
        val json = JSONObject(raw)
        val accessToken = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: error("Codex token response did not contain access_token.")
        val idToken = json.optString("id_token").takeIf { it.isNotBlank() } ?: previous?.idToken
        val accountId = findAccountId(idToken)
            ?: findAccountId(accessToken)
            ?: previous?.accountId
            ?: error("Codex login succeeded but ChatGPT account id could not be found in the token.")
        val expiresIn = json.optLong("expires_in", 0L).takeIf { it > 0 }
        return StoredTokens(
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: previous?.refreshToken,
            idToken = idToken,
            accountId = accountId,
            expiresAtEpochMs = expiresIn?.let { System.currentTimeMillis() + it * 1000L - 60_000L },
            scope = json.optString("scope").takeIf { it.isNotBlank() } ?: previous?.scope,
        )
    }

    private fun findAccountId(token: String?): String? {
        val payload = Jwt.payload(token) ?: return null
        val direct = payload.optString("chatgpt_account_id").takeIf { it.isNotBlank() }
        if (direct != null) return direct
        val auth = payload.optJSONObject("https://api.openai.com/auth")
        return auth?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
            ?: auth?.optString("account_id")?.takeIf { it.isNotBlank() }
    }
}
