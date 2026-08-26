package com.qiuji.codemeter.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.max

object TimeFormat {
    fun parseEpochMillis(value: Any?): Long? = when (value) {
        is Number -> {
            val raw = value.toLong()
            if (raw > 10_000_000_000L) raw else raw * 1000L
        }
        is String -> {
            value.toLongOrNull()?.let { if (it > 10_000_000_000L) it else it * 1000L }
                ?: try {
                    Instant.parse(value).toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                }
        }
        else -> null
    }

    fun remaining(resetAtEpochMs: Long?, now: Long = System.currentTimeMillis()): String {
        if (resetAtEpochMs == null) return "Reset time unavailable"
        var seconds = max(0, (resetAtEpochMs - now) / 1000)
        val days = seconds / 86_400
        seconds %= 86_400
        val hours = seconds / 3_600
        seconds %= 3_600
        val minutes = seconds / 60
        return when {
            days > 0 -> "Resets in ${days}d ${hours}h"
            hours > 0 -> "Resets in ${hours}h ${minutes}m"
            else -> "Resets in ${minutes}m"
        }
    }

    fun exact(resetAtEpochMs: Long?): String {
        if (resetAtEpochMs == null) return "Reset time unavailable"
        val formatter = DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
        return "Resets ${formatter.format(Instant.ofEpochMilli(resetAtEpochMs))}"
    }
}
