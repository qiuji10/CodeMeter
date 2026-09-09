package com.qiuji.codemeter.provider.claude

import com.qiuji.codemeter.model.ProviderId
import com.qiuji.codemeter.model.ProviderUsage
import com.qiuji.codemeter.model.UsageWindow
import com.qiuji.codemeter.network.Http
import com.qiuji.codemeter.network.HttpResponseData
import com.qiuji.codemeter.util.TimeFormat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ClaudeUsageClient(private val http: Http) {
    companion object {
        const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
        private const val SESSION_SECONDS = 5L * 60 * 60
        private const val WEEK_SECONDS = 7L * 24 * 60 * 60
    }

    suspend fun fetch(accessToken: String): ProviderUsage {
        val response = http.getResponse(
            USAGE_URL,
            mapOf(
                "Authorization" to "Bearer ${accessToken.trim()}",
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "anthropic-beta" to "oauth-2025-04-20",
                // Match the request shape used by current Claude Code/OpenUsage integrations.
                "User-Agent" to "claude-code/2.1.69",
            ),
        )
        return parse(response)
    }

    internal fun parse(raw: String): ProviderUsage = parse(HttpResponseData(200, emptyMap(), raw))

    internal fun parse(response: HttpResponseData): ProviderUsage {
        val root = JSONObject(response.body)
        val windows = mutableListOf<UsageWindow>()

        // Keep the long-lived top-level fields as compatibility fallbacks. New structured limits are
        // appended afterwards and therefore win during semantic de-duplication.
        addWindowValue(root.opt("five_hour"), "five_hour", "Session", SESSION_SECONDS, windows)
        addWindowValue(root.opt("seven_day"), "seven_day", "Weekly", WEEK_SECONDS, windows)

        // Legacy per-model weekly keys are still accepted as a compatibility fallback.
        val rootKeys = root.keys()
        while (rootKeys.hasNext()) {
            val key = rootKeys.next()
            if (key.startsWith("seven_day_") && key != "seven_day") {
                val suffix = key.removePrefix("seven_day_")
                addWindowValue(root.opt(key), key, humanize(suffix), WEEK_SECONDS, windows)
            }
        }

        // Current Claude payloads may repeat Session/Weekly inside limits[] (or a map-shaped limits
        // object) while also carrying genuinely separate model-scoped windows. Parse the structured
        // representation last so it replaces the legacy representation instead of creating Limit 1/2.
        parseDynamicLimits(root.opt("limits"), windows)

        val deduped = dedupeWindows(windows)
        if (deduped.isEmpty()) {
            error("Claude usage response did not contain any recognized quota windows.")
        }

        val extraText = parseExtraUsage(root.optJSONObject("extra_usage"))
        var plan: String? = null
        for (key in listOf("plan_type", "plan", "subscription_type")) {
            val value = root.optString(key).takeIf { it.isNotBlank() }
            if (value != null) {
                plan = value
                break
            }
        }

        return ProviderUsage(
            provider = ProviderId.CLAUDE,
            plan = plan,
            windows = deduped,
            creditsText = extraText,
        )
    }

    private fun addWindowValue(
        rawValue: Any?,
        baseKey: String,
        baseLabel: String,
        defaultWindowSeconds: Long,
        out: MutableList<UsageWindow>,
    ) {
        val objects = jsonObjects(rawValue)
        objects.forEachIndexed { index, obj ->
            val percent = usedPercent(obj) ?: return@forEachIndexed
            val scopedName = scopedModelName(obj)
            val label = scopedName ?: if (index == 0) baseLabel else "$baseLabel ${index + 1}"
            val key = if (index == 0 && scopedName == null) baseKey else "${baseKey}_${slug(scopedName ?: (index + 1).toString())}"
            out += UsageWindow(
                key = key,
                label = label,
                usedPercent = percent,
                resetsAtEpochMs = resetAt(obj),
                windowSeconds = optLong(obj, "limit_window_seconds", "window_seconds") ?: defaultWindowSeconds,
            )
        }
    }

    private fun parseDynamicLimits(rawLimits: Any?, out: MutableList<UsageWindow>) {
        val entries = namedJsonObjects(rawLimits)
        entries.forEachIndexed { index, (entryName, item) ->
            val rawKind = item.optString("kind").takeIf { it.isNotBlank() } ?: entryName
            val kind = rawKind?.lowercase(Locale.US)?.replace('-', '_')
            val modelName = scopedModelName(item)
            val explicitName = modelName
                ?: item.optString("display_name").takeIf { it.isNotBlank() }
                ?: item.optString("name").takeIf { it.isNotBlank() }
                ?: friendlyEntryName(entryName)

            // Future/alternate payloads may put window-shaped data below rate_limit.
            val nestedRateLimit = item.optJSONObject("rate_limit")
            if (nestedRateLimit != null) {
                val baseLabel = explicitName ?: "Limit ${index + 1}"
                parseNestedRateLimit(
                    nestedRateLimit,
                    keyPrefix = "limit_${slug(baseLabel)}",
                    baseLabel = baseLabel,
                    out = out,
                )
                return@forEachIndexed
            }

            val percent = usedPercent(item) ?: return@forEachIndexed
            val seconds = optLong(item, "limit_window_seconds", "window_seconds")
            val semanticKind = classifyLimit(kind, modelName, seconds)
            val (key, label, defaultSeconds) = when (semanticKind) {
                ClaudeLimitKind.SESSION -> Triple("five_hour", "Session", SESSION_SECONDS)
                ClaudeLimitKind.WEEKLY -> Triple("seven_day", "Weekly", WEEK_SECONDS)
                ClaudeLimitKind.SCOPED_WEEKLY -> {
                    val scopedLabel = explicitName ?: "Scoped Weekly"
                    Triple("limit_scoped_${slug(scopedLabel)}", scopedLabel, WEEK_SECONDS)
                }
                ClaudeLimitKind.UNKNOWN -> {
                    val unknownLabel = explicitName ?: "Limit ${index + 1}"
                    Triple(
                        item.optString("id").takeIf { it.isNotBlank() }
                            ?: "limit_${slug(kind ?: "dynamic")}_${slug(unknownLabel)}",
                        unknownLabel,
                        seconds ?: WEEK_SECONDS,
                    )
                }
            }

            out += UsageWindow(
                key = key,
                label = label,
                usedPercent = percent,
                resetsAtEpochMs = resetAt(item),
                windowSeconds = seconds ?: defaultSeconds,
            )
        }
    }

    private enum class ClaudeLimitKind {
        SESSION,
        WEEKLY,
        SCOPED_WEEKLY,
        UNKNOWN,
    }

    private fun classifyLimit(kind: String?, modelName: String?, seconds: Long?): ClaudeLimitKind {
        val normalized = kind.orEmpty()
        if (normalized in setOf("session", "five_hour", "five_hour_session", "rolling_5h", "rolling_5_hour")) {
            return ClaudeLimitKind.SESSION
        }
        if (normalized in setOf("weekly_all", "weekly", "seven_day", "seven_day_all")) {
            return ClaudeLimitKind.WEEKLY
        }
        if (normalized == "weekly_scoped" || modelName != null) {
            return ClaudeLimitKind.SCOPED_WEEKLY
        }
        if (normalized.contains("session") || normalized.contains("five_hour")) {
            return ClaudeLimitKind.SESSION
        }
        if ((normalized.contains("weekly") || normalized.contains("seven_day")) && !normalized.contains("scoped")) {
            return ClaudeLimitKind.WEEKLY
        }
        if (seconds == SESSION_SECONDS) return ClaudeLimitKind.SESSION
        if (seconds == WEEK_SECONDS) return ClaudeLimitKind.WEEKLY
        return ClaudeLimitKind.UNKNOWN
    }

    private fun parseNestedRateLimit(
        rateLimit: JSONObject,
        keyPrefix: String,
        baseLabel: String,
        out: MutableList<UsageWindow>,
    ) {
        val primary = rateLimit.optJSONObject("primary_window") ?: rateLimit.optJSONObject("primary")
        val secondary = rateLimit.optJSONObject("secondary_window") ?: rateLimit.optJSONObject("secondary")
        val slots = listOf(false to primary, true to secondary)
        for ((slotWeekly, obj) in slots) {
            if (obj == null) continue
            val percent = usedPercent(obj) ?: continue
            val seconds = optLong(obj, "limit_window_seconds", "window_seconds")
            val isWeekly = when (seconds) {
                WEEK_SECONDS -> true
                SESSION_SECONDS -> false
                else -> slotWeekly
            }
            out += UsageWindow(
                key = "${keyPrefix}_${if (isWeekly) "weekly" else "session"}",
                label = if (isWeekly) "$baseLabel Weekly" else baseLabel,
                usedPercent = percent,
                resetsAtEpochMs = resetAt(obj),
                windowSeconds = seconds ?: if (isWeekly) WEEK_SECONDS else SESSION_SECONDS,
            )
        }
    }

    private fun jsonObjects(value: Any?): List<JSONObject> = namedJsonObjects(value).map { it.second }

    /**
     * Preserves dictionary keys for map-shaped quota payloads. Without this, a payload such as
     * {"session": {...}, "weekly_all": {...}} lost those names and surfaced as "Limit 1/2".
     */
    private fun namedJsonObjects(value: Any?): List<Pair<String?, JSONObject>> = when (value) {
        is JSONObject -> {
            if (hasUsageFields(value) || value.has("kind") || value.has("scope") || value.has("rate_limit")) {
                listOf(null to value)
            } else {
                val result = mutableListOf<Pair<String?, JSONObject>>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    value.optJSONObject(key)?.let { result += key to it }
                }
                result
            }
        }
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            value.optJSONObject(index)?.let { null to it }
        }
        else -> emptyList()
    }

    private fun friendlyEntryName(entryName: String?): String? {
        val raw = entryName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = raw.lowercase(Locale.US).replace('-', '_')
        if (normalized in setOf("session", "five_hour", "five_hour_session")) return "Session"
        if (normalized in setOf("weekly", "weekly_all", "seven_day", "seven_day_all")) return "Weekly"
        if (normalized.matches(Regex("limit_?\\d+"))) return null
        return humanize(normalized)
    }

    /**
     * Structured Claude responses can repeat the top-level Session/Weekly rows. Known semantic rows are
     * de-duplicated by label+period with the later structured value winning. As an extra schema-drift
     * guard, a generic "Limit N" row is dropped when it is byte-for-byte equivalent in quota meaning to
     * an already named row (same period, percent and reset timestamp).
     */
    private fun dedupeWindows(windows: List<UsageWindow>): List<UsageWindow> {
        val named = windows.filterNot { isGenericLimitLabel(it.label) }
        val filtered = windows.filterNot { candidate ->
            isGenericLimitLabel(candidate.label) && named.any { namedWindow -> equivalentQuota(candidate, namedWindow) }
        }

        val deduped = linkedMapOf<String, UsageWindow>()
        filtered.forEach { window ->
            val semanticKey = "${window.label.lowercase(Locale.US)}|${window.windowSeconds ?: 0L}"
            deduped[semanticKey] = window
        }
        return deduped.values.toList()
    }

    private fun isGenericLimitLabel(label: String): Boolean =
        label.matches(Regex("(?i)limit\\s*\\d+")) || label.equals("Limit", ignoreCase = true)

    private fun equivalentQuota(a: UsageWindow, b: UsageWindow): Boolean {
        if (kotlin.math.abs(a.usedPercent - b.usedPercent) > 0.001) return false
        // A shared reset timestamp is the strongest duplicate signal and survives payloads that omit
        // period metadata on one of the two representations.
        if (a.resetsAtEpochMs != null && a.resetsAtEpochMs == b.resetsAtEpochMs) return true
        return a.windowSeconds == b.windowSeconds && a.resetsAtEpochMs == b.resetsAtEpochMs
    }

    private fun hasUsageFields(obj: JSONObject): Boolean =
        listOf("utilization", "used_percent", "percent", "percent_left").any { key -> obj.has(key) }

    private fun usedPercent(obj: JSONObject): Double? {
        optNumber(obj, "utilization", "used_percent", "percent")?.let { return normalizePercent(it) }
        optNumber(obj, "percent_left")?.let { return normalizePercent(100.0 - it) }
        return null
    }

    private fun scopedModelName(item: JSONObject): String? {
        val scope = item.optJSONObject("scope") ?: return null
        val model = scope.opt("model")
        return when (model) {
            is JSONObject -> model.optString("display_name").takeIf { it.isNotBlank() }
                ?: model.optString("name").takeIf { it.isNotBlank() }
                ?: model.optString("id").takeIf { it.isNotBlank() }
            is String -> model.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun resetAt(obj: JSONObject): Long? {
        for (key in listOf("resets_at", "reset_at", "reset_time_ms", "resetsAt")) {
            if (obj.has(key) && !obj.isNull(key)) {
                val parsed = TimeFormat.parseEpochMillis(obj.opt(key))
                if (parsed != null) return parsed
            }
        }
        return optLong(obj, "reset_after_seconds")?.let { System.currentTimeMillis() + it * 1000L }
    }

    private fun parseExtraUsage(extra: JSONObject?): String? {
        if (extra == null || !extra.optBoolean("is_enabled", false)) return null
        val usedCents = optNumber(extra, "used_credits", "used")
        val limitCents = optNumber(extra, "monthly_limit", "limit")
        return when {
            usedCents != null && limitCents != null && limitCents > 0 ->
                "Extra usage \$${formatMoney(usedCents / 100.0)} / \$${formatMoney(limitCents / 100.0)}"
            usedCents != null && usedCents > 0 -> "Extra usage \$${formatMoney(usedCents / 100.0)}"
            else -> "Extra usage enabled"
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

    private fun optLong(json: JSONObject, vararg keys: String): Long? = optNumber(json, *keys)?.toLong()

    private fun humanize(value: String): String = value.split('_').joinToString(" ") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

    private fun slug(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "limit" }

    private fun formatMoney(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale.US, value)
}
