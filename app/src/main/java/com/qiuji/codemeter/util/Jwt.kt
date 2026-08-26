package com.qiuji.codemeter.util

import android.util.Base64
import org.json.JSONObject

object Jwt {
    fun payload(token: String?): JSONObject? {
        if (token.isNullOrBlank()) return null
        return runCatching {
            val parts = token.split('.')
            if (parts.size < 2) return@runCatching null
            val decoded = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(decoded, Charsets.UTF_8))
        }.getOrNull()
    }

    fun stringClaim(token: String?, vararg names: String): String? {
        val payload = payload(token) ?: return null
        for (name in names) {
            val value = payload.optString(name, "").takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }
}
