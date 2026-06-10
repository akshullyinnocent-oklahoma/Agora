package com.newoether.agora.viewmodel

import com.newoether.agora.api.LlmProvider
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Manages LLM provider instances and model list synchronization.
 * Designed to be used by ChatViewModel to reduce its size.
 */
class ModelSyncManager(
    private val settingsManager: SettingsManager,
    private val builtInProviders: Map<String, LlmProvider>,
    private val getProviders: () -> Map<String, LlmProvider>
) {
    private val _isSyncingModels = MutableStateFlow(false)
    val isSyncingModels: StateFlow<Boolean> = _isSyncingModels.asStateFlow()

    fun getProviderInstance(name: String): LlmProvider =
        getProviders()[name] ?: getProviders().values.first()

    fun getProviderForModel(
        modelId: String,
        availableModels: Map<String, List<String>>
    ): String {
        for ((provider, models) in availableModels) {
            if (modelId in models) return provider
        }
        return builtInProviders.keys.first()
    }

    suspend fun fetchAvailableModels(
        apiKeys: List<ApiKeyEntry>,
        activeApiKeyIds: Map<String, String?>,
        providerBaseUrls: Map<String, String>,
        customProviders: List<CustomProviderConfig>,
        isProviderConfigured: (String, String?) -> Boolean
    ): String {
        _isSyncingModels.value = true
        return try {
            val allProviders = getProviders()
            var changed = 0
            // Collect model lists from all configured providers
            for ((providerName, providerInstance) in allProviders) {
                val activeKeyId = activeApiKeyIds[providerName]
                if (!isProviderConfigured(providerName, activeKeyId)) continue
                val apiKey = apiKeys.find { it.id == activeKeyId }?.key ?: ""
                val baseUrl = providerBaseUrls[providerName]
                try {
                    val models = providerInstance.fetchModels(apiKey, baseUrl)
                    if (models.isNotEmpty()) {
                        settingsManager.saveAvailableModels(providerName, models)
                        changed++
                    }
                } catch (_: Exception) { /* skip failed providers */ }
            }
            "Synced $changed providers"
        } catch (e: Exception) {
            "Sync failed: ${e.message}"
        } finally {
            _isSyncingModels.value = false
        }
    }
}
