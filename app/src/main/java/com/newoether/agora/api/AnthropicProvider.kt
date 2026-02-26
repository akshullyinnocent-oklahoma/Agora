package com.newoether.agora.api

import android.util.Log
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
internal data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = true,
    val thinking: AnthropicThinking? = null
)

@Serializable
internal data class AnthropicThinking(
    val type: String = "enabled",
    @SerialName("budget_tokens") val budgetTokens: Int
)

@Serializable
internal data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentPart>
)

@Serializable
internal data class AnthropicContentPart(
    val type: String,
    val text: String? = null,
    val source: AnthropicImageSource? = null
)

@Serializable
internal data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String
)

@Serializable
internal data class AnthropicStreamEvent(
    val type: String,
    val delta: AnthropicDelta? = null,
    val message: AnthropicMessageInfo? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
internal data class AnthropicDelta(
    val text: String? = null,
    @SerialName("thinking") val thinking: String? = null
)

@Serializable
internal data class AnthropicMessageInfo(
    val usage: AnthropicUsage? = null
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null
)

class AnthropicProvider : LlmProvider {
    override val name: String = "Anthropic"
    override val defaultBaseUrl: String = "https://api.anthropic.com/v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: "https://api.anthropic.com/v1"
        val modelName = config.modelId

        val limitedPath = if (messages.size > config.maxContextWindow) {
            messages.takeLast(config.maxContextWindow)
        } else {
            messages
        }

        val apiMessages = limitedPath.map { msg ->
            val parts = mutableListOf<AnthropicContentPart>()
            if (msg.text.isNotEmpty()) parts.add(AnthropicContentPart(type = "text", text = msg.text))
            
            for (imagePath in msg.images) {
                try {
                    val file = File(imagePath)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        val mimeType = if (imagePath.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
                        parts.add(AnthropicContentPart(type = "image", source = AnthropicImageSource(mediaType = mimeType, data = base64)))
                    }
                } catch (e: Exception) {
                    Log.e("AgoraAPI", "Failed to encode image: $imagePath", e)
                }
            }
            if (parts.isEmpty()) parts.add(AnthropicContentPart(type = "text", text = " "))
            AnthropicMessage(role = if (msg.participant == Participant.USER) "user" else "assistant", content = parts)
        }

        // Claude thinking logic
        val thinking = if (config.thinkingEnabled && (modelName.contains("claude-3-7") || modelName.contains("claude-3-5"))) {
            AnthropicThinking(budgetTokens = 1024)
        } else null

        val requestBody = AnthropicRequest(
            model = modelName,
            messages = apiMessages,
            system = config.systemPrompt,
            thinking = thinking,
            maxTokens = if (thinking != null) 2048 else 4096
        )

        var connection: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/messages")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-api-key", config.apiKey)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.doOutput = true
            
            connection.outputStream.bufferedWriter().use { 
                it.write(json.encodeToString(AnthropicRequest.serializer(), requestBody)) 
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = connection.inputStream.bufferedReader()
                var line = reader.readLine()
                var currentType: String? = null
                
                while (line != null && currentCoroutineContext().isActive) {
                    if (line.startsWith("event: ")) {
                        currentType = line.substring(7).trim()
                    } else if (line.startsWith("data: ")) {
                        val jsonStr = line.substring(6).trim()
                        try {
                            val event = json.decodeFromString<AnthropicStreamEvent>(jsonStr)
                            when (event.type) {
                                "content_block_delta" -> {
                                    event.delta?.text?.let { emit(StreamEvent.TextChunk(it)) }
                                    event.delta?.thinking?.let { emit(StreamEvent.ThoughtChunk(it)) }
                                }
                                "message_delta" -> {
                                    event.usage?.let { u ->
                                        val total = (u.inputTokens ?: 0) + (u.outputTokens ?: 0)
                                        emit(StreamEvent.UsageUpdate(total))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AgoraAPI", "Parse error: ${e.message}", e)
                        }
                    }
                    line = reader.readLine()
                }
            } else {
                val errorRaw = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                emit(StreamEvent.Error("Error $responseCode: $errorRaw"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error("Error: ${e.localizedMessage}"))
            }
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = listOf(
        "claude-3-7-sonnet-20250219",
        "claude-3-5-sonnet-20241022",
        "claude-3-5-haiku-20241022",
        "claude-3-opus-20240229",
        "claude-3-sonnet-20240229",
        "claude-3-haiku-20240307"
    )
}
