package com.newoether.agora.api

import okhttp3.MediaType.Companion.toMediaType
import com.newoether.agora.util.DebugLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.util.concurrent.TimeUnit

object HttpClient {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The currently active streaming handle, if any. Used to cancel
     *  generation immediately by closing the underlying socket. */
    @Volatile var activeStreamHandle: StreamHandle? = null

    class StreamHandle(private val call: okhttp3.Call, private val response: okhttp3.Response) {
        val code: Int get() = response.code
        val source: BufferedSource? get() = response.body?.source()
        val errorBody: String? by lazy {
            try { response.body?.string() } catch (_: Exception) { null }
        }
        fun close() {
            if (HttpClient.activeStreamHandle === this) {
                HttpClient.activeStreamHandle = null
            }
            response.close()
        }
        fun readLine(): String? = source?.readUtf8Line()
        /** Cancel the underlying HTTP call immediately — unblocks [readLine]. */
        fun cancel() = call.cancel()
    }

    fun streamPost(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): StreamHandle {
        val body = jsonBody.toRequestBody(JSON)
        val requestBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val call = client.newCall(requestBuilder.build())
        val handle = StreamHandle(call, call.execute())
        activeStreamHandle = handle
        return handle
    }

    fun post(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): String? {
        val body = jsonBody.toRequestBody(JSON)
        val requestBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val response = client.newCall(requestBuilder.build()).execute()
        return response.use {
            if (it.isSuccessful) it.body?.string()
            else {
                DebugLog.e("HttpClient", "POST $url failed: ${it.code} ${it.body?.string()}")
                null
            }
        }
    }

    fun fetchModels(url: String, headers: Map<String, String> = emptyMap()): String? {
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val response = client.newCall(requestBuilder.build()).execute()
        return response.use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }
}
