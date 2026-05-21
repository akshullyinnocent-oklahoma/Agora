package com.newoether.agora.data

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.zip.ZipInputStream
import java.util.Locale

class ClaudeChatImporter {

    @Serializable
    data class ClaudeConversation(
        val uuid: String,
        val name: String = "",
        val summary: String = "",
        @SerialName("created_at") val createdAt: String = "",
        @SerialName("updated_at") val updatedAt: String = "",
        @SerialName("chat_messages") val chatMessages: List<ClaudeMessage> = emptyList()
    )

    @Serializable
    data class ClaudeMessage(
        val uuid: String,
        val text: String = "",
        val sender: String = "",
        @SerialName("created_at") val createdAt: String = "",
        @SerialName("parent_message_uuid") val parentMessageUuid: String? = null,
        val attachments: List<ClaudeAttachment> = emptyList(),
        val files: List<ClaudeFile> = emptyList(),
        val content: List<ClaudeContent> = emptyList()
    )

    @Serializable
    data class ClaudeContent(
        val type: String = "",
        val text: String = "",
        val thinking: String = "",
        val citations: List<ClaudeCitation> = emptyList()
    )

    @Serializable
    data class ClaudeCitation(
        val uuid: String = "",
        val title: String? = null
    )

    @Serializable
    data class ClaudeAttachment(
        @SerialName("file_name") val fileName: String = "",
        @SerialName("file_size") val fileSize: Long = 0,
        @SerialName("file_type") val fileType: String = "",
        @SerialName("extracted_content") val extractedContent: String? = null
    )

    @Serializable
    data class ClaudeFile(
        @SerialName("file_uuid") val fileUuid: String = "",
        @SerialName("file_name") val fileName: String = "",
        @SerialName("file_size") val fileSize: Long = 0,
        @SerialName("file_type") val fileType: String = "",
        @SerialName("extracted_content") val extractedContent: String? = null
    )

    private fun toAttachmentItem(attachment: Any): AttachmentItem {
        return when (attachment) {
            is ClaudeAttachment -> toAttachmentItem(attachment)
            is ClaudeFile -> toAttachmentItem(attachment)
            else -> AttachmentItem(type = "file", fileName = null, textContent = null, mimeType = null)
        }
    }

    private fun toAttachmentItem(att: ClaudeAttachment): AttachmentItem {
        val isImage = att.fileType.startsWith("image/") || isImageFile(att.fileName)
        val isText = !isImage && (isTextFile(att.fileName) || att.fileType.contains("text"))
        return AttachmentItem(
            type = if (isImage) "image" else "file",
            fileName = att.fileName,
            textContent = if (isText) att.extractedContent else null,
            mimeType = att.fileType
        )
    }

    private fun toAttachmentItem(att: ClaudeFile): AttachmentItem {
        val isImage = att.fileType.startsWith("image/") || isImageFile(att.fileName)
        val isText = !isImage && (isTextFile(att.fileName) || att.fileType.contains("text"))
        return AttachmentItem(
            type = if (isImage) "image" else "file",
            fileName = att.fileName,
            textContent = if (isText) att.extractedContent else null,
            mimeType = att.fileType
        )
    }

    data class ConversationSummary(
        val uuid: String,
        val title: String,
        val messageCount: Int
    )

    data class ImportPreview(
        val conversations: List<ConversationSummary> = emptyList(),
        val conversationCount: Int,
        val totalMessageCount: Int,
        val humanMessageCount: Int,
        val assistantMessageCount: Int,
        val hasAttachments: Boolean
    )

    data class ImportResult(
        val conversationsImported: Int = 0,
        val messagesImported: Int = 0,
        val errors: List<String> = emptyList()
    )

