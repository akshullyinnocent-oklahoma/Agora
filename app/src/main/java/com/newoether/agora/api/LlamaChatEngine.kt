package com.newoether.agora.api

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

interface NativeChatCallback {
    fun onToken(token: String)
    fun onDone()
    fun onError(message: String)
}

class ChatTemplateMessage(val role: String, val content: String)

class LlamaChatEngine(
    val modelPath: String,
    val nCtx: Int = 2048
) : Closeable {
    companion object {
        private const val TAG = "LlamaChatEngine"

        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("agora_llama")
        }
    }

    private var nativeHandle: Long = 0

    private external fun nativeChatLoadModel(path: String, nCtx: Int): Long
    private external fun nativeChatGetTemplate(handle: Long): String?
    private external fun nativeChatApplyTemplate(handle: Long, messages: Array<ChatTemplateMessage>, addAss: Boolean): String?
    private external fun nativeChatGenerate(
        handle: Long, prompt: String, temperature: Float, topP: Float, maxTokens: Int,
        callback: NativeChatCallback
    ): Int
    private external fun nativeChatReset(handle: Long)
    private external fun nativeChatFreeModel(handle: Long)
    private external fun nativeChatCancel(handle: Long)

    fun isLoaded(): Boolean = nativeHandle != 0L

    fun load(): Boolean {
        if (!File(modelPath).exists()) {
            Log.e(TAG, "Model file not found: $modelPath")
            return false
        }
        nativeHandle = nativeChatLoadModel(modelPath, nCtx)
        if (nativeHandle == 0L) {
            Log.e(TAG, "Failed to load model: $modelPath")
            return false
        }
        Log.d(TAG, "Model loaded: $modelPath, nCtx=$nCtx")
        return true
    }

    fun getChatTemplate(): String? {
        if (nativeHandle == 0L) return null
        return nativeChatGetTemplate(nativeHandle)
    }

    fun applyTemplate(messages: List<ChatTemplateMessage>, addAss: Boolean = true): String? {
        if (nativeHandle == 0L) return null
        return nativeChatApplyTemplate(nativeHandle, messages.toTypedArray(), addAss)
    }

    fun generate(
        prompt: String,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = 4096
    ): Flow<String> = callbackFlow {
        if (nativeHandle == 0L) {
            close(Exception("Model not loaded"))
            return@callbackFlow
        }

        var errorMessage: String? = null

        val callback = object : NativeChatCallback {
            override fun onToken(token: String) {
                trySend(token)
            }

            override fun onDone() {
                close()
            }

            override fun onError(message: String) {
                errorMessage = message
                close()
            }
        }

        val job = launch(Dispatchers.IO) {
            try {
                nativeChatGenerate(nativeHandle, prompt, temperature, topP, maxTokens, callback)
            } catch (e: Exception) {
                Log.e(TAG, "nativeChatGenerate crashed", e)
                close(e)
            }
        }

        job.join()

        if (errorMessage != null) {
            Log.e(TAG, "Generation error: $errorMessage")
        }
    }

    fun cancel() {
        if (nativeHandle != 0L) {
            nativeChatCancel(nativeHandle)
        }
    }

    fun resetContext() {
        if (nativeHandle != 0L) {
            nativeChatReset(nativeHandle)
        }
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeChatFreeModel(nativeHandle)
            nativeHandle = 0L
            Log.d(TAG, "Model closed: $modelPath")
        }
    }

    protected fun finalize() {
        close()
    }
}
