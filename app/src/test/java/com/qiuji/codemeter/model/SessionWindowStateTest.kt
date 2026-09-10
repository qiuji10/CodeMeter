package com.qiuji.codemeter.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWindowStateTest {
    @Test
    fun missingSessionMeansStarterCanBeOfferedOnFreshUsage() {
        val usage = ProviderUsage(
            provider = ProviderId.CLAUDE,
            windows = listOf(UsageWindow("seven_day", "Weekly", 20.0, null, 604800)),
        )
        assertFalse(usage.hasActiveSessionWindow())
        assertTrue(usage.canStartSessionWindow())
    }

    @Test
    fun resetTimestampMeansZeroPercentSessionIsAlreadyActive() {
        val usage = ProviderUsage(
            provider = ProviderId.CLAUDE,
            windows = listOf(UsageWindow("five_hour", "Session", 0.0, 1_900_000_000_000L, 18000)),
        )
        assertTrue(usage.hasActiveSessionWindow())
        assertFalse(usage.canStartSessionWindow())
    }

    @Test
    fun staleUsageNeverOffersStarter() {
        val usage = ProviderUsage(
            provider = ProviderId.CODEX,
            windows = listOf(UsageWindow("weekly", "Weekly", 10.0, null, 604800)),
            isStale = true,
        )
        assertFalse(usage.canStartSessionWindow())
    }
}
