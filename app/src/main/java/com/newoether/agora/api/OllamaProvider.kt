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
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = true,
    val options: JsonObject? = null
)

@Serializable
internal data class OllamaMessage(
    val role: String,
    val content: String,
    val images: List<String>? = null
)

@Serializable
internal data class OllamaStreamResponse(
    val model: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null
)

@Serializable
internal data class OllamaTagsResponse(
    val models: List<OllamaModelInfo>
)

@Serializable
internal data class OllamaModelInfo(
    val name: String
)

class OllamaProvider : LlmProvider {
    override val name: String = "Ollama"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: "http://localhost:11434"
        
        val limitedPath = if (messages.size > config.maxContextWindow) {
            messages.takeLast(config.maxContextWindow)
        } else {
            messages
        }

        val apiMessages = mutableListOf<OllamaMessage>()
        if (!config.systemPrompt.isNullOrBlank()) {
            apiMessages.add(OllamaMessage(role = "system", content = config.systemPrompt))
        }

        apiMessages.addAll(limitedPath.map { msg ->
            val images = msg.images.mapNotNull { imagePath ->
                try {
                    val file = File(imagePath)
                    if (file.exists()) {
                        android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                    } else null
                } catch (e: Exception) { null }
            }
            OllamaMessage(
                role = if (msg.participant == Participant.USER) "user" else "assistant",
                content = msg.text,
                images = if (images.isNotEmpty()) images else null
            )
        })

        val requestBody = OllamaChatRequest(
            model = config.modelId,
            messages = apiMessages,
            stream = true
        )

        var connection: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/api/chat")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            if (config.apiKey.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            connection.doOutput = true
            
            connection.outputStream.bufferedWriter().use { 
                it.write(json.encodeToString(OllamaChatRequest.serializer(), requestBody)) 
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = connection.inputStream.bufferedReader()
                var line = reader.readLine()
                var inThinkingBlock = false
                
                while (line != null && currentCoroutineContext().isActive) {
                    try {
                        val response = json.decodeFromString<OllamaStreamResponse>(line)
                        response.message?.content?.let { content ->
                            if (content.isNotEmpty()) {
                                var remaining = content
                                while (remaining.isNotEmpty() && currentCoroutineContext().isActive) {
                                    if (!inThinkingBlock) {
                                        val startIdx = remaining.indexOf("<think>")
                                        if (startIdx != -1) {
                                            val before = remaining.substring(0, startIdx)
                                            if (before.isNotEmpty()) emit(StreamEvent.TextChunk(before))
                                            inThinkingBlock = true
                                            remaining = remaining.substring(startIdx + 7)
                                        } else {
                                            emit(StreamEvent.TextChunk(remaining))
                                            remaining = ""
                                        }
                                    } else {
                                        val endIdx = remaining.indexOf("</think>")
                                        if (endIdx != -1) {
                                            val thought = remaining.substring(0, endIdx)
                                            if (thought.isNotEmpty() && config.thinkingEnabled) {
                                                emit(StreamEvent.ThoughtChunk(thought))
                                            }
                                            inThinkingBlock = false
                                            remaining = remaining.substring(endIdx + 8)
                                        } else {
                                            if (config.thinkingEnabled) {
                                                emit(StreamEvent.ThoughtChunk(remaining))
                                            }
                                            remaining = ""
                                        }
                                    }
                                }
                            }
                        }
                        if (response.done) {
                            val total = (response.promptEvalCount ?: 0) + (response.evalCount ?: 0)
                            emit(StreamEvent.UsageUpdate(total))
                        }
                    } catch (e: Exception) {
                        Log.e("AgoraAPI", "Parse error: ${e.message}")
                    }
                    line = reader.readLine()
                }
            } else {
                emit(StreamEvent.Error("Error $responseCode: ${connection.errorStream?.bufferedReader()?.readText()}"))
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

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: "http://localhost:11434"
            val url = URL("$effectiveBaseUrl/api/tags")
            val connection = url.openConnection() as HttpURLConnection
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<OllamaTagsResponse>(responseText).models.map { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
