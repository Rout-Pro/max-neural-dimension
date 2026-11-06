package com.max.neuraldimension.features.chat

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.max.neuraldimension.core.theme.NdBlack
import com.max.neuraldimension.core.theme.NdError
import com.max.neuraldimension.core.theme.NdLavender
import com.max.neuraldimension.core.theme.NdMuted
import com.max.neuraldimension.core.theme.NdNeon
import com.max.neuraldimension.core.theme.NdPanelDeep
import com.max.neuraldimension.core.theme.NdSilver
import com.max.neuraldimension.core.theme.NdViolet
import com.max.neuraldimension.core.theme.NdWarn
import com.max.neuraldimension.data.db.Conversation
import com.max.neuraldimension.data.db.Message
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(vm: AppViewModel, onOpenSettings: () -> Unit) {
    val conversations by vm.conversations.collectAsState()
    val messages by vm.messages.collectAsState()
    val sending by vm.sending.collectAsState()
    val incognito by vm.incognito.collectAsState()
    val incognitoMessages by vm.incognitoMessages.collectAsState()
    val currentId by vm.currentConversationId.collectAsState()
    val settings by vm.settings.collectAsState()
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Message>>(emptyList()) }
    var editTarget by remember { mutableStateOf<Message?>(null) }
    var pendingAttachment by remember { mutableStateOf<AppViewModel.PendingAttachment?>(null) }
    var attachError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    } ?: "file"
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val supported = mime.startsWith("image/") || mime == "application/pdf" || mime == "text/plain"
                    val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                    when {
                        !supported -> attachError = "Unsupported file type ($mime). Images, PDF and plain text only."
                        bytes == null -> attachError = "Could not read that file."
                        bytes.size > 7_000_000 -> attachError = "File too large (about 7 MB max)."
                        vm.incognito.value -> {
                            // Incognito must leave nothing on disk: keep bytes in memory only.
                            pendingAttachment = AppViewModel.PendingAttachment(name, mime, bytes, null)
                            attachError = null
                        }
                        else -> {
                            val f = java.io.File(context.filesDir, "att_${System.currentTimeMillis()}_$name")
                            f.writeBytes(bytes)
                            pendingAttachment = AppViewModel.PendingAttachment(name, mime, bytes, f.absolutePath)
                            attachError = null
                        }
                    }
                } catch (e: Exception) {
                    attachError = "Could not read that file."
                }
            }
        }
    }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val pending by vm.pendingChatText.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { vm.pendingChatText.value = null; vm.send(it) }
    }
    val scrollCount = if (incognito) incognitoMessages.size else messages.size
    val scrollTail = (if (incognito) incognitoMessages else messages).lastOrNull()?.text?.length
    LaunchedEffect(scrollCount, scrollTail, incognito) {
        if (scrollCount > 0) listState.animateScrollToItem(0)
    }

    Column(Modifier.fillMaxSize().background(NdBlack).statusBarsPadding().imePadding()) {
        // ---- header: conversation picker + actions ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { menuOpen = true }, modifier = Modifier.weight(1f, fill = false)) {
                val title = conversations.firstOrNull { it.id == currentId }?.title ?: "New conversation"
                Text(
                    title.take(24), color = NdLavender, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Conversations", tint = NdMuted)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("New conversation") }, onClick = {
                    vm.newConversation(); menuOpen = false
                })
                conversations.forEach { c ->
                    DropdownMenuItem(text = { Text(c.title.take(30)) }, onClick = {
                        vm.selectConversation(c.id); menuOpen = false
                    })
                }
            }
            Spacer(Modifier.weight(0.1f).width(4.dp))
            IconButton(onClick = { vm.newConversation() }) {
                Icon(Icons.Default.Add, contentDescription = "New chat", tint = NdLavender)
            }
            IconButton(onClick = { vm.toggleIncognito() }) {
                Icon(
                    if (incognito) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = "Incognito",
                    tint = if (incognito) NdNeon else NdMuted
                )
            }
            IconButton(onClick = { searchOpen = true; searchQuery = ""; searchResults = emptyList() }) {
                Icon(Icons.Default.Search, contentDescription = "Search history", tint = NdLavender)
            }
            val cur = conversations.firstOrNull { it.id == currentId }
            if (cur != null) {
                IconButton(onClick = { renameOpen = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename chat", tint = NdLavender)
                }
                IconButton(onClick = { vm.deleteConversation(cur) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete chat", tint = NdMuted)
                }
            }
        }

        // ---- messages ----
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val shown = if (incognito) incognitoMessages else messages
            val lastAssistantId = shown.lastOrNull { it.role == "assistant" }?.id
            if (shown.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (incognito) "Incognito: not saved to your chat history"
                            else "Start a conversation with ${settings.assistantName}",
                            color = NdMuted, fontSize = 14.sp
                        )
                        if (incognito) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Replies still go through your secure backend.",
                                color = NdMuted, fontSize = 11.sp
                            )
                        }
                        if (settings.backendUrl.isBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Secure backend not connected yet — set its URL in Settings > Secure Backend to enable live replies.",
                                color = NdWarn, fontSize = 12.sp
                            )
                        }
                    }
                }
            }
            items(shown.asReversed(), key = { it.id }) { m ->
                MessageBubble(
                    m,
                    isLastAssistant = m.id == lastAssistantId,
                    onCopy = { clipboard.setText(AnnotatedString(m.text)) },
                    onOpenSettings = onOpenSettings,
                    onEdit = { editTarget = m },
                    onRegenerate = { vm.regenerateLast() }
                )
            }
            if (sending) {
                item { Text("Thinking...", color = NdMuted, fontSize = 12.sp, modifier = Modifier.padding(6.dp)) }
            }
        }

        // ---- rename dialog ----
        val cur2 = conversations.firstOrNull { it.id == currentId }
        if (renameOpen && cur2 != null) {
            var title by remember(cur2.id) { mutableStateOf(cur2.title) }
            AlertDialog(
                onDismissRequest = { renameOpen = false },
                confirmButton = {
                    TextButton(onClick = {
                        if (title.isNotBlank()) vm.renameConversation(cur2, title.trim())
                        renameOpen = false
                    }) { Text("Save", color = NdLavender) }
                },
                dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel", color = NdMuted) } },
                title = { Text("Rename conversation", color = NdSilver) },
                text = {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it }, singleLine = true,
                        textStyle = TextStyle(color = NdSilver, fontSize = 14.sp)
                    )
                },
                containerColor = NdPanelDeep
            )
        }

        // ---- search dialog ----
        if (searchOpen) {
            AlertDialog(
                onDismissRequest = { searchOpen = false },
                confirmButton = { TextButton(onClick = { searchOpen = false }) { Text("Close", color = NdLavender) } },
                title = { Text("Search history", color = NdSilver) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { q ->
                                searchQuery = q
                                if (q.length >= 2) scope.launch { searchResults = vm.searchMessages(q.trim()) }
                                else searchResults = emptyList()
                            },
                            singleLine = true,
                            placeholder = { Text("Type to search messages", color = NdMuted, fontSize = 13.sp) },
                            textStyle = TextStyle(color = NdSilver, fontSize = 14.sp)
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(searchResults, key = { it.id }) { r ->
                                Column(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        (if (r.role == "user") "You: " else "${settings.assistantName}: ") + r.text.take(90),
                                        color = NdSilver, fontSize = 13.sp,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                    TextButton(onClick = {
                                        vm.selectConversation(r.conversationId)
                                        searchOpen = false
                                    }) { Text("Open conversation", color = NdNeon, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                },
                containerColor = NdPanelDeep
            )
        }

        // ---- edit user message dialog ----
        editTarget?.let { em ->
            var edited by remember(em.id) { mutableStateOf(em.text) }
            AlertDialog(
                onDismissRequest = { editTarget = null },
                confirmButton = {
                    TextButton(onClick = {
                        vm.editUserMessage(em, edited)
                        editTarget = null
                    }) { Text("Save & resend", color = NdLavender) }
                },
                dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel", color = NdMuted) } },
                title = { Text("Edit message", color = NdSilver) },
                text = {
                    OutlinedTextField(
                        value = edited, onValueChange = { edited = it },
                        textStyle = TextStyle(color = NdSilver, fontSize = 14.sp)
                    )
                },
                containerColor = NdPanelDeep
            )
        }

        // ---- pending attachment chip ----
        pendingAttachment?.let { pa ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, tint = NdNeon, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(pa.name.take(38), color = NdSilver, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { pendingAttachment = null }, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove attachment", tint = NdMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
        attachError?.let {
            Text(it, color = NdWarn, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        }

        // ---- input ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                attachLauncher.launch(arrayOf("image/*", "application/pdf", "text/plain"))
            }) {
                Icon(Icons.Default.AttachFile, contentDescription = "Attach image, PDF or text file", tint = NdLavender)
            }
            Row(
                Modifier.weight(1f)
                    .background(NdPanelDeep, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(color = NdSilver, fontSize = 14.sp),
                    cursorBrush = SolidColor(NdLavender),
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    decorationBox = { inner ->
                        if (input.isEmpty()) Text("Message ${settings.assistantName}...", color = NdMuted, fontSize = 14.sp)
                        inner()
                    }
                )
                IconButton(onClick = {
                    if (input.isNotBlank() || pendingAttachment != null) {
                        vm.send(input.trim(), pendingAttachment)
                        input = ""
                        pendingAttachment = null
                    }
                }) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = NdNeon)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    m: Message,
    isLastAssistant: Boolean,
    onCopy: () -> Unit,
    onOpenSettings: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit
) {
    val isUser = m.role == "user"
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = 300.dp)
                .background(
                    if (isUser) NdViolet.copy(alpha = 0.35f) else NdPanelDeep,
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            if (m.attachmentName != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, tint = NdNeon, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(m.attachmentName.take(34), color = NdLavender, fontSize = 11.sp)
                }
            }
            when {
                m.text.startsWith("SETUP_REQUIRED::") -> {
                    Text("Secure backend is not connected yet", color = NdWarn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "MAX answers through your secure backend, which holds the AI provider keys server-side. Set its URL in Settings > Secure Backend and your messages will get real answers.",
                        color = NdMuted, fontSize = 12.sp
                    )
                    TextButton(onClick = onOpenSettings) { Text("Open Settings", color = NdLavender, fontSize = 12.sp) }
                }
                m.text.startsWith("ERROR::") -> {
                    val raw = m.text.removePrefix("ERROR::")
                    val quota = raw.contains("429") || raw.contains("quota", ignoreCase = true) ||
                        raw.contains("rate limit", ignoreCase = true) || raw.contains("retry later", ignoreCase = true)
                    val unavailable = raw.contains("503") || raw.contains("unavailable", ignoreCase = true)
                    var showDetail by remember { mutableStateOf(false) }
                    Text("Request failed", color = NdError, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            // No timing promise: per-minute windows and daily quotas recover differently.
                            quota -> "The AI provider's rate or quota limit has been reached. Please try again later."
                            unavailable -> "The AI provider is temporarily unavailable. Please try again later."
                            else -> "The backend could not answer this request. Please try again."
                        },
                        color = NdMuted, fontSize = 12.sp
                    )
                    TextButton(onClick = { showDetail = !showDetail }) {
                        Text(if (showDetail) "Hide technical details" else "Technical details", color = NdLavender, fontSize = 11.sp)
                    }
                    if (showDetail) {
                        Text(raw.take(400), color = NdMuted, fontSize = 11.sp)
                    }
                }
                m.text.isEmpty() && !isUser -> {
                    Text("...", color = NdMuted, fontSize = 14.sp)
                }
                else -> {
                    SelectionContainer { MarkdownText(m.text, if (isUser) NdSilver else NdSilver) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (isUser) {
                            IconButton(onClick = onEdit, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = NdMuted, modifier = Modifier.size(14.dp))
                            }
                        } else {
                            if (isLastAssistant) {
                                IconButton(onClick = onRegenerate, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Regenerate", tint = NdMuted, modifier = Modifier.size(14.dp))
                                }
                            }
                            IconButton(onClick = onCopy, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = NdMuted, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Minimal honest markdown: headers, bold, inline code and fenced code blocks. */
@Composable
fun MarkdownText(text: String, color: androidx.compose.ui.graphics.Color) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Code -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(NdBlack.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(b.code, color = NdNeon, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                is MdBlock.Text -> Text(
                    buildAnnotatedString {
                        var i = 0
                        val s = b.text
                        while (i < s.length) {
                            val boldStart = s.indexOf("**", i)
                            val codeStart = s.indexOf('`', i)
                            val next = listOf(boldStart, codeStart).filter { it >= 0 }.minOrNull() ?: s.length
                            append(s.substring(i, next))
                            i = next
                            if (i >= s.length) break
                            if (boldStart == i) {
                                val end = s.indexOf("**", i + 2)
                                if (end < 0) { append("**"); i += 2 } else {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = NdLavender)) {
                                        append(s.substring(i + 2, end))
                                    }
                                    i = end + 2
                                }
                            } else {
                                val end = s.indexOf('`', i + 1)
                                if (end < 0) { append('`'); i += 1 } else {
                                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = NdNeon)) {
                                        append(s.substring(i + 1, end))
                                    }
                                    i = end + 1
                                }
                            }
                        }
                    },
                    color = color, fontSize = 14.sp, lineHeight = 20.sp
                )
                is MdBlock.Header -> Text(
                    b.text, color = NdLavender,
                    fontSize = if (b.level == 1) 17.sp else 15.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private sealed class MdBlock {
    data class Text(val text: String) : MdBlock()
    data class Header(val text: String, val level: Int) : MdBlock()
    data class Code(val code: String) : MdBlock()
}

private fun parseMarkdown(text: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = text.lines()
    var i = 0
    val buf = StringBuilder()
    fun flush() { if (buf.isNotEmpty()) { out.add(MdBlock.Text(buf.toString().trimEnd())); buf.clear() } }
    while (i < lines.size) {
        val l = lines[i]
        when {
            l.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
                out.add(MdBlock.Code(code.toString().trimEnd()))
            }
            l.startsWith("# ") -> { flush(); out.add(MdBlock.Header(l.removePrefix("# ").trim(), 1)) }
            l.startsWith("## ") -> { flush(); out.add(MdBlock.Header(l.removePrefix("## ").trim(), 2)) }
            else -> buf.append(l).append('\n')
        }
        i++
    }
    flush()
    return out
}
