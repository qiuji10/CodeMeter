package com.qiuji.codemeter.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.qiuji.codemeter.model.ProviderId
import com.qiuji.codemeter.model.StoredTokens
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_credentials", Context.MODE_PRIVATE)
    private val alias = "ai_usage_aes_v1"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    fun put(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, packed, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, packed, cipher.iv.size, encrypted.size)
        prefs.edit().putString(key, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val packed = Base64.decode(raw, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    fun putTokens(profileId: String, tokens: StoredTokens) = put(tokenKey(profileId), tokens.toJson())

    fun getTokens(profileId: String): StoredTokens? = get(tokenKey(profileId))?.let(::parseTokens)

    fun clearTokens(profileId: String) = put(tokenKey(profileId), null)

    fun getLegacyTokens(provider: ProviderId): StoredTokens? =
        get("tokens_${provider.name.lowercase()}")?.let(::parseTokens)

    fun clearLegacyTokens(provider: ProviderId) = put("tokens_${provider.name.lowercase()}", null)

    fun clearPrefix(prefix: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    private fun tokenKey(profileId: String) = "profile_tokens_$profileId"

    private fun StoredTokens.toJson(): String = JSONObject()
        .put("accessToken", accessToken)
        .put("refreshToken", refreshToken)
        .put("idToken", idToken)
        .put("accountId", accountId)
        .put("expiresAtEpochMs", expiresAtEpochMs)
        .put("scope", scope)
        .toString()

    private fun parseTokens(raw: String): StoredTokens? = runCatching {
        val json = JSONObject(raw)
        StoredTokens(
            accessToken = json.getString("accessToken"),
            refreshToken = json.optString("refreshToken").takeIf { it.isNotBlank() && it != "null" },
            idToken = json.optString("idToken").takeIf { it.isNotBlank() && it != "null" },
            accountId = json.optString("accountId").takeIf { it.isNotBlank() && it != "null" },
            expiresAtEpochMs = if (json.has("expiresAtEpochMs") && !json.isNull("expiresAtEpochMs")) json.getLong("expiresAtEpochMs") else null,
            scope = json.optString("scope").takeIf { it.isNotBlank() && it != "null" },
        )
    }.getOrNull()
}
