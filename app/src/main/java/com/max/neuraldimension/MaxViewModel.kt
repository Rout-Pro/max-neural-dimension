package com.max.neuraldimension

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

data class ChatMessage(val text: String, val fromUser: Boolean)

class MaxViewModel : ViewModel() {
    val messages = mutableStateListOf<ChatMessage>()
    var endpoint: String = ""
        private set
    var mode: String = "Fast"
        private set

    fun addMessage(text: String) {
        messages.add(ChatMessage(text.trim(), true))
        // This starter intentionally does not fabricate an AI answer.
        // Connect ChatRepository to your authenticated backend before enabling live AI.
    }

    fun saveSettings(url: String, responseMode: String) {
        endpoint = url.trim()
        mode = responseMode
    }
}
