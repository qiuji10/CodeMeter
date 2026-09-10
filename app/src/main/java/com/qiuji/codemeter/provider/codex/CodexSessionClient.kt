package com.qiuji.codemeter.provider.codex

import com.qiuji.codemeter.network.Http
import com.qiuji.codemeter.network.HttpStatusException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Sends one minimal Codex Responses turn to start the normal subscription session window. */
class CodexSessionClient(private val http: Http) {
    companion object {
        const val RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"

        // Luna is intentionally first because the starter should consume as little quota/latency as
        // practical. The fallbacks are only tried when the backend explicitly rejects the model slug.
        private val MODEL_CANDIDATES = listOf(
            "gpt-5.6-luna",
            "gpt-5.6-terra",
            "gpt-5.6-sol",
        )
    }

    suspend fun start(accessToken: String, accountId: String) {
        var lastModelError: HttpStatusException? = null
        for (model in MODEL_CANDIDATES) {
            try {
                val sessionId = UUID.randomUUID().toString()
                http.postJsonResponse(
                    RESPONSES_URL,
                    requestBody(model),
                    mapOf(
                        "Authorization" to "Bearer $accessToken",
                        "ChatGPT-Account-Id" to accountId,
                        "Accept" to "text/event-stream",
                        "Content-Type" to "application/json",
                        // The ChatGPT Codex backend is an internal/undocumented contract. Keep the
                        // caller identity honest instead of impersonating the official CLI.
                        "originator" to "codemeter",
                        "session_id" to sessionId,
                        "x-client-request-id" to sessionId,
                        "OpenAI-Beta" to "responses=v1",
                        "User-Agent" to "CodeMeter Android",
                    ),
                )
                return
            } catch (error: HttpStatusException) {
                if (!isModelCompatibilityError(error, model)) throw error
                lastModelError = error
            }
        }
        throw lastModelError ?: error("Codex did not accept a session-start model.")
    }

    internal fun requestBody(model: String): JSONObject = JSONObject().apply {
        put("model", model)
        put("instructions", "Reply with hi only.")
        put(
            "input",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "input_text")
                                .put("text", "Reply with hi only."),
                        ),
                    ),
            ),
        )
        put("tools", JSONArray())
        put("tool_choice", "auto")
        put("parallel_tool_calls", false)
        put("include", JSONArray())
        put("store", false)
        put("stream", true)
    }

    private fun isModelCompatibilityError(error: HttpStatusException, model: String): Boolean {
        if (error.statusCode !in setOf(400, 404)) return false
        val body = error.responseText.lowercase()
        return model.lowercase() in body && ("model" in body || "not found" in body || "invalid" in body)
    }
}
