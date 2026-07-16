package com.newoether.agora.api

import java.io.Closeable

/**
 * Stubbed LlamaChatEngine — local llama.cpp chat support removed for armeabi-v7a build.
 * LocalProvider still references this class but its methods are no-ops.
 */
class ChatTemplateMessage(val role: String, val content: String)

class LlamaChatEngine(
    val modelPath: String,
    val nCtx: Int = 2048
) : Closeable {

    fun isLoaded(): Boolean = false

    fun load(): Boolean = false

    fun getChatTemplate(): String? = null

    fun applyTemplate(
        messages: List<ChatTemplateMessage>,
        addAss: Boolean = true
    ): String? = null

    fun generate(
        prompt: String,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = 4096
    ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        throw RuntimeException("Local model inference disabled (llama.cpp removed)")
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
        throw RuntimeException("Local model inference disabled (llama.cpp removed)")
    }

    fun cancel() {}

    fun resetContext() {}

    override fun close() {}

    protected fun finalize() {
        close()
    }
}