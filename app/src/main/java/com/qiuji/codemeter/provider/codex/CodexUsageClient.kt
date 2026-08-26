package com.qiuji.codemeter.provider.codex

import com.qiuji.codemeter.model.ProviderId
import com.qiuji.codemeter.model.ProviderUsage
import com.qiuji.codemeter.model.UsageWindow
import com.qiuji.codemeter.network.Http
import com.qiuji.codemeter.util.TimeFormat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class CodexUsageClient(private val http: Http) {
    companion object {
        const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
    }

    suspend fun fetch(accessToken: String, accountId: String): ProviderUsage {
        val raw = http.get(
            USAGE_URL,
            mapOf(
                "Authorization" to "Bearer $accessToken",
                "ChatGPT-Account-Id" to accountId,
                "Accept" to "application/json",
            ),
        )
        return parse(raw)
    }

    internal fun parse(raw: String): ProviderUsage {
        val root = JSONObject(raw)
        val rateLimit = root.optJSONObject("rate_limit") ?: root.optJSONObject("rate_limits")
            ?: error("Codex usage response did not contain rate_limit.")

        val windows = mutableListOf<UsageWindow>()
        parseWindow(rateLimit, listOf("primary_window", "primary", "five_hour"), "primary", windows)
        parseWindow(rateLimit, listOf("secondary_window", "secondary", "weekly"), "secondary", windows)
        parseAdditional(root.optJSONArray("additional_rate_limits"), windows)

        if (windows.isEmpty()) error("Codex usage response did not contain recognized quota windows.")

        val ordered = windows.sortedWith(compareBy<UsageWindow> { it.windowSeconds ?: Long.MAX_VALUE }.thenBy { it.label })
        val relabeled = ordered.mapIndexed { index, window ->
            if (window.key.startsWith("additional_")) window
            else when {
                window.windowSeconds != null && window.windowSeconds <= 86_400 -> window.copy(label = "Session")
                window.windowSeconds != null && window.windowSeconds >= 2 * 86_400 -> window.copy(label = "Weekly")
                index == 0 -> window.copy(label = "Session")
                else -> window.copy(label = "Weekly")
            }
        }

        val credits = root.optJSONObject("credits")?.let { credit ->
            val balance = optNumber(credit, "balance", "credits_balance", "remaining")
            val hasCredits = credit.optBoolean("has_credits", balance != null)
            when {
                balance != null -> "Credits ${formatNumber(balance)}"
                hasCredits -> "Credits available"
                else -> null
            }
        }

        return ProviderUsage(
            provider = ProviderId.CODEX,
            plan = root.optString("plan_type").takeIf { it.isNotBlank() },
            windows = relabeled.distinctBy { it.key },
            creditsText = credits,
        )
    }

    private fun parseWindow(
        parent: JSONObject,
        candidates: List<String>,
        fallbackKey: String,
        out: MutableList<UsageWindow>,
    ) {
        val key = candidates.firstOrNull { parent.optJSONObject(it) != null } ?: return
        val obj = parent.optJSONObject(key) ?: return
        val percent = optNumber(obj, "used_percent", "utilization", "used") ?: return
        val windowSeconds = optLong(obj, "limit_window_seconds", "window_seconds")
        val reset = TimeFormat.parseEpochMillis(
            when {
                obj.has("reset_at") -> obj.opt("reset_at")
                obj.has("resets_at") -> obj.opt("resets_at")
                else -> null
            },
        ) ?: optLong(obj, "reset_after_seconds")?.let { System.currentTimeMillis() + it * 1000L }
        out += UsageWindow(
            key = if (key.isBlank()) fallbackKey else key,
            label = fallbackKey,
            usedPercent = normalizePercent(percent),
            resetsAtEpochMs = reset,
            windowSeconds = windowSeconds,
        )
    }

    private fun parseAdditional(array: JSONArray?, out: MutableList<UsageWindow>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name").takeIf { it.isNotBlank() }
                ?: item.optString("display_name").takeIf { it.isNotBlank() }
                ?: "Additional ${i + 1}"
            val nested = item.optJSONObject("rate_limit") ?: item
            val primary = nested.optJSONObject("primary_window") ?: nested.optJSONObject("primary") ?: nested
            val percent = optNumber(primary, "used_percent", "utilization", "used") ?: continue
            val windowSeconds = optLong(primary, "limit_window_seconds", "window_seconds")
            val reset = TimeFormat.parseEpochMillis(primary.opt("reset_at") ?: primary.opt("resets_at"))
            out += UsageWindow(
                key = "additional_${name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')}",
                label = name,
                usedPercent = normalizePercent(percent),
                resetsAtEpochMs = reset,
                windowSeconds = windowSeconds,
            )
        }
    }

    private fun optNumber(json: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            when (val value = json.opt(key)) {
                is Number -> return value.toDouble()
                is String -> {
                    val parsed = value.toDoubleOrNull()
                    if (parsed != null) return parsed
                }
            }
        }
        return null
    }

    private fun optLong(json: JSONObject, vararg keys: String): Long? = optNumber(json, *keys)?.toLong()

    private fun normalizePercent(value: Double): Double = value.coerceIn(0.0, 100.0)

    private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale.US, value)
}
