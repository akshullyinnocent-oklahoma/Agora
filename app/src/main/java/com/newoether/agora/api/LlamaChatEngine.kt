package com.newoether.agora.api

/**
 * Stub for the removed llama.cpp chat engine.
 *
 * The native agora_llama library (llama.cpp) has been removed from the build
 * for armeabi-v7a compatibility. On-device GGUF chat inference is no longer available.
 * Use remote LLM providers (OpenAI, Gemini, Anthropic, Ollama, etc.) instead.
 */
class LlamaChatEngine(
    val modelPath: String,
    val nCtx: Int = 2048
) : java.io.Closeable {

    fun isLoaded(): Boolean = false

    fun load(): Boolean = false

    fun getChatTemplate(): String? = null

    fun applyTemplate(messages: List<ChatTemplateMessage>, addAss: Boolean = true): String? = null

    fun generate(
        prompt: String,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = 4096
    ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        throw RuntimeException("Local model inference is not available (llama.cpp removed)")
    }

    fun loadMmproj(mmprojPath: String): Boolean = false

    fun hasMmproj(): Boolean = false

    fun unloadMmproj() {}

    fun generateWithImages(
        prompt: String,
        imagePaths: List<String>,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = 4096
    ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        throw RuntimeException("Local model inference is not available (llama.cpp removed)")
    }

    fun cancel() {}

    fun resetContext() {}

    override fun close() {}

    protected fun finalize() {
        close()
    }
}