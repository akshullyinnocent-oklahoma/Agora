package com.newoether.agora.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDao

class ChatViewModelFactory(
    private val application: Application,
    private val settingsManager: SettingsManager,
    private val chatDao: ChatDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(application, settingsManager, chatDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
