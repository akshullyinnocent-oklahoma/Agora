package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for conversation and message data access.
 *
 * Wraps ChatDao and provides:
 * - Conversation CRUD with cascade semantics
 * - Message tree persistence (parentId-based branching)
 * - Branch selection save/restore
 * - Stuck-message fixing on conversation load
 * - Embedding lifecycle management
 */
class ConversationRepository(
    private val chatDao: ChatDao,
    private val blobDir: java.io.File? = null
) {
    companion object {
        /** JSON larger than this gets offloaded to a file to avoid SQLite 2MB row limit. */
        private const val OFFLOAD_THRESHOLD = 512 * 1024
        private const val BLOB_MARKER = "@blob:"
    }

    // ── ToolCallJson offload ─────────────────────────────────────

    private fun MessageEntity.offloaded(): MessageEntity {
        val json = toolCallJson ?: return this
        val dir = blobDir ?: return this
        if (json.length <= OFFLOAD_THRESHOLD) return this
        if (json.startsWith(BLOB_MARKER)) return this // already offloaded
        try {
            if (!dir.exists()) dir.mkdirs()
            val fileName = "tc_${id}.json"
            java.io.File(dir, fileName).writeText(json)
            return copy(toolCallJson = "$BLOB_MARKER$fileName")
        } catch (_: Exception) {
            return this // fallback: keep in DB
        }
    }

    private fun MessageEntity.inflated(): MessageEntity {
        val json = toolCallJson ?: return this
        if (!json.startsWith(BLOB_MARKER)) return this
        val dir = blobDir ?: return this
        try {
            val fileName = json.removePrefix(BLOB_MARKER)
            val content = java.io.File(dir, fileName).readText()
            return copy(toolCallJson = content)
        } catch (_: Exception) {
            return this
        }
    }

    // ── Conversations ─────────────────────────────────────────

    fun getAllConversations(): Flow<List<ChatConversation>> =
        chatDao.getAllConversations().map { entities ->
            entities.map { ChatConversation(id = it.id, title = it.title, systemPromptId = it.systemPromptId, modelId = it.modelId) }
        }

    suspend fun getConversation(id: String): ChatEntity? =
        chatDao.getConversation(id)

    suspend fun createConversation(title: String, systemPromptId: String? = null, modelId: String? = null): String {
        val id = java.util.UUID.randomUUID().toString()
        chatDao.upsertConversation(ChatEntity(id = id, title = title, systemPromptId = systemPromptId, modelId = modelId))
        return id
    }

    suspend fun upsertConversation(entity: ChatEntity) = chatDao.upsertConversation(entity)

    suspend fun deleteConversation(id: String) {
        deleteConversationBlobs(id)
        chatDao.deleteEmbeddingsByConversation(id)
        chatDao.deleteMessagesByConversation(id)
        chatDao.deleteConversation(id)
    }

    private suspend fun deleteConversationBlobs(conversationId: String) {
        val dir = blobDir ?: return
        try {
            chatDao.getMessagesForConversation(conversationId).first().forEach { msg ->
                val json = msg.toolCallJson ?: return@forEach
                if (json.startsWith(BLOB_MARKER)) {
                    java.io.File(dir, json.removePrefix(BLOB_MARKER)).delete()
                }
            }
        } catch (_: Exception) { }
    }

    // ── Messages ──────────────────────────────────────────────

    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> =
        chatDao.getMessagesForConversation(conversationId).map { list -> list.map { it.inflated() } }

    suspend fun getMessagesForConversationSnapshot(conversationId: String): List<MessageEntity> =
        chatDao.getMessagesForConversation(conversationId).first().map { it.inflated() }

    suspend fun upsertMessage(entity: MessageEntity) = chatDao.upsertMessage(entity.offloaded())

    suspend fun deleteMessagesByIds(ids: List<String>) = chatDao.deleteMessagesByIds(ids)

    suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity> =
        chatDao.getMessagesByIds(ids).map { it.inflated() }

    // ── Branch Selection ──────────────────────────────────────

    /**
     * Persist the branch selection map for a conversation.
     * The map keys are parentId values (null for root).
     */
    suspend fun saveBranchSelections(conversationId: String, selections: Map<String?, String>) {
        val conversation = chatDao.getConversation(conversationId) ?: return
        val stringKeyMap = selections.mapKeys { it.key ?: "null" }
        val json = Json.encodeToString(stringKeyMap)
        if (conversation.selectedBranchesJson != json) {
            chatDao.upsertConversation(conversation.copy(selectedBranchesJson = json))
        }
    }

    /**
     * Restore branch selections from the persisted JSON.
     */
    suspend fun restoreBranchSelections(conversationId: String): Map<String?, String> {
        val conversation = chatDao.getConversation(conversationId) ?: return emptyMap()
        val raw = conversation.selectedBranchesJson ?: return emptyMap()
        return try {
            val map = Json.decodeFromString<Map<String, String>>(raw)
            map.mapKeys { if (it.key == "null") null else it.key }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ── Stuck Message Fixer ───────────────────────────────────

    /**
     * Fix messages stuck in in-progress states (SENDING, THINKING, TOOL_CALLING, TRANSCRIBING)
     * when loading a conversation that is not currently generating.
     */
    suspend fun fixStuckMessages(conversationId: String) {
        val stuckMessages = getMessagesForConversation(conversationId).first()
            .filter {
                it.status == MessageStatus.SENDING ||
                it.status == MessageStatus.THINKING ||
                it.status == MessageStatus.TOOL_CALLING ||
                it.status == MessageStatus.TRANSCRIBING
            }
        stuckMessages.forEach { msg ->
            upsertMessage(msg.copy(status = MessageStatus.STOPPED))
        }
    }

    // ── Embeddings ────────────────────────────────────────────

    suspend fun deleteEmbeddingsByConversation(conversationId: String) =
        chatDao.deleteEmbeddingsByConversation(conversationId)

    suspend fun deleteOrphanedEmbeddings() =
        chatDao.deleteOrphanedEmbeddings()

    suspend fun deleteEmbedding(messageId: String) =
        chatDao.deleteEmbedding(messageId)

    suspend fun getEmbeddingCountByModel(modelId: String): Int =
        chatDao.getEmbeddingCountByModel(modelId)

    suspend fun getIndexableMessageCount(): Int =
        chatDao.getIndexableMessageCount()

    // ── Search ────────────────────────────────────────────────

    suspend fun searchMessages(query: String, limit: Int = 10): List<MessageEntity> =
        chatDao.searchMessages(query, limit).map { it.inflated() }

    suspend fun getAllConversationsList(): List<ChatEntity> =
        chatDao.getAllConversationsList()

    suspend fun getAllMessagesList(): List<MessageEntity> =
        chatDao.getAllMessagesList().map { it.inflated() }

    suspend fun getAllMessagesForIndexing(): List<MessageEntity> =
        chatDao.getAllMessagesForIndexing().map { it.inflated() }
}
