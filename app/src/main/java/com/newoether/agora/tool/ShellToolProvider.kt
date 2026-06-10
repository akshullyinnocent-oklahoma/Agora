package com.newoether.agora.tool

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.ShellClient
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ShellToolProvider : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.shellEnabled || ctx.shellDevices.isEmpty()) return emptyList()
        val deviceNames = ctx.shellDevices.joinToString(", ") { d -> "\"${d.name}\"" }
        val serverProperty = if (ctx.shellDevices.size == 1) {
            ToolProperty("string", "The shell server name (optional, defaults to the only configured server: \"${ctx.shellDevices[0].name}\").")
        } else {
            ToolProperty("string", "The shell server name. Use list_shells to see available servers: $deviceNames.")
        }
        val shellRequiredParams = if (ctx.shellDevices.size == 1) listOf("command") else listOf("command", "server")

        val shellTools = listOf(
            ToolDefinition(function = ToolFunction(
                name = "list_shells",
                description = "List all configured shell servers with their names and descriptions.",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList())
            )),
            ToolDefinition(function = ToolFunction(
                name = "execute_shell_command",
                description = "Execute a shell command on a remote server and return the combined stdout and stderr output. Use this to run system commands, scripts, or interact with the command line. The command is sent to a configured remote shell server, not executed locally on the device.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "command" to ToolProperty("string", "The shell command to execute."),
                        "server" to serverProperty,
                        "timeout_ms" to ToolProperty("integer", "Timeout in milliseconds (optional, defaults to the device's configured timeout)."),
                        "workdir" to ToolProperty("string", "Working directory for the command (optional).")
                    ),
                    required = shellRequiredParams
                )
            ))
        )

        // File tools
        val fileServerProperty = if (ctx.shellDevices.size == 1) {
            ToolProperty("string", "The shell server name (optional, defaults to the only configured server).")
        } else {
            ToolProperty("string", "The shell server name. Available: $deviceNames.")
        }
        val fileRequired = if (ctx.shellDevices.size == 1) emptyList<String>() else listOf("server")

        val fileTools = listOf(
            ToolDefinition(function = ToolFunction(
                name = "file_read",
                description = "Read a file from a remote shell server. Returns the file content as text.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file on the remote server."),
                        "server" to fileServerProperty,
                        "offset" to ToolProperty("integer", "Byte offset to start reading from (optional, default 0)."),
                        "limit" to ToolProperty("integer", "Maximum bytes to read (optional, default 1MB).")
                    ),
                    required = listOf("path") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_write",
                description = "Write content to a file on a remote shell server. Creates parent directories as needed and overwrites existing files.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file on the remote server."),
                        "content" to ToolProperty("string", "Content to write to the file."),
                        "server" to fileServerProperty
                    ),
                    required = listOf("path", "content") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_edit",
                description = "Edit a file on a remote shell server by replacing old_string with new_string. The old_string must match exactly once in the file (or set replace_all to replace all occurrences).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Absolute path to the file on the remote server."),
                        "old_string" to ToolProperty("string", "The exact text to find and replace."),
                        "new_string" to ToolProperty("string", "The replacement text."),
                        "server" to fileServerProperty,
                        "replace_all" to ToolProperty("boolean", "Replace all occurrences instead of requiring a unique match (optional, default false).")
                    ),
                    required = listOf("path", "old_string", "new_string") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_glob",
                description = "List files on a remote shell server matching a glob pattern. Supports * and ** wildcards.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pattern" to ToolProperty("string", "Glob pattern (e.g. '*.go', '**/*.md')."),
                        "server" to fileServerProperty,
                        "path" to ToolProperty("string", "Base directory for the search (optional, defaults to current directory).")
                    ),
                    required = listOf("pattern") + fileRequired
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "file_grep",
                description = "Search for a regex pattern in files on a remote shell server. Returns matching lines with file paths and line numbers.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "pattern" to ToolProperty("string", "Regular expression pattern to search for."),
                        "server" to fileServerProperty,
                        "path" to ToolProperty("string", "File or directory to search in (optional, defaults to current directory)."),
                        "glob" to ToolProperty("string", "Filter files by glob pattern, e.g. '*.go' (optional).")
                    ),
                    required = listOf("pattern") + fileRequired
                )
            ))
        )

        return shellTools + fileTools
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        return when (name) {
            "list_shells" -> listShells(ctx)
            "execute_shell_command" -> executeShellCommand(arguments, ctx)
            "file_read" -> executeFileRead(arguments, ctx)
            "file_write" -> executeFileWrite(arguments, ctx)
            "file_edit" -> executeFileEdit(arguments, ctx)
            "file_glob" -> executeFileGlob(arguments, ctx)
            "file_grep" -> executeFileGrep(arguments, ctx)
            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in setOf(
        "list_shells", "execute_shell_command",
        "file_read", "file_write", "file_edit", "file_glob", "file_grep"
    )

    // --- Helpers ---

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> {
        return try {
            val argsStr = arguments.ifBlank { "{}" }
            Json.decodeFromString<Map<String, JsonElement>>(argsStr)
        } catch (_: Exception) { emptyMap() }
    }

    private fun toolError(type: String, message: String, server: String? = null): String {
        return buildJsonObject {
            put("type", type); put("error", "error"); put("message", message)
            if (server != null) put("server", server)
        }.toString()
    }

    private fun arg(args: Map<String, JsonElement>, key: String): String {
        return (args[key] as? JsonPrimitive)?.content ?: ""
    }

    private fun resolveShellDevice(serverName: String, ctx: GenerationContext): ShellDeviceConfig? {
        return if (serverName.isNotBlank()) {
            ctx.shellDevices.find { it.name.equals(serverName, ignoreCase = true) }
        } else if (ctx.shellDevices.size == 1) {
            ctx.shellDevices.first()
        } else null
    }

    private fun serverNotFoundMessage(serverName: String, ctx: GenerationContext): String {
        return if (ctx.shellDevices.size == 1) {
            "Unknown server: $serverName. Use \"${ctx.shellDevices[0].name}\" or omit the server parameter."
        } else {
            val names = ctx.shellDevices.joinToString(", ") { "\"${it.name}\"" }
            if (serverName.isBlank()) "Multiple servers available. Use list_shells to see them, then specify one: $names."
            else "Unknown server: $serverName. Available: $names."
        }
    }

    private suspend fun withShellClient(
        device: ShellDeviceConfig,
        block: suspend (ShellClient) -> String
    ): String {
        val serverUrl = device.serverUrl.trimEnd('/')
        if (serverUrl.isBlank()) {
            return buildJsonObject {
                put("error", "no_server_url")
                put("message", "Server \"${device.name}\" has no URL configured.")
            }.toString()
        }
        return try {
            val shellClient = ShellClient(
                serverUrl = serverUrl,
                apiKey = device.apiKey,
                cachedPublicKey = device.conchPublicKey
            )
            if (!shellClient.fetchPublicKey() && device.apiKey.isNotBlank()) {
                buildJsonObject {
                    put("error", "encryption_failed")
                    put("message", shellClient.lastError ?: "Failed to establish encrypted channel.")
                }.toString()
            } else {
                block(shellClient)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "request_failed")
                put("message", e.message ?: "Unknown error")
            }.toString()
        }
    }

    // --- Shell execution ---

    private suspend fun listShells(ctx: GenerationContext): String {
        val devices = ctx.shellDevices.map { d ->
            buildJsonObject {
                put("name", d.name.ifBlank { "Untitled" }); put("description", d.description)
            }
        }
        return buildJsonObject {
            put("type", "list_shells")
            putJsonArray("devices") { devices.forEach { add(it) } }
        }.toString()
    }

    private suspend fun executeShellCommand(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = try {
            Json.decodeFromString<Map<String, JsonElement>>(argsStr)
        } catch (e: Exception) {
            return buildJsonObject {
                put("type", "execute_shell_command"); put("error", "parse_error")
                put("message", "Failed to parse arguments: ${e.message}")
            }.toString()
        }
        fun arg(key: String): String = (args[key] as? JsonPrimitive)?.content ?: ""
        val command = arg("command")
        if (command.isBlank()) {
            return buildJsonObject { put("type", "execute_shell_command"); put("error", "no_command") }.toString()
        }
        val serverName = arg("server")
        val device = if (serverName.isNotBlank()) {
            ctx.shellDevices.find { it.name.equals(serverName, ignoreCase = true) }
        } else if (ctx.shellDevices.size == 1) {
            ctx.shellDevices.first()
        } else null
        if (device == null) {
            return if (ctx.shellDevices.size == 1) {
                buildJsonObject {
                    put("type", "execute_shell_command"); put("error", "server_not_found")
                    put("message", "Unknown server: $serverName. Use \"${ctx.shellDevices[0].name}\" or omit the server parameter.")
                }.toString()
            } else {
                val names = ctx.shellDevices.joinToString(", ") { "\"${it.name}\"" }
                buildJsonObject {
                    put("type", "execute_shell_command"); put("error", "server_not_found")
                    put("message", if (serverName.isBlank()) "Multiple servers available. Use list_shells to see them, then specify one: $names." else "Unknown server: $serverName. Available: $names.")
                }.toString()
            }
        }
        val timeoutMs = (arg("timeout_ms").toIntOrNull() ?: device.timeout * 1000).coerceIn(1000, 120000)
        val workdir = arg("workdir")
        val serverUrl = device.serverUrl.trimEnd('/')
        if (serverUrl.isBlank()) {
            return buildJsonObject {
                put("type", "execute_shell_command"); put("error", "no_server_url")
                put("message", "Server \"${device.name}\" has no URL configured.")
            }.toString()
        }
        return try {
            val shellClient = ShellClient(
                serverUrl = serverUrl, apiKey = device.apiKey, cachedPublicKey = device.conchPublicKey
            )
            if (!shellClient.fetchPublicKey() && device.apiKey.isNotBlank()) {
                return buildJsonObject {
                    put("type", "execute_shell_command"); put("error", "encryption_failed")
                    put("message", shellClient.lastError ?: "Failed to establish encrypted channel.")
                }.toString()
            }
            val prepared = shellClient.prepareRequest(command, timeoutMs, workdir)
            val handle = HttpClient.streamPost("${prepared.serverUrl}/execute", prepared.body, prepared.headers)
            try {
                val output = StringBuilder()
                var exitCode: Int? = null
                var errorMessage: String? = null
                var currentEvent: String? = null
                val aesKey = shellClient.getSessionKey()
                while (true) {
                    val line = handle.readLine() ?: break
                    when {
                        line.startsWith("event: ") -> { currentEvent = line.substring(7).trim() }
                        line.startsWith("data: ") -> {
                            var dataStr = line.substring(6).trim()
                            if (aesKey != null) {
                                try { dataStr = shellClient.decryptSseData(dataStr) }
                                catch (e: Exception) {
                                    DebugLog.e("AgoraAPI", "SSE decryption failed: ${e.message}", e)
                                    continue
                                }
                            }
                            val dataJson = try { Json.parseToJsonElement(dataStr).jsonObject } catch (_: Exception) { null } ?: continue
                            when (currentEvent) {
                                "line" -> {
                                    val text = (dataJson["line"] as? JsonPrimitive)?.content
                                    if (text != null) output.append(text).append('\n')
                                }
                                "result" -> {
                                    exitCode = (dataJson["exit_code"] as? JsonPrimitive)?.content?.toIntOrNull()
                                }
                                "error" -> {
                                    errorMessage = (dataJson["message"] as? JsonPrimitive)?.content
                                }
                            }
                        }
                    }
                }
                if (errorMessage != null) {
                    buildJsonObject {
                        put("type", "execute_shell_command"); put("server", device.name)
                        put("command", command); put("error", "execution_error")
                        put("message", errorMessage); put("output", output.toString().trimEnd())
                    }.toString()
                } else {
                    buildJsonObject {
                        put("type", "execute_shell_command"); put("server", device.name)
                        put("command", command); put("exit_code", exitCode ?: -1)
                        put("output", output.toString().trimEnd())
                    }.toString()
                }
            } finally { handle.close() }
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "execute_shell_command"); put("server", device.name)
                put("command", command); put("error", "request_failed")
                put("message", e.message ?: "Unknown error")
            }.toString()
        }
    }

    // --- File tools ---

    private suspend fun executeFileRead(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return toolError("file_read", "path is required")
        val serverName = arg(args, "server")
        val device = resolveShellDevice(serverName, ctx) ?: return toolError("file_read", serverNotFoundMessage(serverName, ctx), serverName)
        val offset = arg(args, "offset").toLongOrNull() ?: 0L
        val limit = arg(args, "limit").toLongOrNull() ?: 0L
        return withShellClient(device) { client ->
            val result = client.fileRead(path, offset, limit)
            if (result.error != null) toolError("file_read", result.error, device.name)
            else buildJsonObject {
                put("type", "file_read"); put("server", device.name); put("path", path)
                put("content", result.content); put("lines", result.lines)
            }.toString()
        }
    }

    private suspend fun executeFileWrite(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return toolError("file_write", "path is required")
        val content = arg(args, "content")
        if (content.isBlank()) return toolError("file_write", "content is required")
        val serverName = arg(args, "server")
        val device = resolveShellDevice(serverName, ctx) ?: return toolError("file_write", serverNotFoundMessage(serverName, ctx), serverName)
        return withShellClient(device) { client ->
            val error = client.fileWrite(path, content)
            if (error != null) toolError("file_write", error, device.name)
            else buildJsonObject {
                put("type", "file_write"); put("server", device.name)
                put("path", path); put("ok", true)
            }.toString()
        }
    }

    private suspend fun executeFileEdit(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val path = arg(args, "path")
        if (path.isBlank()) return toolError("file_edit", "path is required")
        val oldStr = arg(args, "old_string")
        if (oldStr.isBlank()) return toolError("file_edit", "old_string is required")
        val newStr = arg(args, "new_string")
        val replaceAll = arg(args, "replace_all").equals("true", ignoreCase = true)
        val serverName = arg(args, "server")
        val device = resolveShellDevice(serverName, ctx) ?: return toolError("file_edit", serverNotFoundMessage(serverName, ctx), serverName)
        return withShellClient(device) { client ->
            val result = client.fileRead(path, 0, 0)
            if (result.error != null) return@withShellClient toolError("file_edit", "read error: ${result.error}", device.name)
            val content = result.content
            val count = content.split(oldStr).size - 1
            if (count == 0) toolError("file_edit", "old_string not found in file", device.name)
            else if (count > 1 && !replaceAll) toolError("file_edit", "Found $count matches of old_string. Use replace_all=true to replace all, or provide more context to make it unique.", device.name)
            else {
                val replaced = content.replace(oldStr, newStr)
                val writeError = client.fileWrite(path, replaced)
                if (writeError != null) toolError("file_edit", "write error: $writeError", device.name)
                else buildJsonObject {
                    put("type", "file_edit"); put("server", device.name)
                    put("path", path); put("replaced", count)
                }.toString()
            }
        }
    }

    private suspend fun executeFileGlob(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return toolError("file_glob", "pattern is required")
        val serverName = arg(args, "server")
        val device = resolveShellDevice(serverName, ctx) ?: return toolError("file_glob", serverNotFoundMessage(serverName, ctx), serverName)
        val basePath = arg(args, "path")
        return withShellClient(device) { client ->
            val result = client.fileGlob(pattern, basePath)
            result.fold(
                onSuccess = { files -> buildJsonObject {
                    put("type", "file_glob"); put("server", device.name); put("pattern", pattern)
                    putJsonArray("files") { files.forEach { add(JsonPrimitive(it)) } }
                }.toString() },
                onFailure = { e -> toolError("file_glob", e.message ?: "Unknown error", device.name) }
            )
        }
    }

    private suspend fun executeFileGrep(arguments: String, ctx: GenerationContext): String {
        val args = parseToolArgs(arguments)
        val pattern = arg(args, "pattern")
        if (pattern.isBlank()) return toolError("file_grep", "pattern is required")
        val serverName = arg(args, "server")
        val device = resolveShellDevice(serverName, ctx) ?: return toolError("file_grep", serverNotFoundMessage(serverName, ctx), serverName)
        val basePath = arg(args, "path")
        val fileGlob = arg(args, "glob")
        return withShellClient(device) { client ->
            val result = client.fileGrep(pattern, basePath, fileGlob)
            result.fold(
                onSuccess = { matches -> buildJsonObject {
                    put("type", "file_grep"); put("server", device.name); put("pattern", pattern)
                    putJsonArray("matches") { matches.forEach { m -> add(buildJsonObject {
                        put("path", m.path); put("line", m.line); put("content", m.content)
                    }) } }
                }.toString() },
                onFailure = { e -> toolError("file_grep", e.message ?: "Unknown error", device.name) }
            )
        }
    }
}
