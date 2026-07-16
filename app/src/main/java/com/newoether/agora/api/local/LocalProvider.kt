package com.newoether.agora.api.local

import com.newoether.agora.api.*
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stub for the removed local LLM provider.
 *
 * On-device GGUF model inference (via llama.cpp) has been removed from the build
 * for armeabi-v7a compatibility. This stub exists so that any references to
 * Constants.PROVIDER_LOCAL compile but immediately return an error at runtime.
 *
 * Use remote LLM providers (OpenAI, Gemini, Anthropic, Ollama, etc.) instead.
 */
class LocalProvider(
    private val context: android.content.Context,
    private val settings: com.newoether.agora.data.repository.SettingsRepository
) : LlmProvider {

    override val name: String = Constants.PROVIDER_LOCAL
    override val defaultBaseUrl: String = ""

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        emit(StreamEvent.Error(GenerationError.LocalModel("Local model inference is not available (llama.cpp removed for armeabi-v7a)")))
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = emptyList()

    fun close() {}

    suspend fun releaseEngine() {}

    fun releaseEngineBlocking() {}
}