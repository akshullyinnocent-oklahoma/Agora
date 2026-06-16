package com.newoether.agora.api.openai

import com.newoether.agora.api.*

import com.newoether.agora.api.util.StreamingThinkTagParser

class CustomOpenAiProvider(
    override val name: String,
    override val defaultBaseUrl: String
) : BaseOpenAiProvider() {

    override val retryableStatusCodes: Set<Int> = setOf(401, 429, 502, 503, 504)

    override val retryMissingV1BaseUrl: Boolean = true

    override fun retryDelayMillis(statusCode: Int, attempt: Int): Long =
        if (statusCode == 401) 5000L else super.retryDelayMillis(statusCode, attempt)

    override suspend fun parseDeltaContent(
        delta: OpenAiDelta,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        delta.reasoningContent?.let { reasoning ->
            if (reasoning.isNotEmpty() && config.thinkingEnabled) {
                emit(StreamEvent.ThoughtChunk(reasoning))
            }
        }
        delta.content?.let { content ->
            if (content.isNotEmpty()) emit(StreamEvent.TextChunk(content))
        }
    }
}
