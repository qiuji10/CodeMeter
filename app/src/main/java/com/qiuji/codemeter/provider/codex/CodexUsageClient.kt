package com.qiuji.codemeter.provider.codex

import com.qiuji.codemeter.model.ProviderId
import com.qiuji.codemeter.model.ProviderUsage
import com.qiuji.codemeter.model.UsageWindow
import com.qiuji.codemeter.network.Http
import com.qiuji.codemeter.network.HttpResponseData
import com.qiuji.codemeter.util.TimeFormat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class CodexUsageClient(private val http: Http) {
    companion object {
        const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        const val RESET_CREDITS_URL = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits"
        private const val SESSION_SECONDS = 5L * 60 * 60
        private const val WEEK_SECONDS = 7L * 24 * 60 * 60
        private const val CREDIT_USD_RATE = 0.04
    }

    suspend fun fetch(accessToken: String, accountId: String): ProviderUsage {
        val baseHeaders = mapOf(
            "Authorization" to "Bearer $accessToken",
            "ChatGPT-Account-Id" to accountId,
            "Accept" to "application/json",
            "User-Agent" to "codex-cli",
        )
        val usageResponse = http.getResponse(USAGE_URL, baseHeaders)

        // Current usage payloads normally already carry rate_limit_reset_credits.available_count.
        // Avoid a second private endpoint call unless that count is missing; the dedicated endpoint has
        // its own rate limiting and must never make the main quota fetch fail.
        val usageHasResetCount = runCatching {
            val body = JSONObject(usageResponse.body)
            val embedded = body.optJSONObject("rate_limit_reset_credits")
            embedded != null && optNumber(embedded, "available_count") != null
        }.getOrDefault(false)
        val resetCreditsResponse = if (usageHasResetCount) {
            null
        } else {
            runCatching {
                http.getResponse(
                    RESET_CREDITS_URL,
                    baseHeaders + mapOf(
                        "OpenAI-Beta" to "codex-1",
                        "originator" to "Codex Desktop",
                    ),
                )
            }.getOrNull()
        }

        return parse(usageResponse, resetCreditsResponse)
    }

    internal fun parse(raw: String): ProviderUsage = parse(HttpResponseData(200, emptyMap(), raw), null)

    internal fun parse(
        response: HttpResponseData,
        resetCreditsResponse: HttpResponseData? = null,
    ): ProviderUsage {
        val root = JSONObject(response.body)
        val rawPlan = root.optString("plan_type").takeIf { it.isNotBlank() }
        val rateLimit = root.optJSONObject("rate_limit") ?: root.optJSONObject("rate_limits") ?: JSONObject()

        val windows = mutableListOf<UsageWindow>()
        windows += parseMainWindows(rateLimit, response)
        parseAdditional(root.optJSONArray("additional_rate_limits"), windows, rawPlan)

        if (windows.isEmpty()) error("Codex usage response did not contain recognized quota windows.")

        val info = mutableListOf<String>()
        readResetCredits(root, resetCreditsResponse)?.takeIf { it > 0 }?.let { count ->
            info += "$count reset${if (count == 1) "" else "s"} available"
        }
        readCreditBalance(response, root)?.let { balance ->
            val credits = balance.coerceAtLeast(0.0).toLong()
            info += "\$${formatMoney(credits * CREDIT_USD_RATE)} · $credits credits"
        }

        return ProviderUsage(
            provider = ProviderId.CODEX,
            plan = formatCodexPlan(rawPlan),
            windows = windows.distinctBy { it.key },
            creditsText = info.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        )
    }

    private enum class WindowKind { SESSION, WEEKLY }

    private data class WindowCandidate(
        val key: String,
        val obj: JSONObject,
        val usedPercent: Double?,
        val fallbackKind: WindowKind,
    )

    private fun parseMainWindows(rateLimit: JSONObject, response: HttpResponseData): List<UsageWindow> {
        val candidates = mutableListOf<WindowCandidate>()

        fun addCandidate(keys: List<String>, fallback: WindowKind, headerPercent: Double?) {
            val key = keys.firstOrNull { rateLimit.optJSONObject(it) != null }
            val obj = if (key != null) rateLimit.optJSONObject(key) else null
            if (obj != null) {
                candidates += WindowCandidate(key ?: keys.first(), obj, usedPercent(obj) ?: headerPercent, fallback)
            } else if (headerPercent != null) {
                candidates += WindowCandidate(keys.first(), JSONObject(), headerPercent, fallback)
            }
        }

        addCandidate(
            listOf("primary_window", "primary", "five_hour"),
            WindowKind.SESSION,
            optHeaderNumber(response, "x-codex-primary-used-percent"),
        )
        addCandidate(
            listOf("secondary_window", "secondary", "weekly"),
            WindowKind.WEEKLY,
            optHeaderNumber(response, "x-codex-secondary-used-percent"),
        )

        // Legacy payloads can expose explicit five_hour / weekly fields alongside neither slot alias.
        if (candidates.none { it.key == "five_hour" }) {
            rateLimit.optJSONObject("five_hour")?.let {
                candidates += WindowCandidate("five_hour", it, usedPercent(it), WindowKind.SESSION)
            }
        }
        if (candidates.none { it.key == "weekly" }) {
            rateLimit.optJSONObject("weekly")?.let {
                candidates += WindowCandidate("weekly", it, usedPercent(it), WindowKind.WEEKLY)
            }
        }

        return listOfNotNull(
            classifiedWindow(WindowKind.SESSION, "Session", "session", candidates),
            classifiedWindow(WindowKind.WEEKLY, "Weekly", "weekly", candidates),
        )
    }

    private fun classifiedWindow(
        kind: WindowKind,
        label: String,
        stableKey: String,
        candidates: List<WindowCandidate>,
    ): UsageWindow? {
        val exact = candidates.firstOrNull { exactKind(it.obj) == kind }
        val fallback = candidates.firstOrNull { exactKind(it.obj) == null && it.fallbackKind == kind }
        val candidate = exact ?: fallback ?: return null
        val percent = candidate.usedPercent ?: return null
        val seconds = optLong(candidate.obj, "limit_window_seconds", "window_seconds")
            ?: if (kind == WindowKind.SESSION) SESSION_SECONDS else WEEK_SECONDS
        return UsageWindow(
            key = stableKey,
            label = label,
            usedPercent = normalizePercent(percent),
            resetsAtEpochMs = resetAt(candidate.obj),
            windowSeconds = seconds,
        )
    }

    private fun exactKind(obj: JSONObject): WindowKind? {
        return when (optLong(obj, "limit_window_seconds", "window_seconds")) {
            SESSION_SECONDS -> WindowKind.SESSION
            WEEK_SECONDS -> WindowKind.WEEKLY
            else -> null
        }
    }

    private fun parseAdditional(
        array: JSONArray?,
        out: MutableList<UsageWindow>,
        rawPlan: String?,
    ) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val limitName = item.optString("limit_name").takeIf { it.isNotBlank() }
            val meteredFeature = item.optString("metered_feature").takeIf { it.isNotBlank() }

            // The backend can expose Spark telemetry to Plus even though Plus is not entitled to use it.
            if (isPlusPlan(rawPlan) && isSparkLimit(limitName, meteredFeature)) continue

            val fullName = limitName
                ?: item.optString("display_name").takeIf { it.isNotBlank() }
                ?: item.optString("name").takeIf { it.isNotBlank() }
                ?: meteredFeature?.let(::humanizeFeatureName)
                ?: "Additional ${i + 1}"
            val displayName = if (isSparkLimit(limitName, meteredFeature)) "Spark" else fullName
            val slug = slug(fullName)
            val nested = item.optJSONObject("rate_limit") ?: item

            val candidates = mutableListOf<WindowCandidate>()
            nested.optJSONObject("primary_window")?.let {
                candidates += WindowCandidate("primary_window", it, usedPercent(it), WindowKind.SESSION)
            }
            nested.optJSONObject("secondary_window")?.let {
                candidates += WindowCandidate("secondary_window", it, usedPercent(it), WindowKind.WEEKLY)
            }
            nested.optJSONObject("primary")?.let {
                candidates += WindowCandidate("primary", it, usedPercent(it), WindowKind.SESSION)
            }
            nested.optJSONObject("secondary")?.let {
                candidates += WindowCandidate("secondary", it, usedPercent(it), WindowKind.WEEKLY)
            }
            if (candidates.isEmpty() && hasUsageFields(nested)) {
                candidates += WindowCandidate("direct", nested, usedPercent(nested), WindowKind.SESSION)
            }

            classifiedWindow(WindowKind.SESSION, displayName, "additional_${slug}_session", candidates)?.let(out::add)
            classifiedWindow(WindowKind.WEEKLY, "$displayName Weekly", "additional_${slug}_weekly", candidates)?.let(out::add)
        }
    }

    private fun usedPercent(obj: JSONObject): Double? {
        optNumber(obj, "used_percent", "usedPercent", "utilization", "used")?.let { return normalizePercent(it) }
        optNumber(obj, "percent_left")?.let { return normalizePercent(100.0 - it) }
        return null
    }

    private fun hasUsageFields(obj: JSONObject): Boolean =
        listOf("used_percent", "usedPercent", "utilization", "used", "percent_left").any { key -> obj.has(key) }

    private fun resetAt(obj: JSONObject): Long? {
        val directKeys = listOf("reset_at", "resets_at", "reset_time_ms", "resetsAt")
        for (key in directKeys) {
            if (obj.has(key) && !obj.isNull(key)) {
                val parsed = TimeFormat.parseEpochMillis(obj.opt(key))
                if (parsed != null) return parsed
            }
        }
        return optLong(obj, "reset_after_seconds")?.let { System.currentTimeMillis() + it * 1000L }
    }

    private fun readResetCredits(root: JSONObject, dedicated: HttpResponseData?): Int? {
        val dedicatedJson = dedicated?.body?.let { runCatching { JSONObject(it) }.getOrNull() }
        val source = dedicatedJson?.takeIf { optNumber(it, "available_count") != null }
            ?: root.optJSONObject("rate_limit_reset_credits")
        return source?.let { optNumber(it, "available_count")?.coerceAtLeast(0.0)?.toInt() }
    }

    private fun readCreditBalance(response: HttpResponseData, root: JSONObject): Double? {
        val credits = root.optJSONObject("credits")
        if (credits != null) {
            val balance = optNumber(credits, "balance")
            if (balance != null) return balance
            if (!credits.optBoolean("has_credits", true)) return null
        }
        return response.header("x-codex-credits-balance")?.toDoubleOrNull()
    }

    private fun optHeaderNumber(response: HttpResponseData, key: String): Double? =
        response.header(key)?.substringBefore(',')?.trim()?.toDoubleOrNull()

    private fun isPlusPlan(plan: String?): Boolean {
        val normalized = plan
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]+"), "_")
            ?.trim('_')
            ?: return false
        return normalized == "plus" || normalized == "chatgpt_plus"
    }

    private fun isSparkLimit(limitName: String?, meteredFeature: String?): Boolean {
        val name = limitName?.lowercase(Locale.US).orEmpty()
        val feature = meteredFeature?.lowercase(Locale.US).orEmpty()
        return "spark" in name || feature == "codex_bengalfox" || "bengalfox" in feature
    }

    private fun formatCodexPlan(value: String?): String? = when (value?.lowercase(Locale.US)) {
        null, "" -> null
        "prolite" -> "Pro 5x"
        "pro" -> "Pro 20x"
        "self_serve_business_prolite" -> "Business Premium"
        else -> humanizeFeatureName(value)
    }

    private fun humanizeFeatureName(value: String): String = value
        .replace(Regex("[_-]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase(Locale.US) } }

    private fun slug(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "limit" }

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

    private fun formatMoney(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale.US, value)
}
