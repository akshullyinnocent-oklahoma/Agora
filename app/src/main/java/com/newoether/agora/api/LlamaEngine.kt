package com.newoether.agora.api

/**
 * Stub for the removed llama.cpp embedding engine.
 *
 * The native agora_llama library (llama.cpp) has been removed from the build
 * for armeabi-v7a compatibility. Local GGUF model inference is no longer available.
 * Use remote embedding providers (OpenAI, etc.) via [EmbeddingClient] instead.
 */
object LlamaEngine {

    fun isModelReady(modelPath: String): Boolean = false

    fun computeEmbedding(text: String, modelPath: String, beforeLoad: (() -> Unit)? = null): FloatArray? = null

    fun computeEmbeddings(texts: List<String>, modelPath: String, beforeLoad: (() -> Unit)? = null): List<FloatArray?> {
        return texts.map { null }
    }
}