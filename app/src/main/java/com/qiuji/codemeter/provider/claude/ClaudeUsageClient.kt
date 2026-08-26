package com.qiuji.codemeter.provider.claude

import com.qiuji.codemeter.model.ProviderId
import com.qiuji.codemeter.model.ProviderUsage
import com.qiuji.codemeter.model.UsageWindow
import com.qiuji.codemeter.network.Http
import com.qiuji.codemeter.util.TimeFormat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ClaudeUsageClient(private val http: Http) {
    companion object {
        const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    }

    suspend fun fetch(accessToken: String): ProviderUsage {
        val raw = http.get(
            USAGE_URL,
            mapOf(
                "Authorization" to "Bearer $accessToken",
                "anthropic-beta" to "oauth-2025-04-20",
                "Accept" to "application/json",
            ),
        )
        return parse(raw)
    }

    internal fun parse(raw: String): ProviderUsage {
        val root = JSONObject(raw)
        val windows = mutableListOf<UsageWindow>()

        addWindow(root, "five_hour", "Session", windows)
        addWindow(root, "seven_day", "Weekly", windows)

        root.keys().forEach { key ->
            if (key.startsWith("seven_day_") && key != "seven_day") {
                val value = root.optJSONObject(key) ?: return@forEach
                if (!value.has("utilization")) return@forEach
                val suffix = key.removePrefix("seven_day_")
                val label = "Weekly ${humanize(suffix)}"
                addWindow(root, key, label, windows)
            }
        }

        parseDynamicLimits(root.optJSONArray("limits"), windows)

        if (windows.isEmpty()) {
            error("Claude usage response did not contain any recognized quota windows.")
        }

        val extra = root.optJSONObject("extra_usage")
        val credits = extra?.let {
            if (!it.optBoolean("is_enabled", false)) null
            else {
                val used = optNumber(it, "used_credits", "used")
                val limit = optNumber(it, "monthly_limit", "limit")
                when {
                    used != null && limit != null -> "Extra usage ${formatNumber(used)} / ${formatNumber(limit)}"
                    used != null -> "Extra usage ${formatNumber(used)}"
                    else -> "Extra usage enabled"
                }
            }
        }

        return ProviderUsage(
            provider = ProviderId.CLAUDE,
            plan = root.optString("plan_type").takeIf { it.isNotBlank() }
                ?: root.optString("plan").takeIf { it.isNotBlank() },
            windows = windows.distinctBy { it.key },
            creditsText = credits,
        )
    }

    private fun addWindow(root: JSONObject, key: String, label: String, out: MutableList<UsageWindow>) {
        val obj = root.optJSONObject(key) ?: return
        val rawPercent = optNumber(obj, "utilization", "used_percent") ?: return
        val percent = normalizePercent(rawPercent)
        val reset = TimeFormat.parseEpochMillis(
            when {
                obj.has("resets_at") -> obj.opt("resets_at")
                obj.has("reset_at") -> obj.opt("reset_at")
                else -> null
            },
        )
        out += UsageWindow(key, label, percent, reset)
    }

    private fun parseDynamicLimits(array: JSONArray?, out: MutableList<UsageWindow>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val utilization = optNumber(item, "utilization", "used_percent") ?: continue
            val name = item.optString("name").takeIf { it.isNotBlank() }
                ?: item.optString("display_name").takeIf { it.isNotBlank() }
                ?: item.optJSONObject("scope")?.optString("model")?.takeIf { it.isNotBlank() }
                ?: "Limit ${i + 1}"
            val key = item.optString("id").takeIf { it.isNotBlank() } ?: "limit_${name.lowercase().replace(' ', '_')}"
            val reset = TimeFormat.parseEpochMillis(item.opt("resets_at") ?: item.opt("reset_at"))
            out += UsageWindow(key, name, normalizePercent(utilization), reset)
        }
    }

    private fun normalizePercent(value: Double): Double = value.coerceIn(0.0, 100.0)

    private fun optNumber(json: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val value = json.opt(key)
            when (value) {
                is Number -> return value.toDouble()
                is String -> {
                    val parsed = value.toDoubleOrNull()
                    if (parsed != null) return parsed
                }
            }
        }
        return null
    }

    private fun humanize(value: String): String = value.split('_').joinToString(" ") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

    private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale.US, value)
}
