package com.newoether.agora.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "settings")

@Serializable
data class ApiKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val key: String,
    val provider: String = "Google"
)

@Serializable
data class SystemPromptEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String
)

class SettingsManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val PROVIDER = stringPreferencesKey("provider")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val AVAILABLE_MODELS_JSON = stringPreferencesKey("available_models_json")
        val ENABLED_MODELS = stringSetPreferencesKey("enabled_models")
        
        val API_KEYS_JSON = stringPreferencesKey("api_keys_json")
        val ACTIVE_API_KEY_IDS_JSON = stringPreferencesKey("active_api_key_ids_json")
        
        val SYSTEM_PROMPTS_JSON = stringPreferencesKey("system_prompts_json")
        val ACTIVE_SYSTEM_PROMPT_ID = stringPreferencesKey("active_system_prompt_id")
        val MODEL_ALIASES_JSON = stringPreferencesKey("model_aliases_json")
        val MAX_CONTEXT_WINDOW = stringPreferencesKey("max_context_window")
        val VISUALIZE_CONTEXT_ROLLOUT = booleanPreferencesKey("visualize_context_rollout")
        val CODE_EXECUTION_ENABLED = booleanPreferencesKey("code_execution_enabled")
        val GOOGLE_SEARCH_ENABLED = booleanPreferencesKey("google_search_enabled")
        val THINKING_ENABLED = booleanPreferencesKey("thinking_enabled")
        val PROVIDER_BASE_URLS = stringPreferencesKey("provider_base_urls")
    }

    val provider: Flow<String> = context.dataStore.data.map { it[PROVIDER] ?: "Google" }
    val selectedModel: Flow<String> = context.dataStore.data.map { it[SELECTED_MODEL] ?: "models/gemini-1.5-flash" }
    
    val providerBaseUrls: Flow<Map<String, String>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[PROVIDER_BASE_URLS] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val availableModels: Flow<Map<String, List<String>>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[AVAILABLE_MODELS_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, List<String>>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val enabledModels: Flow<Set<String>> = context.dataStore.data.map { it[ENABLED_MODELS] ?: emptySet() }

    val modelAliases: Flow<Map<String, String>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[MODEL_ALIASES_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val apiKeys: Flow<List<ApiKeyEntry>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[API_KEYS_JSON] ?: "[]"
        try { json.decodeFromString<List<ApiKeyEntry>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    
    val activeApiKeyIds: Flow<Map<String, String>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[ACTIVE_API_KEY_IDS_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val systemPrompts: Flow<List<SystemPromptEntry>> = context.dataStore.data.map { pref ->
        val jsonStr = pref[SYSTEM_PROMPTS_JSON] ?: "[]"
        try { json.decodeFromString<List<SystemPromptEntry>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    
    val activeSystemPromptId: Flow<String?> = context.dataStore.data.map { it[ACTIVE_SYSTEM_PROMPT_ID] }

    val maxContextWindow: Flow<Int> = context.dataStore.data.map { it[MAX_CONTEXT_WINDOW]?.toIntOrNull() ?: 20 }
    val visualizeContextRollout: Flow<Boolean> = context.dataStore.data.map { it[VISUALIZE_CONTEXT_ROLLOUT] ?: false }
    val codeExecutionEnabled: Flow<Boolean> = context.dataStore.data.map { it[CODE_EXECUTION_ENABLED] ?: false }
    val googleSearchEnabled: Flow<Boolean> = context.dataStore.data.map { it[GOOGLE_SEARCH_ENABLED] ?: false }
    val thinkingEnabled: Flow<Boolean> = context.dataStore.data.map { it[THINKING_ENABLED] ?: true }

    suspend fun saveProvider(provider: String) {
        context.dataStore.edit { it[PROVIDER] = provider }
    }

    suspend fun saveProviderBaseUrl(provider: String, url: String) {
        val current = context.dataStore.data.map { it[PROVIDER_BASE_URLS] ?: "{}" }.first()
        val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
        map[provider] = url
        context.dataStore.edit { it[PROVIDER_BASE_URLS] = json.encodeToString(map) }
    }

    suspend fun saveSelectedModel(model: String) {
        context.dataStore.edit { it[SELECTED_MODEL] = model }
    }

    suspend fun saveAvailableModels(provider: String, models: List<String>) {
        val current = context.dataStore.data.map { it[AVAILABLE_MODELS_JSON] ?: "{}" }.first()
        val map = try { json.decodeFromString<MutableMap<String, List<String>>>(current) } catch (e: Exception) { mutableMapOf() }
        map[provider] = models
        context.dataStore.edit { it[AVAILABLE_MODELS_JSON] = json.encodeToString(map) }
    }

    suspend fun saveEnabledModels(models: Set<String>) {
        context.dataStore.edit { it[ENABLED_MODELS] = models }
    }

    suspend fun saveModelAliases(aliases: Map<String, String>) {
        context.dataStore.edit { it[MODEL_ALIASES_JSON] = json.encodeToString(aliases) }
    }

    suspend fun saveApiKeys(keys: List<ApiKeyEntry>) {
        context.dataStore.edit { it[API_KEYS_JSON] = json.encodeToString(keys) }
    }

    suspend fun setActiveApiKeyId(provider: String, id: String?) {
        val current = context.dataStore.data.map { it[ACTIVE_API_KEY_IDS_JSON] ?: "{}" }.first()
        val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
        if (id == null) map.remove(provider) else map[provider] = id
        context.dataStore.edit { it[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(map) }
    }

    suspend fun saveSystemPrompts(prompts: List<SystemPromptEntry>) {
        context.dataStore.edit { it[SYSTEM_PROMPTS_JSON] = json.encodeToString(prompts) }
    }

    suspend fun setActiveSystemPromptId(id: String?) {
        context.dataStore.edit { 
            if (id == null) it.remove(ACTIVE_SYSTEM_PROMPT_ID) else it[ACTIVE_SYSTEM_PROMPT_ID] = id 
        }
    }

    suspend fun saveMaxContextWindow(window: Int) {
        context.dataStore.edit { it[MAX_CONTEXT_WINDOW] = window.toString() }
    }

    suspend fun saveVisualizeContextRollout(enabled: Boolean) {
        context.dataStore.edit { it[VISUALIZE_CONTEXT_ROLLOUT] = enabled }
    }

    suspend fun saveCodeExecutionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[CODE_EXECUTION_ENABLED] = enabled }
    }

    suspend fun saveGoogleSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[GOOGLE_SEARCH_ENABLED] = enabled }
    }

    suspend fun saveThinkingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[THINKING_ENABLED] = enabled }
    }
}
