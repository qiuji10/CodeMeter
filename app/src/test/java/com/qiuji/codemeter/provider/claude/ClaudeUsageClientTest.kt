package com.qiuji.codemeter.provider.claude

import com.qiuji.codemeter.network.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClaudeUsageClientTest {
    private val client = ClaudeUsageClient(Http())

    @Test
    fun structuredCoreLimitsReplaceLegacyRowsWithoutDuplicates() {
        val usage = client.parse(
            """
            {
              "five_hour": {"utilization": 23, "resets_at": "2026-09-09T18:00:00Z"},
              "seven_day": {"utilization": 41, "resets_at": "2026-09-13T00:00:00Z"},
              "limits": [
                {
                  "kind": "session",
                  "percent": 23,
                  "resets_at": "2026-09-09T18:00:00Z",
                  "limit_window_seconds": 18000
                },
                {
                  "kind": "weekly_all",
                  "percent": 41,
                  "resets_at": "2026-09-13T00:00:00Z",
                  "limit_window_seconds": 604800
                },
                {
                  "kind": "weekly_scoped",
                  "percent": 12,
                  "resets_at": "2026-09-13T00:00:00Z",
                  "scope": {"model": {"display_name": "Fable"}}
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Session", "Weekly", "Fable"), usage.windows.map { it.label })
        assertEquals(3, usage.windows.size)
    }

    @Test
    fun mapShapedLimitsKeepTheirSemanticKeys() {
        val usage = client.parse(
            """
            {
              "five_hour": {"utilization": 10, "resets_at": "2026-09-09T18:00:00Z"},
              "seven_day": {"utilization": 20, "resets_at": "2026-09-13T00:00:00Z"},
              "limits": {
                "session": {"percent": 10, "resets_at": "2026-09-09T18:00:00Z"},
                "weekly_all": {"percent": 20, "resets_at": "2026-09-13T00:00:00Z"}
              }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Session", "Weekly"), usage.windows.map { it.label })
        assertFalse(usage.windows.any { it.label.startsWith("Limit") })
    }

    @Test
    fun anonymousDuplicateLimitsAreSuppressedByQuotaIdentity() {
        val usage = client.parse(
            """
            {
              "five_hour": {"utilization": 55, "resets_at": "2026-09-09T18:00:00Z"},
              "seven_day": {"utilization": 66, "resets_at": "2026-09-13T00:00:00Z"},
              "limits": [
                {"percent": 55, "resets_at": "2026-09-09T18:00:00Z"},
                {"percent": 66, "resets_at": "2026-09-13T00:00:00Z"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Session", "Weekly"), usage.windows.map { it.label })
    }
}
