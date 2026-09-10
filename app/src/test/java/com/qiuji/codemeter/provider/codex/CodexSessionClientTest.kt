package com.qiuji.codemeter.provider.codex

import com.qiuji.codemeter.network.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSessionClientTest {
    @Test
    fun sessionStartBodyIsNonStoredMinimalStream() {
        val body = CodexSessionClient(Http()).requestBody("gpt-5.6-luna")

        assertEquals("gpt-5.6-luna", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(0, body.getJSONArray("tools").length())
        val content = body.getJSONArray("input")
            .getJSONObject(0)
            .getJSONArray("content")
            .getJSONObject(0)
        assertEquals("input_text", content.getString("type"))
        assertEquals("Reply with hi only.", content.getString("text"))
    }
}
