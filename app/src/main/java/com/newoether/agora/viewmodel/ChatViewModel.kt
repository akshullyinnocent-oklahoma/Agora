package com.newoether.agora.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.api.*
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import androidx.compose.foundation.lazy.LazyListState

class ChatViewModel(
    application: Application,
    private val settingsManager: SettingsManager,
    private val chatDao: ChatDao
) : AndroidViewModel(application) {
    
    val listState = LazyListState()
    val messageHeights = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    private val providers = mapOf(
        "Google" to GeminiProvider(),
        "OpenAI" to OpenAiProvider(),
        "Anthropic" to AnthropicProvider(),
        "DeepSeek" to DeepSeekProvider(),
        "Qwen" to OpenAiProvider(), // Qwen is OpenAI compatible
        "Ollama" to OllamaProvider(),
        "Open Router" to OpenAiProvider() // Open Router is OpenAI compatible
    )

    private fun getActiveProvider(): LlmProvider {
        return providers[provider.value] ?: GeminiProvider()
    }

    private val _scrollToMessage = MutableSharedFlow<String?>(replay = 0)
    val scrollToMessage = _scrollToMessage.asSharedFlow()

    fun triggerScrollToMessage(messageId: String? = null) {
        viewModelScope.launch {
            _scrollToMessage.emit(messageId)
        }
    }

        val provider = settingsManager.provider.stateIn(viewModelScope, SharingStarted.Eagerly, "Google")
        val selectedModel = settingsManager.selectedModel.stateIn(viewModelScope, SharingStarted.Eagerly, "models/gemini-1.5-flash")
        private val _currentActiveModel = MutableStateFlow<String?>(null)
        val currentActiveModel = kotlinx.coroutines.flow.combine(_currentActiveModel, selectedModel) { active, default ->
            active ?: default
        }.stateIn(viewModelScope, SharingStarted.Eagerly, "models/gemini-1.5-flash")
    
        val availableModels = settingsManager.availableModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        val enabledModels = settingsManager.enabledModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
        val modelAliases = settingsManager.modelAliases.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    
            val apiKeys = settingsManager.apiKeys.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
            val activeApiKeyIds = settingsManager.activeApiKeyIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
            val systemPrompts = settingsManager.systemPrompts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
            val activeSystemPromptId = settingsManager.activeSystemPromptId.stateIn(viewModelScope, SharingStarted.Eagerly, null)
        
        val maxContextWindow = settingsManager.maxContextWindow.stateIn(viewModelScope, SharingStarted.Eagerly, 20)
        val visualizeContextRollout = settingsManager.visualizeContextRollout.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        val codeExecutionEnabled = settingsManager.codeExecutionEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        val googleSearchEnabled = settingsManager.googleSearchEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        val thinkingEnabled = settingsManager.thinkingEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
        val providerBaseUrls = settingsManager.providerBaseUrls.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    
        val conversations: StateFlow<List<ChatConversation>> = chatDao.getAllConversations()
            .map { entities ->
                entities.map { ChatConversation(id = it.id, title = it.title, systemPromptId = it.systemPromptId, modelId = it.modelId) }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _allMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val allMessages: StateFlow<List<ChatMessage>> = _allMessages.asStateFlow()

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    private val _selectedChildren = MutableStateFlow<Map<String?, String>>(emptyMap())

    private var generationJob: Job? = null

    val messages: StateFlow<List<ChatMessage>> = combine(
        _allMessages,
        _streamingMessage,
        _selectedChildren
    ) { allMsgs, streaming, selectedChildren ->
        val path = mutableListOf<ChatMessage>()
        var currentParentId: String? = null
        
        while (true) {
            val siblings = allMsgs.filter { it.parentId == currentParentId }
                .sortedBy { it.timestamp }
            
            if (siblings.isEmpty()) break
            
            val selectedId = selectedChildren[currentParentId]
            var selectedMessage = siblings.find { it.id == selectedId } ?: siblings.last()
            
            if (streaming != null && selectedMessage.id == streaming.id) {
                selectedMessage = streaming
            }
            
            path.add(selectedMessage)
            currentParentId = selectedMessage.id
        }

        if (streaming != null && path.none { it.id == streaming.id }) {
            if (streaming.parentId == path.lastOrNull()?.id || (streaming.parentId == null && path.isEmpty())) {
                path.add(streaming)
            }
        }
        path
    }.distinctUntilChanged()
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalTokens: StateFlow<Int> = _allMessages.map { list ->
        list.sumOf { it.tokenCount }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    private var switchingJob: Job? = null

    fun setSwitching(switching: Boolean) {
        _isSwitching.value = switching
    }

    fun clearMessageHeights() {
        messageHeights.clear()
    }

    private val _isNewChatMode = MutableStateFlow(true)
    val isNewChatMode: StateFlow<Boolean> = _isNewChatMode.asStateFlow()

    private val _isTransitioningToNewChat = MutableStateFlow(false)
    val isTransitioningToNewChat: StateFlow<Boolean> = _isTransitioningToNewChat.asStateFlow()

    private val _branchSwitchTrigger = MutableStateFlow<String?>(null)
    val branchSwitchTrigger: StateFlow<String?> = _branchSwitchTrigger.asStateFlow()

    init {
        viewModelScope.launch {
            _currentConversationId.collectLatest { id ->
                if (id != null) {
                    // Fix stuck sending states when loading conversation
                    val stuckMessages = chatDao.getMessagesForConversation(id).first()
                        .filter { it.status == MessageStatus.SENDING || it.status == MessageStatus.THINKING }
                    
                    stuckMessages.forEach { msg ->
                        chatDao.upsertMessage(msg.copy(status = MessageStatus.STOPPED))
                    }

                    // Restore selected branches
                    val conversation = chatDao.getConversation(id)
                    if (conversation?.selectedBranchesJson != null) {
                        try {
                            val map = Json.decodeFromString<Map<String, String>>(conversation.selectedBranchesJson)
                            val decodedMap = map.mapKeys { if (it.key == "null") null else it.key }
                            _selectedChildren.value = decodedMap
                        } catch (e: Exception) {
                            _selectedChildren.value = emptyMap()
                        }
                    } else {
                        _selectedChildren.value = emptyMap()
                    }

                    chatDao.getMessagesForConversation(id).collect { entities ->
                        _allMessages.value = entities.map { 
                            ChatMessage(
                                id = it.id, 
                                parentId = it.parentId,
                                text = it.text, 
                                images = it.images,
                                thoughts = it.thoughts,
                                tokenCount = it.tokenCount,
                                status = it.status,
                                participant = it.participant, 
                                timestamp = it.timestamp,
                                thoughtTimeMs = it.thoughtTimeMs,
                                modelName = it.modelName
                            )
                        }
                    }
                } else {
                    _allMessages.value = emptyList()
                    _selectedChildren.value = emptyMap()
                }
            }
        }
        
        viewModelScope.launch {
            _selectedChildren.collect { childrenMap ->
                val id = _currentConversationId.value
                if (id != null) {
                    val conversation = chatDao.getConversation(id)
                    if (conversation != null) {
                        val stringKeyMap = childrenMap.mapKeys { it.key ?: "null" }
                        val json = Json.encodeToString(stringKeyMap)
                        if (conversation.selectedBranchesJson != json) {
                            chatDao.upsertConversation(conversation.copy(selectedBranchesJson = json))
                        }
                    }
                }
            }
        }
    }

    // Settings logic
    fun setProvider(p: String) { viewModelScope.launch { settingsManager.saveProvider(p) } }
    fun setSelectedModel(model: String) { viewModelScope.launch { settingsManager.saveSelectedModel(model) } }
    fun setEnabledModels(models: Set<String>) { 
        viewModelScope.launch { 
            settingsManager.saveEnabledModels(models) 
            if (!models.contains(selectedModel.value)) {
                settingsManager.saveSelectedModel(models.firstOrNull() ?: "")
            }
        } 
    }

    fun updateModelAlias(model: String, alias: String) {
        viewModelScope.launch {
            val currentAliases = modelAliases.value.toMutableMap()
            if (alias.isBlank()) {
                currentAliases.remove(model)
            } else {
                currentAliases[model] = alias
            }
            settingsManager.saveModelAliases(currentAliases)
        }
    }

    fun addApiKey(name: String, key: String, provider: String) {
        viewModelScope.launch {
            val entry = ApiKeyEntry(name = name, key = key, provider = provider)
            val newList = apiKeys.value + entry
            settingsManager.saveApiKeys(newList)
            settingsManager.setActiveApiKeyId(provider, entry.id)
        }
    }
    fun deleteApiKey(id: String) {
        viewModelScope.launch {
            val entry = apiKeys.value.find { it.id == id } ?: return@launch
            val provider = entry.provider
            val newList = apiKeys.value.filter { it.id != id }
            settingsManager.saveApiKeys(newList)
            if (activeApiKeyIds.value[provider] == id) {
                settingsManager.setActiveApiKeyId(provider, null)
            }
        }
    }
    fun updateApiKey(id: String, name: String, key: String) {
        viewModelScope.launch {
            val newList = apiKeys.value.map { if (it.id == id) it.copy(name = name, key = key) else it }
            settingsManager.saveApiKeys(newList)
        }
    }
    fun setActiveApiKey(provider: String, id: String) { viewModelScope.launch { settingsManager.setActiveApiKeyId(provider, id) } }

    fun addSystemPrompt(title: String, content: String) {
        viewModelScope.launch {
            val newList = systemPrompts.value + SystemPromptEntry(title = title, content = content)
            settingsManager.saveSystemPrompts(newList)
            if (activeSystemPromptId.value == null) settingsManager.setActiveSystemPromptId(newList.last().id)
        }
    }
    fun deleteSystemPrompt(id: String) {
        viewModelScope.launch {
            val newList = systemPrompts.value.filter { it.id != id }
            settingsManager.saveSystemPrompts(newList)
            if (activeSystemPromptId.value == id) settingsManager.setActiveSystemPromptId(newList.firstOrNull()?.id)
        }
    }
    fun updateSystemPrompt(id: String, title: String, content: String) {
        viewModelScope.launch {
            val newList = systemPrompts.value.map { if (it.id == id) it.copy(title = title, content = content) else it }
            settingsManager.saveSystemPrompts(newList)
        }
    }
    fun setActiveSystemPrompt(id: String) { viewModelScope.launch { settingsManager.setActiveSystemPromptId(id) } }
    fun setMaxContextWindow(window: Int) { viewModelScope.launch { settingsManager.saveMaxContextWindow(window) } }
    fun setVisualizeContextRollout(enabled: Boolean) { viewModelScope.launch { settingsManager.saveVisualizeContextRollout(enabled) } }
    fun setCodeExecutionEnabled(enabled: Boolean) { viewModelScope.launch { settingsManager.saveCodeExecutionEnabled(enabled) } }
    fun setGoogleSearchEnabled(enabled: Boolean) { viewModelScope.launch { settingsManager.saveGoogleSearchEnabled(enabled) } }
    fun setThinkingEnabled(enabled: Boolean) { viewModelScope.launch { settingsManager.saveThinkingEnabled(enabled) } }
    fun setProviderBaseUrl(provider: String, url: String) { viewModelScope.launch { settingsManager.saveProviderBaseUrl(provider, url) } }

    fun createNewChat() {
        switchingJob?.cancel()
        _isNewChatMode.value = true
        _isTransitioningToNewChat.value = true
        _isSwitching.value = true
        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200) // Allow overlay to fade in
            clearMessageHeights()
            _currentConversationId.value = null
            _currentActiveModel.value = null
            _allMessages.value = emptyList()
            _selectedChildren.value = emptyMap()
            _branchSwitchTrigger.value = null
            _isSwitching.value = false
            _isTransitioningToNewChat.value = false
        }
    }

    fun selectConversation(id: String) {
        if (_currentConversationId.value == id && !_isNewChatMode.value) return
        
        switchingJob?.cancel()
        _isTransitioningToNewChat.value = false
        _isSwitching.value = true
        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200) // Allow overlay to fade in
            _isNewChatMode.value = false
            clearMessageHeights()
            _branchSwitchTrigger.value = null
            _currentConversationId.value = id
            val conversation = chatDao.getConversation(id)
            _currentActiveModel.value = conversation?.modelId
            triggerScrollToMessage()
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            val existing = chatDao.getConversation(id)
            if (existing != null) {
                chatDao.upsertConversation(existing.copy(title = newTitle, lastUpdated = System.currentTimeMillis()))
            }
        }
    }

    fun setConversationSystemPrompt(id: String, promptId: String?) {
        viewModelScope.launch {
            val existing = chatDao.getConversation(id)
            if (existing != null) {
                chatDao.upsertConversation(existing.copy(systemPromptId = promptId, lastUpdated = System.currentTimeMillis()))
            }
        }
    }

    fun setActiveModel(model: String) {
        _currentActiveModel.value = model
        _currentConversationId.value?.let { id ->
            viewModelScope.launch {
                val existing = chatDao.getConversation(id)
                if (existing != null) {
                    chatDao.upsertConversation(existing.copy(modelId = model, lastUpdated = System.currentTimeMillis()))
                }
            }
        }
    }

    fun deleteConversation(id: String) {
        if (_currentConversationId.value == id) {
            stopGeneration()
        }
        viewModelScope.launch {
            chatDao.deleteConversation(id)
            if (_currentConversationId.value == id) createNewChat()
        }
    }
    
    fun stopGeneration() {
        generationJob?.cancel()
        _isLoading.value = false
        _streamingMessage.value = null
    }

    fun regenerate(messageId: String) {
        val currentId = _currentConversationId.value ?: return
        val currentProvider = provider.value
        val activeKeyId = activeApiKeyIds.value[currentProvider]
        val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key
        if (activeKey.isNullOrBlank()) return

        stopGeneration()
        generationJob = viewModelScope.launch {
            val messageToRegenerate = _allMessages.value.find { it.id == messageId } ?: return@launch
            val parentId = messageToRegenerate.parentId ?: return@launch
            val userMessage = _allMessages.value.find { it.id == parentId } ?: return@launch

            val isErrorOrStopped = messageToRegenerate.status == MessageStatus.ERROR || messageToRegenerate.status == MessageStatus.STOPPED
            val hasChildren = _allMessages.value.any { it.parentId == messageId }
            val isLatest = !hasChildren

            val modelMessageId: String
            val startTime = System.currentTimeMillis() + 1
            
                        if (isErrorOrStopped && isLatest) {
                            modelMessageId = messageId
                            chatDao.upsertMessage(MessageEntity(
                                id = modelMessageId, conversationId = currentId, parentId = parentId,
                                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                                modelName = currentActiveModel.value
                            ))
                            val newMap = _selectedChildren.value.toMutableMap()
                            newMap[parentId] = modelMessageId
                            _selectedChildren.value = newMap
                        } else {
                            modelMessageId = UUID.randomUUID().toString()
                            chatDao.upsertMessage(MessageEntity(
                                id = modelMessageId, conversationId = currentId, parentId = parentId,
                                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                                modelName = currentActiveModel.value
                            ))
            
                            val newMap = _selectedChildren.value.toMutableMap()
                            newMap[parentId] = modelMessageId
                            _selectedChildren.value = newMap
            }
            
            generateResponse(currentId, userMessage.text, modelMessageId, startTime)
        }
    }

    fun switchBranch(parentId: String?, direction: Int) {
        if (_isLoading.value) return
        val siblings = _allMessages.value.filter { it.parentId == parentId }.sortedBy { it.timestamp }
        if (siblings.size < 2) return
        val currentId = _selectedChildren.value[parentId] ?: siblings.last().id
        val currentIndex = siblings.indexOfFirst { it.id == currentId }
        val newIndex = (currentIndex + direction).coerceIn(0, siblings.size - 1)
        
        switchingJob?.cancel()
        _isSwitching.value = true
        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200) // Allow overlay to fade in
            val newMap = _selectedChildren.value.toMutableMap()
            val targetMessage = siblings[newIndex]
            newMap[parentId] = targetMessage.id
            _selectedChildren.value = newMap
            
            _branchSwitchTrigger.value = targetMessage.id
            triggerScrollToMessage(targetMessage.id)
        }
    }

    fun editMessage(messageId: String, newText: String) {
        val currentId = _currentConversationId.value ?: return
        val currentProvider = provider.value
        val activeKeyId = activeApiKeyIds.value[currentProvider]
        val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key
        if (activeKey.isNullOrBlank()) return

        stopGeneration()
        generationJob = viewModelScope.launch {
            val messageToEdit = _allMessages.value.find { it.id == messageId } ?: return@launch
            val newUserMessageId = UUID.randomUUID().toString()
            chatDao.upsertMessage(MessageEntity(
                id = newUserMessageId, conversationId = currentId, parentId = messageToEdit.parentId,
                text = newText, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis()
            ))
            val newMap = _selectedChildren.value.toMutableMap()
            newMap[messageToEdit.parentId] = newUserMessageId
            _selectedChildren.value = newMap
            val modelMessageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis() + 1
            chatDao.upsertMessage(MessageEntity(
                id = modelMessageId, conversationId = currentId, parentId = newUserMessageId,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                modelName = currentActiveModel.value
            ))
            generateResponse(currentId, newText, modelMessageId, startTime)
        }
    }

    private suspend fun processImages(uris: List<String>): List<String> {
        return withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            uris.mapNotNull { uriString ->
                try {
                    val uri = android.net.Uri.parse(uriString)
                    app.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
                        
                        var scale = 1
                        while (options.outWidth / scale / 2 >= 1024 && options.outHeight / scale / 2 >= 1024) {
                            scale *= 2
                        }
                        
                        val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
                        app.contentResolver.openInputStream(uri)?.use { stream2 ->
                            val bitmap = android.graphics.BitmapFactory.decodeStream(stream2, null, decodeOptions)
                            if (bitmap != null) {
                                val file = File(app.filesDir, "img_${UUID.randomUUID()}.jpg")
                                file.outputStream().use { out ->
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                }
                                file.absolutePath
                            } else null
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    fun sendMessage(text: String, images: List<String> = emptyList()) {
        val currentProvider = provider.value
        val activeKeyId = activeApiKeyIds.value[currentProvider]
        val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key
        if (activeKey.isNullOrBlank()) {
            _allMessages.value = _allMessages.value + ChatMessage(text = "Please set API Key first!", participant = Participant.ERROR)
            return
        }
        stopGeneration()
        generationJob = viewModelScope.launch {
            val processedImages = if (images.isNotEmpty()) processImages(images) else emptyList()
            var currentId = _currentConversationId.value
            if (_isNewChatMode.value) {
                val newId = UUID.randomUUID().toString()
                val title = if (text.length > 20) text.take(20) + "..." else text.ifBlank { "New Chat" }
                chatDao.upsertConversation(ChatEntity(id = newId, title = title, modelId = currentActiveModel.value))
                _currentConversationId.value = newId
                _isNewChatMode.value = false
                currentId = newId
            }
            val currentPath = messages.value
            val lastMessageId = currentPath.lastOrNull()?.id
            val userMessageId = UUID.randomUUID().toString()
            chatDao.upsertMessage(MessageEntity(
                id = userMessageId, conversationId = currentId!!, parentId = lastMessageId,
                text = text, images = processedImages, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis()
            ))
            val modelMessageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis() + 1
            chatDao.upsertMessage(MessageEntity(
                id = modelMessageId, conversationId = currentId, parentId = userMessageId,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                modelName = currentActiveModel.value
            ))
            triggerScrollToMessage(userMessageId)
            generateResponse(currentId, text, modelMessageId, startTime)
        }
    }

    private suspend fun generateResponse(currentId: String, text: String, modelMessageId: String, startTime: Long) {
        val currentProvider = provider.value
        val activeKeyId = activeApiKeyIds.value[currentProvider]
        val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key ?: return
        _isLoading.value = true
        _streamingMessage.value = null
        var totalText = ""
        var totalThoughts = ""
        var totalTokenCount = 0
        var totalThoughtTimeMs: Long? = null
        var currentStatus = MessageStatus.SENDING
        val placeholder = chatDao.getMessagesForConversation(currentId).first().find { it.id == modelMessageId }
        val parentId = placeholder?.parentId

        try {
            val dbMessages = chatDao.getMessagesForConversation(currentId).first()
            val pathEntities = mutableListOf<MessageEntity>()
            var currId: String? = parentId
            while (currId != null) {
                val msg = dbMessages.find { it.id == currId } ?: break
                pathEntities.add(0, msg)
                currId = msg.parentId
            }
            val currentPath = pathEntities.map { 
                ChatMessage(id = it.id, parentId = it.parentId, text = it.text, images = it.images, thoughts = it.thoughts, tokenCount = it.tokenCount, status = it.status, participant = it.participant, timestamp = it.timestamp, thoughtTimeMs = it.thoughtTimeMs) 
            }.filter { it.participant != Participant.ERROR }
            
            val conversation = chatDao.getConversation(currentId)
            val targetPromptId = conversation?.systemPromptId ?: activeSystemPromptId.value
            val activePrompt = systemPrompts.value.find { it.id == targetPromptId }?.content
            
            val config = ProviderConfig(
                apiKey = activeKey,
                modelId = currentActiveModel.value ?: "",
                systemPrompt = activePrompt,
                maxContextWindow = maxContextWindow.value,
                codeExecutionEnabled = codeExecutionEnabled.value,
                googleSearchEnabled = googleSearchEnabled.value,
                thinkingEnabled = thinkingEnabled.value,
                baseUrl = providerBaseUrls.value[provider.value]
            )

            getActiveProvider().generateResponse(currentPath, config).collect { event ->
                when (event) {
                    is StreamEvent.TextChunk -> {
                        totalText += event.text
                        currentStatus = MessageStatus.SENDING
                    }
                    is StreamEvent.ThoughtChunk -> {
                        if (totalText.isEmpty()) {
                            currentStatus = MessageStatus.THINKING
                            if (totalThoughtTimeMs == null) totalThoughtTimeMs = System.currentTimeMillis() - startTime
                            if (totalThoughts.isEmpty()) {
                                totalThoughts = "Model is reasoning..."
                            }
                        }
                        if (event.thought.isNotEmpty()) {
                            if (totalThoughts == "Model is reasoning...") {
                                totalThoughts = event.thought
                            } else {
                                totalThoughts += event.thought
                            }
                        }
                    }
                    is StreamEvent.UsageUpdate -> {
                        if (event.tokenCount > 0) totalTokenCount = event.tokenCount
                        if (totalText.isEmpty() && event.thoughtsTokenCount > 0) {
                            currentStatus = MessageStatus.THINKING
                            if (totalThoughtTimeMs == null) totalThoughtTimeMs = System.currentTimeMillis() - startTime
                            if (totalThoughts.isEmpty()) {
                                totalThoughts = "Model is reasoning..."
                            }
                        }
                    }
                    is StreamEvent.Error -> {
                        totalText = event.message
                        currentStatus = MessageStatus.ERROR
                    }
                }
                
                _streamingMessage.value = ChatMessage(
                    id = modelMessageId,
                    parentId = parentId,
                    text = totalText,
                    thoughts = totalThoughts.ifBlank { null },
                    tokenCount = totalTokenCount,
                    status = currentStatus,
                    participant = Participant.MODEL,
                    timestamp = startTime,
                    thoughtTimeMs = totalThoughtTimeMs,
                    modelName = currentActiveModel.value
                )
            }
            
            if (currentStatus != MessageStatus.ERROR) {
                currentStatus = if (totalText.isNotEmpty() || totalThoughts.isNotEmpty()) MessageStatus.SUCCESS else MessageStatus.ERROR
            }
        } catch (e: CancellationException) {
            currentStatus = MessageStatus.STOPPED
            throw e
        } catch (e: Exception) {
            val isCancelled = generationJob?.isCancelled == true
            currentStatus = if (isCancelled) MessageStatus.STOPPED else MessageStatus.ERROR
            if (!isCancelled) {
                totalText = "Error: ${e.localizedMessage ?: "An unexpected error occurred."}"
            }
        } finally {
            withContext(NonCancellable) {
                // Only persist if the conversation still exists to avoid FK constraint crashes on deletion
                val conversationExists = chatDao.getConversation(currentId) != null
                if (conversationExists) {
                    val finalStreamingMsg = _streamingMessage.value
                    if (finalStreamingMsg != null) {
                        chatDao.upsertMessage(MessageEntity(id = finalStreamingMsg.id, conversationId = currentId, parentId = finalStreamingMsg.parentId, text = finalStreamingMsg.text, thoughts = finalStreamingMsg.thoughts, tokenCount = finalStreamingMsg.tokenCount, status = currentStatus, participant = finalStreamingMsg.participant, timestamp = finalStreamingMsg.timestamp, thoughtTimeMs = finalStreamingMsg.thoughtTimeMs, modelName = finalStreamingMsg.modelName))
                    } else {
                        chatDao.upsertMessage(MessageEntity(id = modelMessageId, conversationId = currentId, parentId = parentId, text = totalText, thoughts = totalThoughts.ifBlank { null }, tokenCount = totalTokenCount, status = currentStatus, participant = Participant.MODEL, timestamp = startTime, thoughtTimeMs = totalThoughtTimeMs, modelName = currentActiveModel.value))
                    }
                }
                _isLoading.value = false
                _streamingMessage.value = null
            }
        }
    }

    fun fetchAvailableModels() {
        val currentProvider = provider.value
        val activeKeyId = activeApiKeyIds.value[currentProvider]
        val activeKey = apiKeys.value.find { it.id == activeKeyId }?.key
        if (activeKey.isNullOrBlank()) return
        val currentBaseUrl = providerBaseUrls.value[currentProvider]
        viewModelScope.launch {
            try {
                val newModels = getActiveProvider().fetchModels(activeKey, currentBaseUrl)
                if (newModels.isNotEmpty()) {
                    settingsManager.saveAvailableModels(newModels)
                    val newEnabled = enabledModels.value.intersect(newModels.toSet())
                    settingsManager.saveEnabledModels(newEnabled)
                    if (!newEnabled.contains(selectedModel.value)) settingsManager.saveSelectedModel(newEnabled.firstOrNull() ?: "")
                }
            } catch (e: Exception) { }
        }
    }
}
