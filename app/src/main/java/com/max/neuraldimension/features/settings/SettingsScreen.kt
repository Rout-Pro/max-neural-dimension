package com.max.neuraldimension.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import com.max.neuraldimension.core.theme.NdBlack
import com.max.neuraldimension.features.assistant.BriefingScheduler
import com.max.neuraldimension.core.theme.NdLavender
import com.max.neuraldimension.core.theme.NdMuted
import com.max.neuraldimension.core.theme.NdNeon
import com.max.neuraldimension.core.theme.NdOk
import com.max.neuraldimension.core.theme.NdPanelDeep
import com.max.neuraldimension.core.theme.NdSilver
import com.max.neuraldimension.core.theme.NdViolet
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.Personality
import com.max.neuraldimension.features.chat.AppViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val testResult by vm.connectionTest.collectAsState()

    Column(
        Modifier.fillMaxSize().background(NdBlack).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SETTINGS", color = NdLavender, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)

        Section("Profile") {
            Field("Your name", s.userName) { v -> scope.launch { vm.settingsRepo.update { it.copy(userName = v) } } }
            Field("Assistant name", s.assistantName) { v -> scope.launch { vm.settingsRepo.update { it.copy(assistantName = v) } } }
        }

        Section("SECURE BACKEND") {
            Field("Backend URL (https://your-worker.example.workers.dev)", s.backendUrl) { v ->
                scope.launch { vm.settingsRepo.update { it.copy(backendUrl = v) } }
            }
            PasswordField("App token (shared with your backend, not an AI key)", s.appToken) { v ->
                scope.launch { vm.settingsRepo.update { it.copy(appToken = v) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Thinking mode", color = NdSilver, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = s.thinkingMode,
                    onCheckedChange = { v -> scope.launch { vm.settingsRepo.update { it.copy(thinkingMode = v) } } },
                    colors = SwitchDefaults.colors(checkedTrackColor = NdViolet)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.testConnection() }) { Text("Test connection", color = NdNeon) }
                testResult?.let { Text(it.take(80), color = NdMuted, fontSize = 11.sp) }
            }
            Text(
                "AI provider keys (Gemini, OpenAI-compatible, Groq) are stored only on your backend server - never in this app. " +
                    "The backend source ships in the repo under backend/ and deploys free (see backend/README.md).",
                color = NdMuted, fontSize = 11.sp
            )
        }

        Section("Personality") {
            PickerRow("Mode", s.personality.label, Personality.entries.map { it.label }) { idx ->
                scope.launch { vm.settingsRepo.update { it.copy(personality = Personality.entries[idx]) } }
            }
            if (s.personality == Personality.CUSTOM) {
                var custom by remember { mutableStateOf(s.customPersonality) }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    placeholder = { Text("Describe how MAX should behave...", color = NdMuted, fontSize = 13.sp) },
                    textStyle = TextStyle(color = NdSilver, fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = {
                    scope.launch { vm.settingsRepo.update { it.copy(customPersonality = custom.trim()) } }
                }) { Text("Save personality", color = NdNeon) }
            }
        }

        Section("Voice") {
            PickerRow("Language", s.language, listOf("en-IN", "en-US", "hi-IN")) { idx ->
                scope.launch { vm.settingsRepo.update { it.copy(language = listOf("en-IN", "en-US", "hi-IN")[idx], voiceName = "") } }
            }
            // Voices for the selected language, from the device TTS engine (male/female
            // where the device exposes them). Loads async while the engine initializes.
            val voiceNames by produceState(initialValue = emptyList<String>(), s.language) {
                var tries = 0
                while (tries < 20) {
                    val v = vm.voice.availableVoiceNames()
                    if (v.isNotEmpty()) { value = v; break }
                    tries++
                    kotlinx.coroutines.delay(500)
                }
            }
            if (voiceNames.isNotEmpty()) {
                val options = listOf("System default") + voiceNames
                PickerRow("Voice", if (s.voiceName.isBlank()) "System default" else s.voiceName, options) { idx ->
                    val picked = if (idx == 0) "" else voiceNames[idx - 1]
                    scope.launch { vm.settingsRepo.update { it.copy(voiceName = picked) } }
                }
            }
            Text("Speech rate: %.1fx".format(s.speechRate), color = NdSilver, fontSize = 13.sp)
            Slider(
                value = s.speechRate, valueRange = 0.5f..2.0f,
                onValueChange = { v -> scope.launch { vm.settingsRepo.update { it.copy(speechRate = v) } } },
                colors = SliderDefaults.colors(activeTrackColor = NdViolet, thumbColor = NdNeon)
            )
            TextButton(onClick = { vm.speak("Hello. This is ${s.assistantName} speaking at this rate.") }) {
                Text("Test voice", color = NdNeon)
            }
        }

        Section("Robot") {
            Text("Animation intensity: %.1f".format(s.animIntensity), color = NdSilver, fontSize = 13.sp)
            Slider(
                value = s.animIntensity, valueRange = 0.2f..1.6f,
                onValueChange = { v -> scope.launch { vm.settingsRepo.update { it.copy(animIntensity = v) } } },
                colors = SliderDefaults.colors(activeTrackColor = NdViolet, thumbColor = NdNeon)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reduced motion", color = NdSilver, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = s.reducedMotion,
                    onCheckedChange = { v -> scope.launch { vm.settingsRepo.update { it.copy(reducedMotion = v) } } },
                    colors = SwitchDefaults.colors(checkedTrackColor = NdViolet)
                )
            }
        }

        Section("Memory") {
            var newMem by remember { mutableStateOf("") }
            s.memories.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m, color = NdSilver, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch { vm.settingsRepo.update { it.copy(memories = it.memories - m) } }
                    }) { Text("Delete", color = NdMuted, fontSize = 11.sp) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = newMem, onValueChange = { newMem = it },
                    textStyle = TextStyle(color = NdSilver, fontSize = 13.sp),
                    cursorBrush = SolidColor(NdLavender),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    decorationBox = { inner ->
                        if (newMem.isEmpty()) Text("Add a fact to remember...", color = NdMuted, fontSize = 13.sp)
                        inner()
                    }
                )
                TextButton(onClick = {
                    if (newMem.isNotBlank()) {
                        scope.launch { vm.settingsRepo.update { it.copy(memories = it.memories + newMem.trim()) } }
                        newMem = ""
                    }
                }) { Text("Add", color = NdLavender) }
            }
            Text("Memories are stored only on this device and are sent to your provider as part of the chat context.", color = NdMuted, fontSize = 11.sp)
        }

        Section("Quiet hours and daily briefing") {
            val context = LocalContext.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quiet hours", color = NdSilver, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = s.quietHoursEnabled,
                    onCheckedChange = { v -> scope.launch { vm.settingsRepo.update { it.copy(quietHoursEnabled = v) } } },
                    colors = SwitchDefaults.colors(checkedTrackColor = NdViolet)
                )
            }
            if (s.quietHoursEnabled) {
                Text("From %02d:00 to %02d:00".format(s.quietStartMin / 60, s.quietEndMin / 60), color = NdSilver, fontSize = 13.sp)
                Text("Start hour: %02d:00".format(s.quietStartMin / 60), color = NdMuted, fontSize = 11.sp)
                Slider(
                    value = s.quietStartMin / 60f, valueRange = 0f..23f, steps = 22,
                    onValueChange = { v -> scope.launch { vm.settingsRepo.update { it.copy(quietStartMin = v.roundToInt() * 60) } } },
                    colors = SliderDefaults.colors(activeTrackColor = NdViolet, thumbColor = NdNeon)
                )
                Text("End hour: %02d:00".format(s.quietEndMin / 60), color = NdMuted, fontSize = 11.sp)
                Slider(
                    value = s.quietEndMin / 60f, valueRange = 0f..23f, steps = 22,
                    onValueChange = { v -> scope.launch { vm.settingsRepo.update { it.copy(quietEndMin = v.roundToInt() * 60) } } },
                    colors = SliderDefaults.colors(activeTrackColor = NdViolet, thumbColor = NdNeon)
                )
                Text(
                    "Reminders and the daily briefing that fall inside quiet hours are deferred to the end of the window instead of firing during it. They fire late, not never - nothing is deleted or changed.",
                    color = NdMuted, fontSize = 11.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Daily briefing", color = NdSilver, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = s.briefingEnabled,
                    onCheckedChange = { v ->
                        scope.launch {
                            vm.settingsRepo.update { it.copy(briefingEnabled = v) }
                            BriefingScheduler.reconcile(context)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = NdViolet)
                )
            }
            if (s.briefingEnabled) {
                Text("Around %02d:00 each day".format(s.briefingMinOfDay / 60), color = NdSilver, fontSize = 13.sp)
                Slider(
                    value = s.briefingMinOfDay / 60f, valueRange = 0f..23f, steps = 22,
                    onValueChange = { v ->
                        scope.launch { vm.settingsRepo.update { it.copy(briefingMinOfDay = v.roundToInt() * 60) } }
                    },
                    onValueChangeFinished = { scope.launch { BriefingScheduler.reconcile(context) } },
                    colors = SliderDefaults.colors(activeTrackColor = NdViolet, thumbColor = NdNeon)
                )
                Text(
                    "Arrives as a notification around the chosen time. Android may deliver it a little late to save battery, so this is not an exact alarm.",
                    color = NdMuted, fontSize = 11.sp
                )
            }
        }

        Section("About MAX 2.0") {
            Text("Neural Dimension - version 2.0 (Phase 1)", color = NdSilver, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Working now: rigged 3D robot (GLB asset with facial morph targets) with lip-synced voice, live voice conversation, " +
                    "streaming AI chat through your secure backend (keys stay server-side), chat history, tasks, memory, device tools.\n\n" +
                    "Next phases: security lock, wake word, web development studio, " +
                    "image/video generation (needs provider APIs on the backend), performance tiers.",
                color = NdMuted, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(NdPanelDeep, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text(title, color = NdLavender, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = NdMuted, fontSize = 11.sp)
        BasicTextField(
            value = v,
            onValueChange = { v = it; onChange(it) },
            textStyle = TextStyle(color = NdSilver, fontSize = 14.sp),
            cursorBrush = SolidColor(NdLavender),
            modifier = Modifier.fillMaxWidth()
                .background(NdBlack.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = NdMuted, fontSize = 11.sp)
        BasicTextField(
            value = v,
            onValueChange = { v = it; onChange(it) },
            textStyle = TextStyle(color = NdSilver, fontSize = 14.sp),
            cursorBrush = SolidColor(NdLavender),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
                .background(NdBlack.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun PickerRow(label: String, current: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = NdMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { open = true }) { Text(current, color = NdLavender, fontSize = 13.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onPick(i); open = false })
            }
        }
    }
}
