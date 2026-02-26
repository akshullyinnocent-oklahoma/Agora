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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DeepSeekProvider : LlmProvider {
    override val name: String = "DeepSeek"
    override val defaultBaseUrl: String = "https://api.deepseek.com"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: "https://api.deepseek.com"
        val modelName = config.modelId

        val limitedPath = if (messages.size > config.maxContextWindow) {
            messages.takeLast(config.maxContextWindow)
        } else {
            messages
        }

        val apiMessages = mutableListOf<OpenAiMessage>()
        
        if (!config.systemPrompt.isNullOrBlank()) {
            apiMessages.add(OpenAiMessage(role = "system", content = listOf(OpenAiContentPart(type = "text", text = config.systemPrompt))))
        }

        apiMessages.addAll(limitedPath.map { msg ->
            val parts = mutableListOf<OpenAiContentPart>()
            if (msg.text.isNotEmpty()) parts.add(OpenAiContentPart(type = "text", text = msg.text))
            
            // Note: DeepSeek official API currently has limited image support in some regions/models, 
            // but we'll include it for compatibility with their future updates or proxy services.
            for (imagePath in msg.images) {
                try {
                    val file = File(imagePath)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        parts.add(OpenAiContentPart(type = "image_url", imageUrl = OpenAiImageUrl(url = "data:image/jpeg;base64,$base64")))
                    }
                } catch (e: Exception) {
                    Log.e("AgoraAPI", "Failed to encode image: $imagePath", e)
                }
            }
            if (parts.isEmpty()) parts.add(OpenAiContentPart(type = "text", text = " "))
            OpenAiMessage(role = if (msg.participant == Participant.USER) "user" else "assistant", content = parts)
        })

        val requestBody = OpenAiChatRequest(
            model = modelName,
            messages = apiMessages,
            stream = true,
            streamOptions = OpenAiStreamOptions(includeUsage = true)
        )

        var connection: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/chat/completions")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.doOutput = true
            
            connection.outputStream.bufferedWriter().use { 
                it.write(json.encodeToString(OpenAiChatRequest.serializer(), requestBody)) 
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = connection.inputStream.bufferedReader()
                var line = reader.readLine()
                var inThinkingBlock = false
                
                while (line != null && currentCoroutineContext().isActive) {
                    if (line.startsWith("data: ")) {
                        val jsonStr = line.substring(6).trim()
                        if (jsonStr == "[DONE]") break
                        
                        try {
                            val response = json.decodeFromString<OpenAiStreamResponse>(jsonStr)
                            val delta = response.choices?.firstOrNull()?.delta
                            
                            // 1. Handle native reasoning_content (DeepSeek R1 official API)
                            delta?.reasoningContent?.let { reasoning ->
                                if (reasoning.isNotEmpty() && config.thinkingEnabled) {
                                    emit(StreamEvent.ThoughtChunk(reasoning))
                                }
                            }
                            
                            // 2. Handle content and potential <think> tags (Common in open-source DeepSeek deployments)
                            delta?.content?.let { content ->
                                if (content.isNotEmpty()) {
                                    var remaining = content
                                    while (remaining.isNotEmpty() && currentCoroutineContext().isActive) {
                                        if (!inThinkingBlock) {
                                            val startIdx = remaining.indexOf("<think>")
                                            if (startIdx != -1) {
                                                // Emit text before <think>
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
                            
                            response.usage?.let { usage ->
                                emit(StreamEvent.UsageUpdate(
                                    tokenCount = usage.totalTokens,
                                    thoughtsTokenCount = usage.completionTokensDetails?.reasoningTokens ?: 0
                                ))
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

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = emptyList() // DeepSeek has static models usually
}
