package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.service.AgoraForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Handles image/video/PDF transcription as a pre-processing stage before
 * LLM generation. Extracted from GenerationManager (~160 lines).
 *
 * Lifecycle: created once per GenerationManager, used for each generation
 * that has images needing transcription.
 */
class TranscriptionManager(
    private val providers: Map<String, LlmProvider>,
    private val chatDao: ChatDao,
    private val context: Context
) {
    data class TranscriptionTarget(
        val messageId: String,
        val imagePath: String,
        val metaItemIndex: Int
    )

    /**
     * Collect all images in the message path that need transcription.
     * Skips images that already have a transcription (unless they're in
     * the latest user message — re-transcribe on every send).
     */
    suspend fun collectTargets(
        conversationId: String,
        parentId: String?
    ): List<TranscriptionTarget> {
        val allMessages = chatDao.getMessagesForConversation(conversationId).first()
        val pathMessages = mutableListOf<MessageEntity>()
        var currentId = parentId
        while (currentId != null) {
            val msg = allMessages.find { it.id == currentId } ?: break
            pathMessages.add(0, msg)
            currentId = msg.parentId
        }
        val latestUserMsg = pathMessages.lastOrNull { it.participant == Participant.USER }
        val targets = mutableListOf<TranscriptionTarget>()
        for (msg in pathMessages) {
            if (msg.participant != Participant.USER) continue
            if (msg.images.isEmpty()) continue
            val meta = msg.attachmentMeta?.let {
                try { Json.decodeFromString<AttachmentMeta>(it) } catch (_: Exception) { null }
            } ?: continue
            val isLatest = msg.id == latestUserMsg?.id
            meta.items.forEachIndexed { index, item ->
                val imageIndex = item.imageIndex
                val imageType = item.type
                if (imageIndex == null || (imageType != "image" && imageType != "pdf" && imageType != "video")) return@forEachIndexed
                val count = when (imageType) {
                    "pdf" -> item.pageCount ?: 1
                    "video" -> item.pageCount ?: 1
                    else -> 1
                }
                for (i in 0 until count) {
                    val offset = imageIndex + i
                    if (offset !in msg.images.indices) break
                    val imagePath = msg.images[offset]
                    if (isLatest || item.transcription.isNullOrEmpty()) {
                        targets.add(TranscriptionTarget(msg.id, imagePath, index))
                    }
                }
            }
        }
        return targets
    }

    /**
     * Run transcription for all targets. Streams progress via [onProgress].
     * Returns the transcription segments (for display in the UI) and persists
     * results to the message attachment metadata.
     *
     * @return Pair of (segments list, error message or null)
     */
    suspend fun transcribe(
        targets: List<TranscriptionTarget>,
        conversationId: String,
        providerName: String,
        modelId: String,
        apiKey: String,
        baseUrl: String?,
        generationJob: Job?,
        modelMessageId: String,
        startTime: Long,
        onProgress: (ChatMessage) -> Unit
    ): Pair<List<MessageSegment>, String?> {
        val provider = providers[providerName] ?: providers.values.first()
        val transcriptionConfig = ProviderConfig(
            apiKey = apiKey,
            modelId = modelId,
            systemPrompt = "You are an image describer. Describe the given image in detail.",
            thinkingEnabled = false,
            baseUrl = baseUrl
        )
        val placeholder = chatDao.getMessagesForConversation(conversationId).first().find { it.id == modelMessageId }
        val parentId = placeholder?.parentId
        val results = mutableMapOf<String, MutableList<Pair<Int, String>>>()
        val transcriptionSegments = mutableListOf<MessageSegment>()
        var processed = 0
        val total = targets.size

        for (target in targets) {
            if (generationJob?.isCancelled == true) throw CancellationException("Transcription cancelled")
            if (!currentCoroutineContext().isActive) throw CancellationException("Transcription cancelled")

            withContext(Dispatchers.Main) {
                AgoraForegroundService.updateText(context.getString(R.string.transcription_progress, processed + 1, total))
            }

            val currentSegment = MessageSegment(type = "transcription", content = "Transcribing...")
            transcriptionSegments.add(currentSegment)
            onProgress(ChatMessage(
                id = modelMessageId, parentId = parentId, text = "",
                participant = Participant.MODEL, status = MessageStatus.TRANSCRIBING, timestamp = startTime,
                retryText = "${processed + 1}/$total",
                thoughtTitle = "Image Transcription",
                segments = transcriptionSegments.toList(),
            ))

            val promptMessages = listOf(ChatMessage(
                text = "Please describe this image in detail. Include all visible text, data, charts, layout, and visual elements. Preserve the original language of any text shown.",
                images = listOf(target.imagePath),
                participant = Participant.USER,
                status = MessageStatus.SUCCESS
            ))
            val transcription = StringBuilder()
            var streamError: String? = null
            provider.generateResponse(promptMessages, transcriptionConfig).collect { event ->
                when (event) {
                    is StreamEvent.TextChunk -> {
                        transcription.append(event.text)
                        transcriptionSegments[transcriptionSegments.lastIndex] = currentSegment.copy(content = transcription.toString())
                        onProgress(ChatMessage(
                            id = modelMessageId, parentId = parentId, text = "",
                            participant = Participant.MODEL, status = MessageStatus.TRANSCRIBING, timestamp = startTime,
                            retryText = "${processed + 1}/$total",
                            thoughtTitle = "Image Transcription",
                            segments = transcriptionSegments.toList(),
                        ))
                    }
                    is StreamEvent.Error -> { streamError = event.message }
                    else -> {}
                }
            }
            if (streamError != null) return Pair(transcriptionSegments, streamError)
            val text = transcription.toString().trim()
            transcriptionSegments[transcriptionSegments.lastIndex] = currentSegment.copy(content = text)
            results.getOrPut(target.messageId) { mutableListOf() }
                .add(target.metaItemIndex to text)
            processed++
        }

        // Persist results back to message attachment metadata
        for ((messageId, updates) in results) {
            val entity = chatDao.getMessagesForConversation(conversationId).first().find { it.id == messageId }
            if (entity != null) {
                val meta = entity.attachmentMeta?.let {
                    try { Json.decodeFromString<AttachmentMeta>(it) } catch (_: Exception) { null }
                } ?: AttachmentMeta()
                val items = meta.items.toMutableList()
                val grouped: Map<Int, List<String>> = updates.groupBy { it.first }.mapValues { e -> e.value.map { it.second } }
                for ((index, texts) in grouped) {
                    if (index in items.indices) {
                        val joined = if (texts.size == 1) texts.first()
                        else texts.mapIndexed { i, t -> "[Page ${i + 1}]\n$t" }.joinToString("\n\n")
                        items[index] = items[index].copy(transcription = joined)
                    }
                }
                chatDao.upsertMessage(entity.copy(
                    attachmentMeta = Json.encodeToString(AttachmentMeta.serializer(), AttachmentMeta(items = items))
                ))
            }
        }
        return Pair(transcriptionSegments, null)
    }
}