    fun extractJsonFromBytes(bytes: ByteArray): Result<String> {
        return try {
            // Check for ZIP magic bytes
            if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                val zipInput = ZipInputStream(ByteArrayInputStream(bytes))
                var entry = zipInput.nextEntry
                // Prefer conversations.json, then any JSON that's not user/account data
                var fallbackJson: String? = null
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".json")) {
                        val json = zipInput.readBytes().decodeToString()
                        if (entry.name.contains("conversation", ignoreCase = true)) {
                            zipInput.close()
                            return Result.success(json)
                        }
                        if (fallbackJson == null && json.contains("\"chat_messages\"")) {
                            fallbackJson = json
                        }
                    }
                    zipInput.closeEntry()
                    entry = zipInput.nextEntry
                }
                zipInput.close()
                if (fallbackJson != null) {
                    Result.success(fallbackJson)
                } else {
                    Result.failure(Exception("No conversation data found in ZIP archive"))
                }
            } else {
                Result.success(bytes.decodeToString())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun parseJson(json: String): Result<ClaudeConversations> {
        return try {
            // Try wrapped format: {"conversations": [...]}
            try {
                val result = jsonParser.decodeFromString<ClaudeConversations>(json)
                if (result.conversations.isNotEmpty()) {
                    return Result.success(result)
                }
            } catch (_: Exception) { }
            // Try direct array format: [{...}, ...]
            val list = jsonParser.decodeFromString<List<ClaudeConversation>>(json)
            Result.success(ClaudeConversations(list))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun preview(conversations: ClaudeConversations): ImportPreview {
        val messages = conversations.conversations.flatMap { it.chatMessages }
        val hasAttachments = conversations.conversations.any { conv ->
            conv.chatMessages.any { msg ->
                msg.attachments.isNotEmpty() || msg.files.isNotEmpty()
            }
        }
        val summaries = conversations.conversations
            .sortedByDescending { conv -> iso8601ToMillis(conv.updatedAt) }
            .map { conv ->
                ConversationSummary(
                    uuid = conv.uuid,
                    title = conv.name.ifEmpty { conv.summary.take(50).ifEmpty { "Untitled" } },
                    messageCount = conv.chatMessages.size
                )
            }
        return ImportPreview(
            conversations = summaries,
            conversationCount = conversations.conversations.size,
            totalMessageCount = messages.size,
            humanMessageCount = messages.count { it.sender == "human" },
            assistantMessageCount = messages.count { it.sender == "assistant" },
            hasAttachments = hasAttachments
        )
    }

    fun toImportFormat(conversations: ClaudeConversations, selectedIds: Set<String>? = null): ImportConversations {
        val chatEntities = mutableListOf<ImportChatEntity>()
        val messageEntities = mutableListOf<ImportMessageEntity>()

        val filteredConversations = if (selectedIds != null) {
            conversations.conversations.filter { it.uuid in selectedIds }
        } else {
            conversations.conversations
        }

        for (conv in filteredConversations) {
            chatEntities.add(
                ImportChatEntity(
                    id = conv.uuid,
                    title = conv.name.ifEmpty { conv.summary.take(50).ifEmpty { "Untitled" } },
                    lastUpdated = iso8601ToMillis(conv.updatedAt),
                    selectedBranchesJson = null,
                    systemPromptId = null,
                    modelId = null
                )
            )

            for (msg in conv.chatMessages) {
                // Always prefer content blocks over raw text to properly exclude thinking
                val mergedText = if (msg.content.isNotEmpty()) {
                    msg.content.filter { it.type != "thinking" }.map { it.text }.joinToString("")
                } else {
                    msg.text
                }

                // Claude export does not embed image binary data; images are metadata-only in the files array

                val attachmentMeta = buildAttachmentMeta(conv.uuid, msg)

                val parentId = if (msg.parentMessageUuid != null &&
                    msg.parentMessageUuid != "00000000-0000-4000-8000-000000000000"
                ) msg.parentMessageUuid else null

                val thoughts = msg.content.find { it.type == "thinking" }?.thinking

                messageEntities.add(
                    ImportMessageEntity(
                        id = msg.uuid,
                        conversationId = conv.uuid,
                        parentId = parentId,
                        text = mergedText,
                        images = emptyList(),
                        thoughts = thoughts,
                        thoughtTitle = null,
                        tokenCount = 0,
                        status = "SUCCESS",
                        participant = if (msg.sender == "human") "USER" else "MODEL",
                        timestamp = iso8601ToMillis(msg.createdAt),
                        thoughtTimeMs = null,
                        modelName = null,
                        toolCallJson = null,
                        attachmentMeta = attachmentMeta
                    )
                )
            }
        }

        return ImportConversations(chatEntities, messageEntities)
    }

    private fun buildAttachmentMeta(conversationId: String, msg: ClaudeMessage): String? {
        val allAttachments = msg.attachments + msg.files
        if (allAttachments.isEmpty()) return null

        val attachmentItems = allAttachments.map { attachment -> toAttachmentItem(attachment) }

        val meta = AttachmentMeta(items = attachmentItems)
        return kotlinx.serialization.json.Json.encodeToString(AttachmentMeta.serializer(), meta)
    }

    private fun isTextFile(fileName: String): Boolean {
        val textExtensions = setOf(".txt", ".json", ".cpp", ".py", ".js", ".html", ".xml", ".md", ".java", ".kt", ".rs", ".go", ".ts", ".c", ".h", ".yaml", ".yml", ".toml", ".cfg", ".ini", ".properties")
        return fileName.lowercase().let { name ->
            textExtensions.any { name.endsWith(it) }
        }
    }

    private fun isImageFile(fileName: String): Boolean {
        val imageExtensions = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg", ".heic", ".heif")
        return fileName.lowercase().let { name ->
            imageExtensions.any { name.endsWith(it) }
        }
    }

    private fun iso8601ToMillis(iso8601: String): Long {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US)
            dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
            dateFormat.parse(iso8601)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    @Serializable
    data class ClaudeConversations(
        val conversations: List<ClaudeConversation>
    )

    @Serializable
    data class ImportChatEntity(
        val id: String,
        val title: String,
        val lastUpdated: Long,
        val selectedBranchesJson: String? = null,
        val systemPromptId: String? = null,
        val modelId: String? = null
    )

    @Serializable
    data class ImportMessageEntity(
        val id: String,
        val conversationId: String,
        val parentId: String? = null,
        val text: String,
        val images: List<String> = emptyList(),
        val thoughts: String? = null,
        val thoughtTitle: String? = null,
        val tokenCount: Int = 0,
        val status: String = "SUCCESS",
        val participant: String = "MODEL",
        val timestamp: Long,
        val thoughtTimeMs: Long? = null,
        val modelName: String? = null,
        val toolCallJson: String? = null,
        val attachmentMeta: String? = null
    )

    @Serializable
    data class ImportConversations(
        val conversations: List<ImportChatEntity>,
        val messages: List<ImportMessageEntity>
    )

  }
