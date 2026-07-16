package com.newoether.agora.api

import com.newoether.agora.util.DebugLog

/**
 * Stubbed LlamaEngine — local llama.cpp support removed for armeabi-v7a build.
 * Embedding computation now handled exclusively via API providers.
 * All methods return null/empty; callers already handle null gracefully.
 */
object LlamaEngine {
    private const val TAG = "LlamaEngine"

    fun isModelReady(modelPath: String): Boolean = false

    fun computeEmbedding(text: String, modelPath: String, beforeLoad: (() -> Unit)? = null): FloatArray? {
        DebugLog.d(TAG, "Local embeddings disabled (llama.cpp removed)")
        return null
    }

    fun computeEmbeddings(texts: List<String>, modelPath: String, beforeLoad: (() -> Unit)? = null): List<FloatArray?> {
        DebugLog.d(TAG, "Local embeddings disabled (llama.cpp removed)")
        return texts.map { null }
    }
}