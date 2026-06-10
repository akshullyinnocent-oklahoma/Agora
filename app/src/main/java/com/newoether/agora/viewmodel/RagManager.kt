package com.newoether.agora.viewmodel

import com.newoether.agora.api.EmbeddingClient
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.data.EmbeddingIndexer
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.EmbeddingModelType
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.EmbeddingEntity
import com.newoether.agora.data.local.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages embedding indexing, semantic search, and RAG cache.
 * Designed to be used by ChatViewModel to reduce its size.
 */
class RagManager(
    private val chatDao: ChatDao
) {
    private val _cachingProgress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val cachingProgress: StateFlow<Map<String, Pair<Int, Int>>> = _cachingProgress.asStateFlow()

    private val _cacheCounts = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val cacheCounts: StateFlow<Map<String, Pair<Int, Int>>> = _cacheCounts.asStateFlow()

    fun loadCacheCounts(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            refreshCacheCounts()
        }
    }

    private suspend fun refreshCacheCounts() {
        try {
            val total = chatDao.getIndexableMessageCount()
            val counts = mutableMapOf<String, Pair<Int, Int>>()
            // Model IDs come from SettingsManager when wired into ChatViewModel
            _cacheCounts.value = counts
        } catch (_: Exception) { }
    }

    suspend fun indexMessageForRag(
        messageId: String,
        text: String,
        activeEmbeddingModel: EmbeddingModelConfig?,
        resolveEmbeddingApiKey: () -> String?
    ) {
        if (activeEmbeddingModel == null) return
        if (text.isBlank()) return

        withContext(Dispatchers.IO) {
            try {
                val embedding: FloatArray = when (activeEmbeddingModel.type) {
                    EmbeddingModelType.LOCAL -> {
                        if (!LlamaEngine.isModelReady(activeEmbeddingModel.localFilePath)) return@withContext
                        LlamaEngine.computeEmbedding(text, activeEmbeddingModel.localFilePath)
                    }
                    EmbeddingModelType.REMOTE -> {
                        val apiKey = resolveEmbeddingApiKey() ?: return@withContext
                        EmbeddingClient.computeEmbedding(
                            text, apiKey, activeEmbeddingModel.remoteModelName,
                            activeEmbeddingModel.remoteBaseUrl
                        )
                    }
                } ?: return@withContext

                val bytes = EmbeddingIndexer.floatsToBytes(embedding)
                chatDao.upsertEmbedding(EmbeddingEntity(
                    messageId = messageId,
                    modelId = activeEmbeddingModel.id,
                    embedding = bytes,
                    chunkText = text.take(500),
                    dimension = embedding.size
                ))
            } catch (_: Exception) { }
        }
    }

    suspend fun semanticSearch(
        query: String,
        limit: Int,
        activeEmbeddingModel: EmbeddingModelConfig?,
        ragThreshold: Float,
        resolveEmbeddingApiKey: suspend () -> String?
    ): List<Pair<MessageEntity, Float>> {
        if (activeEmbeddingModel == null) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val queryEmbedding: FloatArray = when (activeEmbeddingModel.type) {
                    EmbeddingModelType.LOCAL -> {
                        if (!LlamaEngine.isModelReady(activeEmbeddingModel.localFilePath)) return@withContext emptyList()
                        LlamaEngine.computeEmbedding(query, activeEmbeddingModel.localFilePath)
                    }
                    EmbeddingModelType.REMOTE -> {
                        val apiKey = resolveEmbeddingApiKey() ?: return@withContext emptyList()
                        EmbeddingClient.computeEmbedding(
                            query, apiKey, activeEmbeddingModel.remoteModelName,
                            activeEmbeddingModel.remoteBaseUrl
                        )
                    }
                } ?: return@withContext emptyList()

                val stored = chatDao.getEmbeddingsByModel(activeEmbeddingModel.id)
                val scored = stored.mapNotNull { entity ->
                    val storedFloats = EmbeddingIndexer.bytesToFloats(entity.embedding)
                    val sim = EmbeddingIndexer.cosineSimilarity(queryEmbedding, storedFloats)
                    if (sim >= ragThreshold) entity.messageId to sim else null
                }.sortedByDescending { it.second }.take(limit)

                val ids = scored.map { it.first }
                val messages = if (ids.isNotEmpty()) chatDao.getMessagesByIds(ids) else emptyList()
                scored.mapNotNull { (id, score) ->
                    messages.find { it.id == id }?.let { it to score }
                }
            } catch (_: Exception) { emptyList() }
        }
    }
}
