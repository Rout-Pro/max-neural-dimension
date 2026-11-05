package com.max.neuraldimension.features.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.max.neuraldimension.core.gl.RobotState
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.data.db.Conversation
import com.max.neuraldimension.data.db.Message
import com.max.neuraldimension.data.db.TaskItem
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import com.max.neuraldimension.domain.BackendClient
import com.max.neuraldimension.domain.AiException
import com.max.neuraldimension.domain.ChatTurn
import com.max.neuraldimension.features.voice.VoiceEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val settingsRepo = SettingsRepository(app)
    private val db = AppDb.get(app)
    private val backend = BackendClient()

    val settings: StateFlow<MaxSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, MaxSettings())

    val voice = VoiceEngine(app, viewModelScope)

    val conversations: StateFlow<List<Conversation>> =
        db.chatDao().conversations().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentConversationId = MutableStateFlow<Long?>(null)

    val messages: StateFlow<List<Message>> = currentConversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else db.chatDao().messages(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val tasks: StateFlow<List<TaskItem>> =
        db.taskDao().tasks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sending = MutableStateFlow(false)
    val connectionTest = MutableStateFlow<String?>(null)

    /** One-shot text queued from the home input, consumed by the chat screen. */
    val pendingChatText = MutableStateFlow<String?>(null)

    init {
        voice.init()
        viewModelScope.launch {
            settings.collect { s ->
                RobotState.assistantName = s.assistantName
                RobotState.intensity = s.animIntensity
                RobotState.reducedMotion = s.reducedMotion
                voice.languageTag = s.language
                voice.setRate(s.speechRate)
            }
        }
    }

    private suspend fun ensureConversation(firstText: String): Long {
        currentConversationId.value?.let { return it }
        val title = firstText.take(38).ifBlank { "New conversation" }
        val id = db.chatDao().insertConversation(Conversation(title = title))
        currentConversationId.value = id
        return id
    }

    fun send(text: String) {
        if (text.isBlank() || sending.value) return
        viewModelScope.launch { generateAndStore(text) }
    }

    /** Core exchange used by chat and voice: stores user message, streams assistant reply, returns it. */
    suspend fun generateAndStore(text: String): String {
        sending.value = true
        try {
            val s = settings.value
            val cid = ensureConversation(text)
            db.chatDao().insertMessage(Message(conversationId = cid, role = "user", text = text))
            if (!backend.isConfigured(s)) {
                db.chatDao().insertMessage(
                    Message(conversationId = cid, role = "assistant", text = "SETUP_REQUIRED::backend"))
                RobotState.mode = RobotState.Mode.CONFUSED
                viewModelScope.launch { delay(4000); if (RobotState.mode == RobotState.Mode.CONFUSED) RobotState.mode = RobotState.Mode.IDLE }
                return ""
            }
            RobotState.mode = RobotState.Mode.THINKING
            val history = db.chatDao().messages(cid).first()
                .filter { !it.text.startsWith("SETUP_REQUIRED::") && !it.text.startsWith("ERROR::") }
                .takeLast(24)
                .map { ChatTurn(it.role, it.text) }
            val aid = db.chatDao().insertMessage(Message(conversationId = cid, role = "assistant", text = ""))
            val sb = StringBuilder()
            var lastWrite = 0L
            try {
                backend.streamChat(s, history) { tok ->
                    sb.append(tok)
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastWrite > 250) {
                        lastWrite = now
                        viewModelScope.launch { db.chatDao().updateMessageText(aid, sb.toString()) }
                    }
                }
                db.chatDao().updateMessageText(aid, sb.toString())
                RobotState.mode = RobotState.Mode.IDLE
                return sb.toString()
            } catch (e: AiException) {
                val msg = "ERROR::" + (e.message ?: "provider error")
                db.chatDao().updateMessageText(aid, msg)
                flashError()
                return ""
            } catch (e: Exception) {
                val msg = "ERROR::" + (e.message ?: e.javaClass.simpleName)
                db.chatDao().updateMessageText(aid, msg)
                flashError()
                return ""
            }
        } finally {
            sending.value = false
        }
    }

    private fun flashError() {
        RobotState.mode = RobotState.Mode.ERROR
        viewModelScope.launch { delay(3500); if (RobotState.mode == RobotState.Mode.ERROR) RobotState.mode = RobotState.Mode.IDLE }
    }

    fun newConversation() { currentConversationId.value = null }

    fun selectConversation(id: Long) { currentConversationId.value = id }

    fun renameConversation(c: Conversation, title: String) {
        viewModelScope.launch { db.chatDao().updateConversation(c.copy(title = title)) }
    }

    fun deleteConversation(c: Conversation) {
        viewModelScope.launch {
            db.chatDao().deleteMessagesFor(c.id)
            db.chatDao().deleteConversation(c)
            if (currentConversationId.value == c.id) currentConversationId.value = null
        }
    }

    fun addTask(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { db.taskDao().insert(TaskItem(title = title.trim())) }
    }

    fun toggleTask(t: TaskItem) {
        viewModelScope.launch { db.taskDao().update(t.copy(done = !t.done)) }
    }

    fun deleteTask(t: TaskItem) {
        viewModelScope.launch { db.taskDao().delete(t) }
    }

    fun testConnection() {
        viewModelScope.launch {
            connectionTest.value = "Testing..."
            connectionTest.value = try {
                backend.testConnection(settings.value)
            } catch (e: Exception) {
                e.message ?: "Failed"
            }
        }
    }

    fun speak(text: String) = voice.speak(text)

    override fun onCleared() {
        voice.release()
        super.onCleared()
    }
}
