package com.newoether.agora.api

import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

sealed class StreamEvent {
    data class TextChunk(val text: String) : StreamEvent()
    data class ThoughtChunk(val thought: String) : StreamEvent()
    data class UsageUpdate(val tokenCount: Int, val thoughtsTokenCount: Int = 0) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

data class ProviderConfig(
    val apiKey: String,
    val modelId: String,
    val systemPrompt: String? = null,
    val maxContextWindow: Int = 20,
    val codeExecutionEnabled: Boolean = false,
    val googleSearchEnabled: Boolean = false,
    val thinkingEnabled: Boolean = true,
    val baseUrl: String? = null
)

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: OpenAiStreamOptions? = null,
    val tools: List<OpenAiTool>? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null
)

@Serializable
data class OpenAiStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: List<OpenAiContentPart>
)

@Serializable
data class OpenAiContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: OpenAiImageUrl? = null
)

@Serializable
data class OpenAiImageUrl(
    val url: String
)

@Serializable
data class OpenAiTool(
    val type: String,
    val function: OpenAiFunction? = null
)

@Serializable
data class OpenAiFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null
)

@Serializable
data class OpenAiStreamResponse(
    val id: String? = null,
    val choices: List<OpenAiChoice>? = null,
    val usage: OpenAiUsage? = null
)

@Serializable
data class OpenAiChoice(
    val index: Int,
    val delta: OpenAiDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class OpenAiDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
data class OpenAiToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiFunctionCall? = null
)

@Serializable
data class OpenAiFunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
    @SerialName("completion_tokens_details") val completionTokensDetails: OpenAiCompletionTokensDetails? = null
)

@Serializable
data class OpenAiCompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null
)

@Serializable
data class OpenAiModelListResponse(val data: List<OpenAiModelInfo>)

@Serializable
data class OpenAiModelInfo(val id: String)

interface LlmProvider {
    val name: String
    val defaultBaseUrl: String
    
    fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent>
    
    suspend fun fetchModels(apiKey: String, baseUrl: String? = null): List<String>
}
