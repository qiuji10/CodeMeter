package com.qiuji.codemeter.provider.claude

import com.qiuji.codemeter.network.Http
import com.qiuji.codemeter.network.HttpStatusException
import org.json.JSONArray
import org.json.JSONObject

/** Sends one deliberately tiny Claude inference turn to start the subscription session window. */
class ClaudeSessionClient(private val http: Http) {
    companion object {
        const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        private const val CLAUDE_CODE_SYSTEM = "You are Claude Code, Anthropic's official CLI for Claude."

        // Haiku keeps the priming request small. Sonnet is a compatibility fallback if the lightweight
        // model slug changes/rolls off before CodeMeter can be updated.
        private val MODEL_CANDIDATES = listOf(
            "claude-haiku-4-5",
            "claude-sonnet-4-6",
        )
    }

    suspend fun start(accessToken: String) {
        var lastModelError: HttpStatusException? = null
        for (model in MODEL_CANDIDATES) {
            try {
                http.postJsonResponse(
                    MESSAGES_URL,
                    requestBody(model),
                    mapOf(
                        "Authorization" to "Bearer ${accessToken.trim()}",
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                        "anthropic-version" to "2023-06-01",
                        "anthropic-beta" to "oauth-2025-04-20",
                        "User-Agent" to "claude-code/2.1.69",
                    ),
                )
                return
            } catch (error: HttpStatusException) {
                if (!isModelCompatibilityError(error, model)) throw error
                lastModelError = error
            }
        }
        throw lastModelError ?: error("Claude did not accept a session-start model.")
    }

    internal fun requestBody(model: String): JSONObject = JSONObject().apply {
        put("model", model)
        put("max_tokens", 1)
        // Current subscription OAuth routing can require the Claude Code identity marker to be the
        // first system content. A plain string is accepted by the current Messages endpoint.
        put("system", CLAUDE_CODE_SYSTEM)
        put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", "Reply with hi."),
            ),
        )
    }

    private fun isModelCompatibilityError(error: HttpStatusException, model: String): Boolean {
        if (error.statusCode !in setOf(400, 404)) return false
        val body = error.responseText.lowercase()
        return model.lowercase() in body && ("model" in body || "not found" in body || "invalid" in body)
    }
}
