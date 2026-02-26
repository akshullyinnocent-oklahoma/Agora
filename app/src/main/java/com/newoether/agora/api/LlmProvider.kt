package com.newoether.agora.api

import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.flow.Flow

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
    val baseUrl: String? = null
)

interface LlmProvider {
    val name: String
    
    fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent>
    
    suspend fun fetchModels(apiKey: String): List<String>
}
