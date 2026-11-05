package com.max.neuraldimension.features.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun ChatScreen(vm: AppViewModel, onOpenSettings: () -> Unit) {
    val conversations by vm.conversations.collectAsState()
    val messages by vm.messages.collectAsState()
    val sending by vm.sending.collectAsState()
    val currentId by vm.currentConversationId.collectAsState()
    val settings by vm.settings.collectAsState()
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val pending by vm.pendingChatText.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { vm.pendingChatText.value = null; vm.send(it) }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().background(NdBlack).statusBarsPadding()) {
        // ---- header: conversation picker + actions ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { menuOpen = true }) {
                val title = conversations.firstOrNull { it.id == currentId }?.title ?: "New conversation"
                Text(title.take(24), color = NdLavender, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.newConversation() }) {
                Icon(Icons.Default.Add, contentDescription = "New chat", tint = NdLavender)
            }
            val cur = conversations.firstOrNull { it.id == currentId }
            if (cur != null) {
                IconButton(onClick = { vm.deleteConversation(cur) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete chat", tint = NdMuted)
                }
            }
        }

        // ---- messages ----
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Start a conversation with ${settings.assistantName}", color = NdMuted, fontSize = 14.sp)
                        if (!vm.settingsRepo.hasKey(settings.provider)) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "No AI key saved yet — add one in Settings > AI Provider to enable live replies.",
                                color = NdWarn, fontSize = 12.sp
                            )
                        }
                    }
                }
            }
            items(messages, key = { it.id }) { m ->
                MessageBubble(m, onCopy = { clipboard.setText(AnnotatedString(m.text)) }, onOpenSettings = onOpenSettings)
            }
            if (sending) {
                item { Text("Thinking...", color = NdMuted, fontSize = 12.sp, modifier = Modifier.padding(6.dp)) }
            }
        }

        // ---- input ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                    if (input.isNotBlank()) { vm.send(input.trim()); input = "" }
                }) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = NdNeon)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: Message, onCopy: () -> Unit, onOpenSettings: () -> Unit) {
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
            when {
                m.text.startsWith("SETUP_REQUIRED::") -> {
                    val provider = m.text.removePrefix("SETUP_REQUIRED::")
                    Text("Live AI is not configured yet", color = NdWarn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add your $provider API key in Settings > AI Provider and your messages will get real answers. The key stays encrypted on this phone.",
                        color = NdMuted, fontSize = 12.sp
                    )
                    TextButton(onClick = onOpenSettings) { Text("Open Settings", color = NdLavender, fontSize = 12.sp) }
                }
                m.text.startsWith("ERROR::") -> {
                    Text("Request failed", color = NdError, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(m.text.removePrefix("ERROR::"), color = NdMuted, fontSize = 12.sp)
                }
                m.text.isEmpty() && !isUser -> {
                    Text("...", color = NdMuted, fontSize = 14.sp)
                }
                else -> {
                    SelectionContainer { MarkdownText(m.text, if (isUser) NdSilver else NdSilver) }
                    if (!isUser) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
