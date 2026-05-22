package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object LlamaEngine {
    private const val TAG = "LlamaEngine"
    val modelMutex = Mutex()

    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("agora_llama")
    }

    private external fun nativeLoadModel(path: String): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeComputeEmbedding(handle: Long, text: String): FloatArray?
    private external fun nativeGetEmbeddingDim(handle: Long): Int

    fun isModelReady(modelPath: String): Boolean {
        return modelPath.isNotBlank() && File(modelPath).exists() && File(modelPath).length() > 0
    }

    fun computeEmbedding(text: String, modelPath: String, beforeLoad: (() -> Unit)? = null): FloatArray? {
        return runBlocking {
            modelMutex.withLock {
                beforeLoad?.invoke()
                val start = System.currentTimeMillis()
                val handle = nativeLoadModel(modelPath)
                if (handle == 0L) {
                    DebugLog.e(TAG, "Failed to load model (${System.currentTimeMillis() - start}ms): $modelPath")
                    return@runBlocking null
                }
                DebugLog.d(TAG, "Model loaded in ${System.currentTimeMillis() - start}ms, dim=${nativeGetEmbeddingDim(handle)}")
                val result = try {
                    val embd = nativeComputeEmbedding(handle, text)
                    if (embd == null) {
                        DebugLog.e(TAG, "nativeComputeEmbedding returned null for text len=${text.length}")
                    } else {
                        DebugLog.d(TAG, "Embedding computed: dim=${embd.size}, elapsed=${System.currentTimeMillis() - start}ms")
                    }
                    embd
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Embedding computation crashed", e)
                    null
                } finally {
                    nativeFreeModel(handle)
                }
                return@runBlocking result
            }
        }
    }
}
