package com.newoether.agora.api

import android.content.Context
import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.util.ThinkingParser
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

class LocalProvider(
    private val context: Context,
    private val settingsManager: SettingsManager
) : LlmProvider {

    companion object {
        private const val TAG = "LocalProvider"
    }

    override val name: String = "Local"
    override val defaultBaseUrl: String = ""

    private var currentEngine: LlamaChatEngine? = null
    private val engineLock = Mutex()

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val chatModels = settingsManager.localChatModels.first()
        val modelConfig = chatModels.find { it.modelId == config.modelId }
        if (modelConfig == null) {
            emit(StreamEvent.Error("Local model not found: ${config.modelId}"))
            return@flow
        }

        val engine = ensureEngineLoaded(modelConfig)
        if (engine == null) {
            emit(StreamEvent.Error("Failed to load model: ${modelConfig.alias}"))
            return@flow
        }

        // Collect images from messages
        val imagePaths = messages.flatMap { it.images }.filter { it.isNotBlank() }

        val templateMessages = buildTemplateMessages(messages, config.systemPrompt)
        val prompt: String
        val hasImages = imagePaths.isNotEmpty()

        if (hasImages) {
            // For multimodal: use ChatML with <__media__> markers
            prompt = buildMultimodalPrompt(templateMessages, imagePaths)
            DebugLog.d(TAG, "Generated multimodal prompt (${prompt.length} chars, ${imagePaths.size} images)")
        } else {
            // Text-only: try native template first, fall back to ChatML
            prompt = engine.applyTemplate(templateMessages, addAss = true)
                ?: buildPrompt(templateMessages)
            DebugLog.d(TAG, "Generated prompt (${prompt.length} chars): ${prompt.take(200)}...")
        }

        // Generate tokens with unified thinking parsing
        var totalTokens = 0
        var stopped = false
        var rawBuf = ""
        val STOP_PATTERNS = listOf("<|im_end|>", "<|im_start|>")
        val thinkParser = ThinkingParser()
        try {
            val tokenFlow = if (hasImages) {
                engine.generateWithImages(
                    prompt = prompt,
                    imagePaths = imagePaths,
                    temperature = config.temperature ?: modelConfig.temperature,
                    topP = config.topP ?: modelConfig.topP,
                    maxTokens = config.maxTokens ?: modelConfig.maxTokens
                )
            } else {
                engine.generate(
                    prompt = prompt,
                    temperature = config.temperature ?: modelConfig.temperature,
                    topP = config.topP ?: modelConfig.topP,
                    maxTokens = config.maxTokens ?: modelConfig.maxTokens
                )
            }
            tokenFlow.collect { token ->
                if (!coroutineContext.isActive) {
                    engine.cancel()
                    return@collect
                }
                if (stopped) return@collect
                totalTokens++

                // Check for stop patterns in the rolling buffer
                rawBuf += token
                val hit = STOP_PATTERNS.firstOrNull { p -> rawBuf.contains(p) }
                if (hit != null) {
                    // Strip the stop pattern and anything after it, then stop
                    val cleanEnd = rawBuf.substringBefore(hit)
                    if (cleanEnd.isNotEmpty()) {
                        thinkParser.feed(
                            content = cleanEnd,
                            thinkingEnabled = config.thinkingEnabled,
                            onText = { emit(StreamEvent.TextChunk(it)) },
                            onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                        )
                    }
                    engine.cancel()
                    stopped = true
                    return@collect
                }

                // Keep buffer bounded — only as much as longest stop pattern
                val maxPatLen = STOP_PATTERNS.maxOf { it.length }
                if (rawBuf.length > maxPatLen * 2) {
                    val emitPart = rawBuf.substring(0, rawBuf.length - maxPatLen)
                    thinkParser.feed(
                        content = emitPart,
                        thinkingEnabled = config.thinkingEnabled,
                        onText = { emit(StreamEvent.TextChunk(it)) },
                        onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                    )
                    rawBuf = rawBuf.substring(rawBuf.length - maxPatLen)
                }
            }
            // Flush remaining buffer (no stop pattern found)
            if (!stopped && rawBuf.isNotEmpty()) {
                thinkParser.feed(
                    content = rawBuf,
                    thinkingEnabled = config.thinkingEnabled,
                    onText = { emit(StreamEvent.TextChunk(it)) },
                    onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                )
            }
            thinkParser.flush(
                onText = { emit(StreamEvent.TextChunk(it)) },
                onThought = { emit(StreamEvent.ThoughtChunk(it)) }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            engine.cancel()
            emit(StreamEvent.Error("Generation cancelled"))
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Generation failed", e)
            emit(StreamEvent.Error("Generation failed: ${e.message}"))
            return@flow
        }

        emit(StreamEvent.UsageUpdate(totalTokens))
    }.flowOn(Dispatchers.IO)

    private suspend fun ensureEngineLoaded(model: com.newoether.agora.data.LocalChatModelConfig): LlamaChatEngine? {
        return engineLock.withLock {
            val existing = currentEngine
            if (existing != null && existing.modelPath == model.localFilePath) {
                existing.resetContext()
                // Reload mmproj if path changed or was previously set
                if (model.mmprojPath.isNotBlank()) {
                    existing.loadMmproj(model.mmprojPath)
                }
                existing
            } else {
                existing?.close()
                val engine = LlamaChatEngine(model.localFilePath, model.nCtx)
                if (engine.load()) {
                    if (model.mmprojPath.isNotBlank()) {
                        val loaded = engine.loadMmproj(model.mmprojPath)
                        DebugLog.d(TAG, "mmproj load: $loaded for ${model.mmprojPath}")
                    }
                    currentEngine = engine
                    engine
                } else {
                    null
                }
            }
        }
    }

    private fun buildTemplateMessages(
        messages: List<ChatMessage>,
        systemPrompt: String?
    ): List<ChatTemplateMessage> {
        val result = mutableListOf<ChatTemplateMessage>()

        if (!systemPrompt.isNullOrBlank()) {
            result.add(ChatTemplateMessage(role = "system", content = systemPrompt))
        }

        for (msg in messages) {
            if (msg.participant == Participant.ERROR) continue

            // Tool call messages: treat as assistant
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        result.add(ChatTemplateMessage(
                            role = "assistant",
                            content = "Tool call: ${seg.toolName}\nArguments: ${seg.toolArgs}"
                        ))
                    }
                } else if (msg.toolCall != null) {
                    result.add(ChatTemplateMessage(
                        role = "assistant",
                        content = "Tool call: ${msg.toolCall.toolName}\nArguments: ${msg.toolCall.arguments}"
                    ))
                }
                continue
            }

            // Tool result messages: treat as user (tool results)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        result.add(ChatTemplateMessage(
                            role = "user",
                            content = "Tool result: ${seg.toolResult ?: ""}"
                        ))
                    }
                } else if (msg.toolCall != null) {
                    result.add(ChatTemplateMessage(
                        role = "user",
                        content = "Tool result: ${msg.toolCall.result}"
                    ))
                }
                continue
            }

            // Normal messages
            val role = when (msg.participant) {
                Participant.USER -> "user"
                Participant.MODEL -> "assistant"
                Participant.ERROR -> "user"
            }

            result.add(ChatTemplateMessage(role = role, content = msg.text))
        }

        return result
    }

    private fun buildMultimodalPrompt(
        messages: List<ChatTemplateMessage>,
        imagePaths: List<String>
    ): String {
        // Build ChatML prompt with <__media__> markers for each image.
        // Images are injected into the FIRST user message's content.
        val sb = StringBuilder()
        val mediaMarkers = imagePaths.joinToString("\n") { "<__media__>" } + "\n"

        for (msg in messages) {
            sb.append("<|im_start|>${msg.role}\n")
            if (msg.role == "user") {
                sb.append(mediaMarkers)
            }
            sb.append("${msg.content}<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun buildPrompt(messages: List<ChatTemplateMessage>): String {
        val sb = StringBuilder()
        for (msg in messages) {
            sb.append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> {
        return settingsManager.localChatModels.first().map { it.modelId }
    }

    fun close() {
        currentEngine?.close()
        currentEngine = null
    }

    suspend fun releaseEngine() {
        engineLock.withLock {
            currentEngine?.close()
            currentEngine = null
        }
    }

    fun releaseEngineBlocking() {
        kotlinx.coroutines.runBlocking { releaseEngine() }
    }
}
