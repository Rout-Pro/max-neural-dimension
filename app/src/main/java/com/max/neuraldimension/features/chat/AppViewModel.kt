package com.max.neuraldimension.features.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.max.neuraldimension.core.gl.RobotState
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.features.assistant.effectiveAt
import com.max.neuraldimension.data.db.Conversation
import com.max.neuraldimension.data.db.Message
import com.max.neuraldimension.data.db.Expense
import com.max.neuraldimension.data.db.Habit
import com.max.neuraldimension.data.db.Note
import com.max.neuraldimension.data.db.Reminder
import com.max.neuraldimension.data.db.SecurityEvent
import com.max.neuraldimension.data.db.TaskItem
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import com.max.neuraldimension.domain.BackendClient
import com.max.neuraldimension.domain.AiException
import com.max.neuraldimension.domain.ChatTurn
import com.max.neuraldimension.domain.PinLock
import com.max.neuraldimension.domain.Backup
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
import java.util.concurrent.TimeUnit

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

    init {
        // Attachment hygiene: remove copied attachment files no message references anymore.
        // This covers incognito sessions killed mid-chat (process death skips toggle-off cleanup)
        // while preserving every file still referenced by a stored conversation.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val referenced = db.chatDao().allAttachmentPaths().filterNotNull().toSet()
                getApplication<Application>().filesDir.listFiles()
                    ?.filter { it.name.startsWith("att_") && it.absolutePath !in referenced }
                    ?.forEach { it.delete() }
            }
        }
    }

    /** Part 13: incognito - in-memory only, never written to Room. */
    val incognito = MutableStateFlow(false)
    val incognitoMessages = MutableStateFlow<List<Message>>(emptyList())

    fun toggleIncognito() {
        incognito.value = !incognito.value
        if (!incognito.value) {
            // Incognito leaves nothing behind: drop messages AND their attachment files.
            incognitoMessages.value.forEach { m -> m.attachmentPath?.let { runCatching { java.io.File(it).delete() } } }
            incognitoMessages.value = emptyList()
        }
    }

    /** One-shot text queued from the home input, consumed by the chat screen. */
    val pendingChatText = MutableStateFlow<String?>(null)

    // ----- Blueprint Part 19: app lock + security event history -----

    /** null = settings not loaded yet; true = locked (PIN set, not unlocked); false = unlocked / no PIN. */
    val lockState = MutableStateFlow<Boolean?>(null)
    val lockError = MutableStateFlow<String?>(null)

    val securityEvents: StateFlow<List<SecurityEvent>> =
        db.securityDao().events().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Gate content on the stored PIN state before first render; a PIN set
        // before process death still locks the next cold start.
        viewModelScope.launch {
            lockState.value = PinLock.isSet(settings.first().pinVerifier)
        }
    }

    /**
     * Attempt an unlock. Enforces the progressive lockout from the persisted
     * failure count; a successful attempt records nothing, a failed one
     * appends pin_failed to the security history.
     */
    fun unlock(pin: String) {
        val s = settings.value
        val waitMs = PinLock.backoffDelayMillis(s.pinFailCount) - (System.currentTimeMillis() - s.pinLastFailAt)
        if (waitMs > 0) {
            lockError.value = "Too many attempts. Try again in " + ((waitMs + 999) / 1000) + "s."
            return
        }
        if (PinLock.verify(pin, s.pinVerifier)) {
            lockState.value = false
            lockError.value = null
            if (s.pinFailCount != 0 || s.pinLastFailAt != 0L) {
                viewModelScope.launch { settingsRepo.update { it.copy(pinFailCount = 0, pinLastFailAt = 0L) } }
            }
        } else {
            lockError.value = "Wrong PIN"
            viewModelScope.launch {
                settingsRepo.update { it.copy(pinFailCount = it.pinFailCount + 1, pinLastFailAt = System.currentTimeMillis()) }
                db.securityDao().insert(SecurityEvent(type = "pin_failed"))
            }
        }
    }

    /** Set or replace the app PIN. No-op for invalid shapes; records pin_set / pin_changed. */
    fun setPin(pin: String) {
        if (!PinLock.isValidPin(pin)) return
        viewModelScope.launch {
            val had = PinLock.isSet(settings.value.pinVerifier)
            settingsRepo.update { it.copy(pinVerifier = PinLock.newVerifier(pin), pinFailCount = 0, pinLastFailAt = 0L) }
            db.securityDao().insert(SecurityEvent(type = if (had) "pin_changed" else "pin_set"))
        }
    }

    /** Remove the app PIN; requires the current PIN. Records pin_cleared on success. */
    fun clearPin(currentPin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (PinLock.verify(currentPin, settings.value.pinVerifier)) {
                settingsRepo.update { it.copy(pinVerifier = "", pinFailCount = 0, pinLastFailAt = 0L) }
                db.securityDao().insert(SecurityEvent(type = "pin_cleared"))
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }

    /**
     * Blueprint Part 19: user-controlled data deletion. Cancels pending
     * reminder/briefing work, wipes every Room table, deletes chat attachment
     * files, resets all settings to defaults (including the PIN), then records
     * a single data_wiped event so the security history restarts with the
     * wipe itself on record.
     */
    fun eraseAllData() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { wipeNow() }
    }

    /** The wipe itself, separated so tests can await it directly. */
    internal suspend fun wipeNow() {
        val app = getApplication<Application>()
        runCatching {
            val wm = androidx.work.WorkManager.getInstance(app)
            wm.cancelAllWorkByTag("nd-reminder")
            wm.cancelAllWorkByTag(com.max.neuraldimension.features.assistant.BriefingScheduler.TAG)
        }
        db.clearAllTables()
        app.filesDir.listFiles()
            ?.filter { it.name.startsWith("att_") }
            ?.forEach { it.delete() }
        settingsRepo.update { MaxSettings() }
        db.securityDao().insert(SecurityEvent(type = "data_wiped"))
        currentConversationId.value = null
        incognitoMessages.value = emptyList()
        incognito.value = false
        lockState.value = false
        lockError.value = null
    }

    /** One-line result of the last backup/restore action, shown in Settings. */
    val backupStatus = MutableStateFlow<String?>(null)

    /**
     * Blueprint Part 19: export reminders, memories and user-facing settings to
     * a JSON file in app-private storage (filesDir/backups). Auth state (backend
     * URL/token, PIN) is never exported. Reads stores fresh so no StateFlow
     * timing is involved. Returns the created file.
     */
    suspend fun exportBackup(): java.io.File {
        val cur = settingsRepo.settings.first()
        val payload = Backup.Payload(
            exportedAt = System.currentTimeMillis(),
            settings = cur,
            memories = cur.memories.toList(),
            reminders = db.reminderDao().reminders().first()
        )
        val dir = java.io.File(getApplication<Application>().filesDir, "backups").apply { mkdirs() }
        val f = java.io.File(dir, "max-backup-${payload.exportedAt}.json")
        f.writeText(Backup.encode(payload))
        return f
    }

    /** Newest backup file in app storage, if any. */
    fun latestBackupFile(): java.io.File? =
        java.io.File(getApplication<Application>().filesDir, "backups").listFiles()
            ?.filter { it.name.startsWith("max-backup-") && it.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }

    data class ImportSummary(val memories: Int, val reminders: Int, val scheduled: Int)

    /**
     * Validates and applies a backup document. Validation fully completes before
     * anything is written, so a malformed file changes nothing. Settings fields
     * are restored onto the current settings (backend URL/token, PIN and briefing
     * generation preserved); memories are replaced by the backup's set; imported
     * reminders are ADDED to existing ones, and future unfired ones are scheduled
     * with WorkManager exactly like addReminder.
     */
    suspend fun importBackup(text: String): ImportSummary {
        val p = Backup.decode(text) // throws BackupFormatException before any write
        settingsRepo.update {
            it.copy(
                userName = p.settings.userName,
                assistantName = p.settings.assistantName,
                thinkingMode = p.settings.thinkingMode,
                personality = p.settings.personality,
                customPersonality = p.settings.customPersonality,
                speechRate = p.settings.speechRate,
                language = p.settings.language,
                voiceName = p.settings.voiceName,
                animIntensity = p.settings.animIntensity,
                reducedMotion = p.settings.reducedMotion,
                memories = p.memories.toSet(),
                quietHoursEnabled = p.settings.quietHoursEnabled,
                quietStartMin = p.settings.quietStartMin,
                quietEndMin = p.settings.quietEndMin,
                briefingEnabled = p.settings.briefingEnabled,
                briefingMinOfDay = p.settings.briefingMinOfDay,
                mouthAnimation = p.settings.mouthAnimation,
                holoPanels = p.settings.holoPanels,
                robotAccent = p.settings.robotAccent
            )
        }
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        var scheduled = 0
        p.reminders.forEach { r ->
            val id = db.reminderDao().insert(r.copy(workId = ""))
            if (!r.fired && r.triggerAt > now) {
                runCatching {
                    val data = androidx.work.Data.Builder()
                        .putLong(com.max.neuraldimension.features.assistant.ReminderWorker.KEY_ID, id)
                        .putString(com.max.neuraldimension.features.assistant.ReminderWorker.KEY_TITLE, r.title)
                        .build()
                    val work = androidx.work.OneTimeWorkRequestBuilder<com.max.neuraldimension.features.assistant.ReminderWorker>()
                        .setInitialDelay(r.triggerAt - now, TimeUnit.MILLISECONDS)
                        .setInputData(data)
                        .addTag("nd-reminder")
                        .build()
                    androidx.work.WorkManager.getInstance(app).enqueue(work)
                    db.reminderDao().update(
                        Reminder(id = id, title = r.title, triggerAt = r.triggerAt,
                            workId = work.id.toString(), fired = r.fired, deferredUntil = r.deferredUntil)
                    )
                    scheduled++
                }
            }
        }
        return ImportSummary(p.memories.size, p.reminders.size, scheduled)
    }

    init {
        voice.init()
        viewModelScope.launch {
            settings.collect { s ->
                RobotState.assistantName = s.assistantName
                RobotState.intensity = s.animIntensity
                RobotState.reducedMotion = s.reducedMotion
                RobotState.mouthAnimation = s.mouthAnimation
                RobotState.accent = s.robotAccent
                voice.languageTag = s.language
                voice.voiceName = s.voiceName
                voice.applyVoice()
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

    fun send(text: String, attachment: PendingAttachment? = null) {
        if ((text.isBlank() && attachment == null) || sending.value) return
        viewModelScope.launch { generateAndStore(text, attachment = attachment) }
    }

    /**
     * Attachment picked in the UI. Bytes stay in memory; a disk copy (path) exists only
     * for non-incognito chats, where the message is stored and the file backs its history.
     */
    data class PendingAttachment(val name: String, val mime: String, val bytes: ByteArray, val path: String?)

    /** Core exchange used by chat and voice: stores user message, streams assistant reply, returns it. */
    suspend fun generateAndStore(text: String, storeUser: Boolean = true, attachment: PendingAttachment? = null): String {
        sending.value = true
        try {
            val s = settings.value
            val cid = if (incognito.value) -1L else ensureConversation(text)
            if (storeUser) {
                val userMsg = Message(
                    id = if (incognito.value) -System.currentTimeMillis() - 1 else 0,
                    conversationId = cid, role = "user", text = text,
                    attachmentName = attachment?.name,
                    attachmentMime = attachment?.mime,
                    attachmentPath = attachment?.path
                )
                if (incognito.value) incognitoMessages.value = incognitoMessages.value + userMsg
                else db.chatDao().insertMessage(userMsg)
            }
            if (!backend.isConfigured(s)) {
                val setupMsg = Message(id = if (incognito.value) -System.currentTimeMillis() else 0,
                    conversationId = cid, role = "assistant", text = "SETUP_REQUIRED::backend")
                if (incognito.value) incognitoMessages.value = incognitoMessages.value + setupMsg
                else db.chatDao().insertMessage(setupMsg)
                RobotState.mode = RobotState.Mode.CONFUSED
                viewModelScope.launch { delay(4000); if (RobotState.mode == RobotState.Mode.CONFUSED) RobotState.mode = RobotState.Mode.IDLE }
                return ""
            }
            // A user question shows CURIOUS while processing (UI animation cue
            // only - not a claim of emotion understanding); other work shows THINKING.
            RobotState.mode = if (text.trimEnd().endsWith("?")) RobotState.Mode.CURIOUS else RobotState.Mode.THINKING
            val history = if (incognito.value) {
                incognitoMessages.value
                    .filter { !it.text.startsWith("SETUP_REQUIRED::") && !it.text.startsWith("ERROR::") }
                    .takeLast(24)
                    .map { ChatTurn(it.role, it.text) }
            } else {
                db.chatDao().messages(cid).first()
                    .filter { !it.text.startsWith("SETUP_REQUIRED::") && !it.text.startsWith("ERROR::") }
                    .takeLast(24)
                    .map { ChatTurn(it.role, it.text) }
            }
            val aid: Long
            if (incognito.value) {
                aid = -System.currentTimeMillis()
                incognitoMessages.value = incognitoMessages.value + Message(id = aid, conversationId = -1, role = "assistant", text = "")
            } else {
                aid = db.chatDao().insertMessage(Message(conversationId = cid, role = "assistant", text = ""))
            }
            fun writeAssistant(t: String) {
                if (incognito.value) {
                    incognitoMessages.value = incognitoMessages.value.map { if (it.id == aid) it.copy(text = t) else it }
                } else {
                    viewModelScope.launch { db.chatDao().updateMessageText(aid, t) }
                }
            }
            val sb = StringBuilder()
            var lastWrite = 0L
            try {
                val atts = attachment?.let {
                    listOf(com.max.neuraldimension.domain.AttachmentData(
                        it.name, it.mime, android.util.Base64.encodeToString(it.bytes, android.util.Base64.NO_WRAP)))
                } ?: emptyList()
                backend.streamChat(s, history, atts) { tok ->
                    sb.append(tok)
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastWrite > 250) {
                        lastWrite = now
                        writeAssistant(sb.toString())
                    }
                }
                writeAssistant(sb.toString())
                RobotState.mode = RobotState.Mode.IDLE
                return sb.toString()
            } catch (e: AiException) {
                val msg = "ERROR::" + (e.message ?: "provider error")
                writeAssistant(msg)
                flashError()
                return ""
            } catch (e: Exception) {
                val msg = "ERROR::" + (e.message ?: e.javaClass.simpleName)
                writeAssistant(msg)
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

    /** Regenerate the reply to the most recent user message in the open conversation. */
    fun regenerateLast() {
        val cid = currentConversationId.value ?: return
        if (sending.value) return
        viewModelScope.launch {
            val msgs = db.chatDao().messages(cid).first()
            val lastUser = msgs.lastOrNull { it.role == "user" } ?: return@launch
            db.chatDao().deleteMessagesAfter(cid, lastUser.id)
            generateAndStore(lastUser.text, storeUser = false)
        }
    }

    /** Edit a user message and regenerate everything after it. */
    fun editUserMessage(m: Message, newText: String) {
        val t = newText.trim()
        if (t.isBlank() || sending.value || m.role != "user") return
        viewModelScope.launch {
            db.chatDao().updateMessageText(m.id, t)
            db.chatDao().deleteMessagesAfter(m.conversationId, m.id)
            generateAndStore(t, storeUser = false)
        }
    }

    suspend fun searchMessages(q: String): List<Message> = db.chatDao().search(q)

    fun newConversation() { currentConversationId.value = null }

    fun selectConversation(id: Long) { currentConversationId.value = id }

    fun renameConversation(c: Conversation, title: String) {
        viewModelScope.launch { db.chatDao().updateConversation(c.copy(title = title)) }
    }

    fun deleteConversation(c: Conversation) {
        viewModelScope.launch {
            db.chatDao().messages(c.id).first().forEach { m ->
                m.attachmentPath?.let { runCatching { java.io.File(it).delete() } }
            }
            db.chatDao().deleteMessagesFor(c.id)
            db.chatDao().deleteConversation(c)
            if (currentConversationId.value == c.id) currentConversationId.value = null
        }
    }

    val notes: StateFlow<List<Note>> =
        db.noteDao().notes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val reminders: StateFlow<List<Reminder>> =
        db.reminderDao().reminders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addNote(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { db.noteDao().insert(Note(text = text.trim())) }
    }

    fun deleteNote(n: Note) {
        viewModelScope.launch { db.noteDao().delete(n) }
    }

    /** Schedules a real WorkManager reminder; returns the new reminder id or -1. */
    fun addReminder(title: String, inMinutes: Int) {
        if (title.isBlank() || inMinutes < 0) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val triggerAt = System.currentTimeMillis() + inMinutes * 60_000L
            val id = db.reminderDao().insert(Reminder(title = title.trim(), triggerAt = triggerAt))
            val data = androidx.work.Data.Builder()
                .putLong(com.max.neuraldimension.features.assistant.ReminderWorker.KEY_ID, id)
                .putString(com.max.neuraldimension.features.assistant.ReminderWorker.KEY_TITLE, title.trim())
                .build()
            val work = androidx.work.OneTimeWorkRequestBuilder<com.max.neuraldimension.features.assistant.ReminderWorker>()
                .setInitialDelay(inMinutes.toLong(), TimeUnit.MINUTES)
                .setInputData(data)
                .addTag("nd-reminder")
                .build()
            androidx.work.WorkManager.getInstance(app).enqueue(work)
            db.reminderDao().update(Reminder(id = id, title = title.trim(), triggerAt = triggerAt, workId = work.id.toString()))
        }
    }

    fun deleteReminder(r: Reminder) {
        viewModelScope.launch {
            if (r.workId.isNotBlank()) {
                runCatching {
                    androidx.work.WorkManager.getInstance(getApplication<Application>())
                        .cancelWorkById(java.util.UUID.fromString(r.workId))
                }
            }
            db.reminderDao().delete(r)
        }
    }

    val habits: StateFlow<List<Habit>> =
        db.habitDao().habits().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val expenses: StateFlow<List<Expense>> =
        db.expenseDao().expenses().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addHabit(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { db.habitDao().insert(Habit(name = name.trim())) }
    }

    fun markHabitToday(h: Habit) {
        viewModelScope.launch {
            val today = java.time.LocalDate.now().toEpochDay()
            if (h.lastDoneDay == today) return@launch
            val newStreak = if (h.lastDoneDay == today - 1) h.streak + 1 else 1
            db.habitDao().update(h.copy(lastDoneDay = today, streak = newStreak))
            pulseExpression(RobotState.Mode.HAPPY, 4000)
        }
    }

    /** Brief expression tied to a real completion event, then back to IDLE. */
    private fun pulseExpression(mode: RobotState.Mode, ms: Long) {
        RobotState.mode = mode
        viewModelScope.launch { delay(ms); if (RobotState.mode == mode) RobotState.mode = RobotState.Mode.IDLE }
    }

    fun deleteHabit(h: Habit) {
        viewModelScope.launch { db.habitDao().delete(h) }
    }

    fun addExpense(label: String, amountCents: Long) {
        if (label.isBlank() || amountCents <= 0) return
        viewModelScope.launch { db.expenseDao().insert(Expense(label = label.trim(), amountCents = amountCents)) }
    }

    fun deleteExpense(e: Expense) {
        viewModelScope.launch { db.expenseDao().delete(e) }
    }

    val weather = MutableStateFlow<String?>(null)   // null = not loaded this session
    val weatherBusy = MutableStateFlow(false)

    fun loadWeather(city: String) {
        if (city.isBlank() || weatherBusy.value) return
        viewModelScope.launch {
            weatherBusy.value = true
            weather.value = try {
                com.max.neuraldimension.domain.WeatherClient.current(city)
            } catch (e: Exception) {
                "Weather unavailable: " + (e.message ?: e.javaClass.simpleName)
            }
            weatherBusy.value = false
        }
    }

    /** Local daily briefing: aggregates Room data; weather only if loaded this session. */
    fun briefing(): String {
        val openTasks = tasks.value.count { !it.done }
        val now = System.currentTimeMillis()
        val next = reminders.value
            .filter { !it.fired && it.effectiveAt() > now }
            .minByOrNull { it.effectiveAt() }
        val today = java.time.LocalDate.now().toEpochDay()
        val habitsDone = habits.value.count { it.lastDoneDay == today }
        val totalHabits = habits.value.size
        val parts = mutableListOf<String>()
        parts.add("$openTasks open task" + (if (openTasks == 1) "" else "s"))
        if (next != null) parts.add("next reminder: " + next.title + " in " + ((next.effectiveAt() - now) / 60000).coerceAtLeast(0) + "m")
        if (totalHabits > 0) parts.add("habits $habitsDone/$totalHabits done today")
        weather.value?.let { if (!it.startsWith("Weather unavailable")) parts.add(it) }
        return parts.joinToString(" - ")
    }

    fun addTask(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { db.taskDao().insert(TaskItem(title = title.trim())) }
    }

    fun toggleTask(t: TaskItem) {
        viewModelScope.launch {
            db.taskDao().update(t.copy(done = !t.done))
            if (!t.done) pulseExpression(RobotState.Mode.HAPPY, 4000)
        }
    }

    fun deleteTask(t: TaskItem) {
        viewModelScope.launch { db.taskDao().delete(t) }
    }

    fun testConnection() {
        viewModelScope.launch {
            connectionTest.value = "Testing..."
            val res = try {
                backend.testConnection(settings.value)
            } catch (e: Exception) {
                e.message ?: "Failed"
            }
            connectionTest.value = res
            if (res.startsWith("Backend reachable")) {
                // Only a successful authenticated health test marks the backend linked.
                // Editing URL/token afterwards makes this marker stale automatically.
                settingsRepo.update { it.copy(backendVerifiedFor = it.backendUrl + "\n" + it.appToken) }
            }
        }
    }

    fun speak(text: String) = voice.speak(text)

    override fun onCleared() {
        voice.release()
        super.onCleared()
    }
}
