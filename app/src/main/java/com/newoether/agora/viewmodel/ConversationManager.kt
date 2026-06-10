package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Manages conversation CRUD, branch switching, and message tree traversal.
 * Designed to be used by ChatViewModel to reduce its size.
 *
 * Usage:
 *   val convMgr = ConversationManager(chatDao, scope)
 *   // Observe convMgr.messages, convMgr.allMessages, etc.
 *   // Call convMgr.createNewChat(), convMgr.selectConversation(id), etc.
 */
class ConversationManager(
    private val chatDao: ChatDao,
    private val scope: CoroutineScope
) {
    private val _allMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val allMessages: StateFlow<List<ChatMessage>> = _allMessages.asStateFlow()

    private val _selectedChildren = MutableStateFlow<Map<String?, String>>(emptyMap())
    val selectedChildren: StateFlow<Map<String?, String>> = _selectedChildren.asStateFlow()

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _isNewChatMode = MutableStateFlow(true)
    val isNewChatMode: StateFlow<Boolean> = _isNewChatMode.asStateFlow()

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    private val _isTransitioningToNewChat = MutableStateFlow(false)
    val isTransitioningToNewChat: StateFlow<Boolean> = _isTransitioningToNewChat.asStateFlow()

    private val _branchSwitchTrigger = MutableStateFlow<String?>(null)
    val branchSwitchTrigger: StateFlow<String?> = _branchSwitchTrigger.asStateFlow()

    private var switchingJob: kotlinx.coroutines.Job? = null

    /**
     * Computed visible message path — walks the conversation tree from root,
     * following selected branches and overlaying any streaming message.
     */
    fun messages(streamingMessage: StateFlow<ChatMessage?>): StateFlow<List<ChatMessage>> =
        combine(_allMessages, streamingMessage, _selectedChildren) { all, streaming, children ->
            ConversationUiState.resolvePath(all, streaming, children)
        }.distinctUntilChanged()
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun createNewChat() {
        switchingJob?.cancel()
        if (!_isNewChatMode.value) {
            // _pendingSystemPromptId handled by ChatViewModel
        }
        _isNewChatMode.value = true
        _isTransitioningToNewChat.value = true
        _isSwitching.value = true
        switchingJob = scope.launch {
            kotlinx.coroutines.delay(200)
            _currentConversationId.value = null
            _allMessages.value = emptyList()
            _selectedChildren.value = emptyMap()
            _branchSwitchTrigger.value = null
            _isSwitching.value = false
            _isTransitioningToNewChat.value = false
        }
    }

    suspend fun selectConversation(id: String) {
        switchingJob?.cancel()
        _isTransitioningToNewChat.value = false
        _isSwitching.value = true
        switchingJob = scope.launch {
            kotlinx.coroutines.delay(200)
            _isNewChatMode.value = false
            _branchSwitchTrigger.value = null
            _currentConversationId.value = id
        }
    }

    fun loadMessages(conversationId: String) {
        scope.launch {
            chatDao.getMessagesForConversation(conversationId).collectLatest { entities ->
            _allMessages.value = entities.map { entity ->
                ChatMessage(
                    id = entity.id,
                    parentId = entity.parentId,
                    text = entity.text,
                    images = entity.images,
                    thoughts = entity.thoughts,
                    thoughtTitle = entity.thoughtTitle,
                    tokenCount = entity.tokenCount,
                    status = entity.status,
                    participant = entity.participant,
                    timestamp = entity.timestamp,
                    thoughtTimeMs = entity.thoughtTimeMs,
                    modelName = entity.modelName,
                    attachmentMeta = entity.attachmentMeta?.let {
                        try { kotlinx.serialization.json.Json.decodeFromString<com.newoether.agora.model.AttachmentMeta>(it) }
                        catch (_: Exception) { null }
                    }
                )
            }
            }
        }
    }

    fun setSwitching(switching: Boolean) { _isSwitching.value = switching }

    fun switchBranch(parentId: String?, direction: Int) {
        val current = _selectedChildren.value.toMutableMap()
        val siblings = _allMessages.value.filter { it.parentId == parentId }
            .filter { !it.id.startsWith(com.newoether.agora.util.Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(com.newoether.agora.util.Constants.RESULT_MSG_PREFIX) }
            .sortedBy { it.timestamp }
        if (siblings.isEmpty()) return
        val currentId = current[parentId]
        val currentIndex = siblings.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: (siblings.size - 1)
        val newIndex = when {
            direction < 0 -> (currentIndex - 1).coerceAtLeast(0)
            direction > 0 -> (currentIndex + 1).coerceAtMost(siblings.size - 1)
            else -> currentIndex
        }
        current[parentId] = siblings[newIndex].id
        _selectedChildren.value = current
    }
}
