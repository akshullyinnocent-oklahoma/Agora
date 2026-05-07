package com.newoether.agora.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object EmbeddingClient {

    private val json = Json { ignoreUnknownKeys = true }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun computeEmbedding(
        text: String,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1"
    ): FloatArray? {
        return try {
            val url = "$baseUrl/embeddings"
            val body = """{"input":${escapeJson(text)},"model":"$model"}"""
            val headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json"
            )
            val response = HttpClient.fetchModels(url, headers) ?: return null
            val parsed = json.parseToJsonElement(response).jsonObject
            val data = parsed["data"]?.jsonArray ?: return null
            val embedding = data.firstOrNull()?.jsonObject?.get("embedding")?.jsonArray ?: return null
            FloatArray(embedding.size) { i -> embedding[i].jsonPrimitive.float }
        } catch (e: Exception) {
            null
        }
    }

    fun computeEmbeddings(
        texts: List<String>,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1"
    ): List<FloatArray?> {
        if (texts.isEmpty()) return emptyList()
        return try {
            val url = "$baseUrl/embeddings"
            val inputs = texts.joinToString(",") { escapeJson(it) }
            val body = """{"input":[$inputs],"model":"$model"}"""
            val headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json"
            )
            val response = HttpClient.fetchModels(url, headers) ?: return texts.map { null }
            val parsed = json.parseToJsonElement(response).jsonObject
            val data = parsed["data"]?.jsonArray ?: return texts.map { null }
            data.map { item ->
                val embedding = item.jsonObject["embedding"]?.jsonArray ?: return@map null
                FloatArray(embedding.size) { i -> embedding[i].jsonPrimitive.float }
            }
        } catch (e: Exception) {
            texts.map { null }
        }
    }
}
