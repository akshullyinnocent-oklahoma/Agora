package com.newoether.agora.data.repository

import com.newoether.agora.data.MemoryManager

/**
 * Repository wrapping file-based MemoryManager.
 *
 * Provides memory CRUD with validation. Phase 2 creates the abstraction.
 * Typed result wrapping (Result<T>) is deferred to a later refinement phase
 * to avoid changing all callers at once.
 */
class MemoryRepository(
    private val memoryManager: MemoryManager
) {
    fun getActiveMemory(): String = memoryManager.getActiveMemory()

    fun updateActiveMemory(content: String, mode: String = "replace"): String =
        memoryManager.updateActiveMemory(content, mode)

    fun listFiles(): List<MemoryManager.MemoryFileInfo> = memoryManager.listFiles()

    fun readFile(name: String): String = memoryManager.readFile(name)

    fun createFile(name: String, content: String, description: String = ""): String =
        memoryManager.createFile(name, content, description)

    fun editFile(
        name: String,
        content: String? = null,
        newName: String? = null,
        description: String? = null,
        oldString: String? = null,
        newString: String? = null
    ): String = memoryManager.editFile(name, content, newName, description, oldString, newString)

    fun deleteFile(name: String): String = memoryManager.deleteFile(name)
}
