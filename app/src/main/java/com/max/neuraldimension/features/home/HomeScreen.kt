package com.max.neuraldimension.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.max.neuraldimension.core.gl.RobotState
import com.max.neuraldimension.core.gl.RobotView
import com.max.neuraldimension.core.theme.NdBlack
import com.max.neuraldimension.core.theme.NdLavender
import com.max.neuraldimension.core.theme.NdMuted
import com.max.neuraldimension.core.theme.NdNeon
import com.max.neuraldimension.core.theme.NdOk
import com.max.neuraldimension.core.theme.NdSilver
import com.max.neuraldimension.core.theme.NdViolet
import com.max.neuraldimension.core.theme.NdWarn
import com.max.neuraldimension.features.chat.AppViewModel
import com.max.neuraldimension.features.hologram.HoloPanel
import com.max.neuraldimension.features.hologram.PanelLine
import com.max.neuraldimension.features.hologram.StatusDot
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PanelKind { AI, DAILY, TASKS, QUICK, NOTIFS, MEMORY }

@Composable
fun HomeScreen(vm: AppViewModel, onOpenChat: () -> Unit, onOpenVoice: () -> Unit, onOpenTools: () -> Unit, onOpenSettings: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val tasks by vm.tasks.collectAsState()
    var expanded by remember { mutableStateOf<PanelKind?>(null) }
    var input by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(Date()) }
    var modeTick by remember { mutableStateOf(RobotState.mode) }

    LaunchedEffect(Unit) {
        while (true) { now = Date(); modeTick = RobotState.mode; delay(1000) }
    }

    Box(Modifier.fillMaxSize().background(NdBlack)) {
        RobotView(Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- top bar ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        settings.assistantName, color = NdSilver, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 4.sp
                    )
                    Text("NEURAL DIMENSION", color = NdMuted, fontSize = 9.sp, letterSpacing = 3.sp)
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val connected = settings.backendUrl.isNotBlank()
                    StatusDot(if (connected) NdOk else NdWarn)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (connected) "Backend linked" else "No backend",
                        color = if (connected) NdOk else NdWarn, fontSize = 11.sp
                    )
                }
            }

            // ---- greeting ----
            Column(Modifier.padding(horizontal = 18.dp)) {
                val hour = remember(now) { SimpleDateFormat("HH", Locale.US).format(now).toInt() }
                val greet = when {
                    hour < 5 -> "Up late"
                    hour < 12 -> "Good morning"
                    hour < 17 -> "Good afternoon"
                    else -> "Good evening"
                }
                Text("$greet, ${settings.userName}.", color = NdLavender, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Mode: " + if (settings.thinkingMode) "Thinking" else "Fast",
                    color = NdMuted, fontSize = 11.sp
                )
            }

            // ---- robot + floating panels ----
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val w = maxWidth
                val panelW = 148.dp
                Column(
                    Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HoloPanel("AI STATUS", Icons.Default.Psychology, 0, { expanded = PanelKind.AI }, Modifier.width(panelW)) {
                        PanelLine("Backend", if (settings.backendUrl.isNotBlank()) "Linked" else "Not set")
                        PanelLine("State", modeTick.name.lowercase().replaceFirstChar { it.uppercase() }, NdNeon)
                    }
                    HoloPanel("TASKS", Icons.Default.Task, 1, { expanded = PanelKind.TASKS }, Modifier.width(panelW)) {
                        val open = tasks.count { !it.done }
                        PanelLine("Open", open.toString(), if (open > 0) NdWarn else NdOk)
                        PanelLine("Done", tasks.count { it.done }.toString())
                    }
                    HoloPanel("ALERTS", Icons.Default.Notifications, 2, { expanded = PanelKind.NOTIFS }, Modifier.width(panelW)) {
                        PanelLine("Feeds", "None linked", NdMuted)
                    }
                }
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HoloPanel("TODAY", Icons.Default.WbSunny, 3, { expanded = PanelKind.DAILY }, Modifier.width(panelW)) {
                        PanelLine("Time", SimpleDateFormat("HH:mm", Locale.US).format(now))
                        PanelLine("Date", SimpleDateFormat("EEE, d MMM", Locale.US).format(now))
                    }
                    HoloPanel("ACTIONS", Icons.Default.AutoAwesome, 4, { expanded = PanelKind.QUICK }, Modifier.width(panelW)) {
                        PanelLine("Chat / Voice", "Tap to open")
                        PanelLine("Research", "Phase 3", NdMuted)
                    }
                    HoloPanel("MEMORY", Icons.Default.CheckCircle, 5, { expanded = PanelKind.MEMORY }, Modifier.width(panelW)) {
                        PanelLine("Saved", "${settings.memories.size} facts")
                        PanelLine("Chats", "Local", NdMuted)
                    }
                }
            }

            // ---- bottom chat input ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f)
                        .background(NdBlack.copy(alpha = 0.55f), RoundedCornerShape(26.dp))
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
                            if (input.isEmpty()) Text("Talk to ${settings.assistantName}...", color = NdMuted, fontSize = 14.sp)
                            inner()
                        }
                    )
                    IconButton(onClick = {
                        if (input.isNotBlank()) {
                            vm.pendingChatText.value = input
                            input = ""
                            onOpenChat()
                        }
                    }) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = NdNeon, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                IconButton(
                    onClick = onOpenVoice,
                    modifier = Modifier.size(46.dp).background(NdViolet, RoundedCornerShape(50))
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Talk with MAX", tint = NdSilver)
                }
            }
        }
    }

    // ---- expanded panel sheet ----
    expanded?.let { kind ->
        AlertDialog(
            onDismissRequest = { expanded = null },
            confirmButton = { TextButton(onClick = { expanded = null }) { Text("Close", color = NdLavender) } },
            containerColor = com.max.neuraldimension.core.theme.NdDarkPurple,
            title = {
                Text(
                    when (kind) {
                        PanelKind.AI -> "AI Status"
                        PanelKind.DAILY -> "Today"
                        PanelKind.TASKS -> "Tasks"
                        PanelKind.QUICK -> "Quick Actions"
                        PanelKind.NOTIFS -> "Notifications"
                        PanelKind.MEMORY -> "Memory"
                    }, color = NdLavender
                )
            },
            text = {
                Column {
                    when (kind) {
                        PanelKind.AI -> {
                            PanelLine("Backend", if (settings.backendUrl.isNotBlank()) settings.backendUrl else "Not configured", NdSilver)
                            PanelLine("Mode", if (settings.thinkingMode) "Thinking" else "Fast", NdSilver)
                            PanelLine("State", modeTick.name, NdNeon)
                            Spacer(Modifier.height(6.dp))
                            Text("Provider keys live on your secure backend server, never in the app. Set the backend URL in Settings.", color = NdMuted, fontSize = 11.sp)
                        }
                        PanelKind.DAILY -> {
                            PanelLine("Time", SimpleDateFormat("HH:mm:ss", Locale.US).format(now), NdSilver)
                            PanelLine("Date", SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US).format(now), NdSilver)
                            PanelLine("Weather", "Configure in a later phase", NdMuted)
                        }
                        PanelKind.TASKS -> {
                            if (tasks.isEmpty()) Text("No tasks yet. Add from the Tools tab.", color = NdMuted, fontSize = 12.sp)
                            tasks.take(6).forEach { t ->
                                PanelLine(if (t.done) "✓" else "•", t.title, if (t.done) NdMuted else NdSilver)
                            }
                        }
                        PanelKind.QUICK -> {
                            TextButton(onClick = { expanded = null; onOpenChat() }) { Text("Open Chat", color = NdLavender) }
                            TextButton(onClick = { expanded = null; onOpenVoice() }) { Text("Open Voice", color = NdLavender) }
                            TextButton(onClick = { expanded = null; onOpenTools() }) { Text("Open Tools", color = NdLavender) }
                            Text("Research, image, video and web-development actions arrive in Phase 3 and need provider support.", color = NdMuted, fontSize = 11.sp)
                        }
                        PanelKind.NOTIFS -> {
                            Text("No notification sources are linked yet. Reminders and integrations arrive in Phase 2.", color = NdMuted, fontSize = 12.sp)
                        }
                        PanelKind.MEMORY -> {
                            if (settings.memories.isEmpty()) {
                                Text("No saved facts yet. Add memories in Settings > Memory.", color = NdMuted, fontSize = 12.sp)
                            } else {
                                settings.memories.take(6).forEach { PanelLine("•", it, NdSilver) }
                                if (settings.memories.size > 6) Text("+${settings.memories.size - 6} more", color = NdMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        )
    }
}

