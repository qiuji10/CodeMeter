package com.qiuji.codemeter.provider.claude

import com.qiuji.codemeter.network.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSessionClientTest {
    @Test
    fun sessionStartBodyIsDeliberatelyTiny() {
        val body = ClaudeSessionClient(Http()).requestBody("claude-haiku-4-5")

        assertEquals("claude-haiku-4-5", body.getString("model"))
        assertEquals(1, body.getInt("max_tokens"))
        assertTrue(body.getString("system").contains("Claude Code"))
        val message = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        assertEquals("Reply with hi.", message.getString("content"))
    }
}
