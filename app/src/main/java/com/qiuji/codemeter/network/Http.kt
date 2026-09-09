package com.qiuji.codemeter.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Full HTTP response used by quota providers that need response headers as part of the API contract. */
data class HttpResponseData(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]
}

class HttpStatusException(
    val statusCode: Int,
    val responseText: String,
    val responseHeaders: Map<String, String> = emptyMap(),
) : IOException("HTTP $statusCode: ${responseText.take(240)}") {
    fun header(name: String): String? = responseHeaders[name.lowercase(Locale.US)]

    /** Parses Retry-After as either delta seconds or an RFC-1123 HTTP date. */
    fun retryAfterEpochMs(nowEpochMs: Long = System.currentTimeMillis()): Long? {
        val raw = header("retry-after")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.takeIf { it >= 0 }?.let { return nowEpochMs + it * 1000L }
        return try {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

class Http(private val client: OkHttpClient = defaultClient()) {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        getResponse(url, headers).body

    suspend fun getResponse(url: String, headers: Map<String, String> = emptyMap()): HttpResponseData =
        execute(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build())

    suspend fun postJson(url: String, json: JSONObject, headers: Map<String, String> = emptyMap()): String =
        postJsonResponse(url, json, headers).body

    suspend fun postJsonResponse(
        url: String,
        json: JSONObject,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponseData {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return execute(request)
    }

    suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): String =
        postFormResponse(url, fields, headers).body

    suspend fun postFormResponse(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponseData {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(body)
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): HttpResponseData = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            val headers = linkedMapOf<String, String>()
            response.headers.names().forEach { name ->
                headers[name.lowercase(Locale.US)] = response.headers.values(name).joinToString(", ")
            }
            if (!response.isSuccessful) throw HttpStatusException(response.code, text, headers)
            HttpResponseData(response.code, headers, text)
        }
    }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
