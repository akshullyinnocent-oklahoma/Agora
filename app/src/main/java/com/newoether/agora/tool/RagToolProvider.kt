package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext

/**
 * Provides tool definitions for RAG/conversation search tools.
 * Execution is handled by GenerationManager because it depends on
 * chatDao and semanticSearch which are tightly coupled to the generation pipeline.
 */
class RagToolProvider : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessPastConversations) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "search_conversations",
                description = "Search past conversations for relevant information. Use this to recall facts, decisions, or context from previous discussions.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "query" to ToolProperty("string", "The search query to find relevant past conversations."),
                        "limit" to ToolProperty("integer", "Maximum number of results (1-20, default 10).")
                    ),
                    required = listOf("query")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "list_conversations",
                description = "List all past conversations. Use this to browse conversation history and find conversations to read. Returns conversation IDs, titles, and last-updated timestamps.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "order" to ToolProperty("string", "Sort order by last updated time: 'asc' (oldest first) or 'desc' (newest first). Default: 'desc'."),
                        "limit" to ToolProperty("integer", "Maximum conversations per page (1-50, default 20)."),
                        "offset" to ToolProperty("integer", "Number of conversations to skip for pagination (default 0).")
                    ),
                    required = emptyList()
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "read_conversation",
                description = "Read a specific conversation by its ID. Shows the selected message branch as a linear list with page controls. Use this after list_conversations or search_conversations to read a conversation of interest. Each message includes participant, text, and timestamp.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "conversation_id" to ToolProperty("string", "The conversation ID to read (from list_conversations or search_conversations results)."),
                        "offset" to ToolProperty("integer", "Number of messages to skip for pagination (default 0)."),
                        "limit" to ToolProperty("integer", "Maximum messages per page (1-100, default 50).")
                    ),
                    required = listOf("conversation_id")
                )
            ))
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        // Execution is handled directly by GenerationManager.executeTool()
        // because it depends on chatDao and semanticSearch
        return "Unknown tool: $name"
    }

    override fun handles(name: String): Boolean = false
    // Execution is handled by GenerationManager.executeTool()'s when block
}
