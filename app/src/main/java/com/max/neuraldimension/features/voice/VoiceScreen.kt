package com.max.neuraldimension.features.voice

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.max.neuraldimension.core.gl.RobotState
import com.max.neuraldimension.core.gl.RobotView
import com.max.neuraldimension.core.theme.NdBlack
import com.max.neuraldimension.core.theme.NdError
import com.max.neuraldimension.core.theme.NdLavender
import com.max.neuraldimension.core.theme.NdMuted
import com.max.neuraldimension.core.theme.NdNeon
import com.max.neuraldimension.core.theme.NdOk
import com.max.neuraldimension.core.theme.NdPanelDeep
import com.max.neuraldimension.core.theme.NdSilver
import com.max.neuraldimension.core.theme.NdViolet
import com.max.neuraldimension.features.chat.AppViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VoiceScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsState()

    var statusText by remember { mutableStateOf("Tap the mic to talk with ${settings.assistantName}") }
    var partial by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var listening by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var continuous by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) { tick++; delay(500) }
    }

    var micDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { micDenied = false; continuous = true; vm.voice.startListening() }
        else { micDenied = true; statusText = "Microphone permission is needed for live conversation" }
    }

    DisposableEffect(Unit) {
        vm.voice.listener = object : VoiceEngine.Listener {
            override fun onListeningChanged(l: Boolean) {
                listening = l
                if (l) statusText = "Listening..."
            }
            override fun onPartial(text: String) { partial = text }
            override fun onFinal(text: String) {
                partial = ""
                transcript = transcript + ("You" to text)
                statusText = "Thinking..."
                RobotState.mode = RobotState.Mode.THINKING
                busy = true
                scope.launch {
                    val reply = vm.generateAndStore(text)
                    busy = false
                    if (reply.isBlank()) {
                        statusText = "AI reply unavailable - connect the secure backend in Settings"
                        if (continuous) { delay(1200); vm.voice.startListening() }
                    } else {
                        transcript = transcript + (settings.assistantName to reply)
                        statusText = "Speaking... tap stop to interrupt"
                        vm.voice.speak(reply)
                        statusText = if (continuous) "Listening..." else "Tap the mic to talk"
                        if (continuous) vm.voice.startListening()
                    }
                }
            }
            override fun onSpeechError(message: String) {
                statusText = message
                if (continuous && !busy && !vm.voice.speaking) {
                    scope.launch { delay(1500); if (continuous) vm.voice.startListening() }
                }
            }
            override fun onSpeakingChanged(s: Boolean) { speaking = s }
        }
        onDispose {
            continuous = false
            vm.voice.stopListening()
            vm.voice.stopSpeaking()
            vm.voice.listener = null
        }
    }

    Column(Modifier.fillMaxSize().background(NdBlack).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE WITH ${settings.assistantName}", color = NdLavender, fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val modeNow = if (tick >= 0) RobotState.mode else RobotState.mode
            val modeColor = when (modeNow) {
                RobotState.Mode.LISTENING -> NdOk
                RobotState.Mode.THINKING -> NdNeon
                RobotState.Mode.SPEAKING -> NdViolet
                RobotState.Mode.ERROR -> NdError
                else -> NdMuted
            }
            Text(modeNow.name, color = modeColor, fontSize = 11.sp, letterSpacing = 1.sp)
        }

        // Robot visible during conversation
        Box(Modifier.fillMaxWidth().height(300.dp)) { RobotView(Modifier.fillMaxSize()) }

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp).verticalScroll(rememberScrollState())
        ) {
            Text(statusText, color = NdLavender, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            // Honest recognition path label - never claims offline (may use network).
            Text("Recognition: " + vm.voice.recognitionModeDescription(), color = NdMuted, fontSize = 11.sp)
            if (micDenied) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    val i = android.content.Intent(
                        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(i)
                }) { Text("Open app settings to allow the microphone", color = NdNeon, fontSize = 12.sp) }
            }
            if (partial.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(partial, color = NdMuted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            transcript.takeLast(8).forEach { (who, what) ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(
                            if (who == "You") NdViolet.copy(alpha = 0.22f) else NdPanelDeep,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(who, color = NdMuted, fontSize = 10.sp)
                    Text(what, color = NdSilver, fontSize = 13.sp)
                }
            }
        }

        // controls
        Row(
            Modifier.fillMaxWidth().padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                continuous = false
                vm.voice.stopListening(); vm.voice.stopSpeaking()
                statusText = "Conversation ended"
            }) {
                Icon(Icons.Default.Close, contentDescription = "End conversation", tint = NdMuted, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(18.dp))
            Box(
                Modifier.size(74.dp).background(
                    if (listening) NdOk else NdViolet, CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = {
                    if (listening) {
                        continuous = false
                        vm.voice.stopListening()
                        statusText = "Stopped"
                    } else {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) { continuous = true; vm.voice.startListening() }
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }, modifier = Modifier.size(74.dp)) {
                    Icon(
                        if (listening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (listening) "Stop listening" else "Start listening",
                        tint = NdSilver, modifier = Modifier.size(30.dp)
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            IconButton(onClick = {
                vm.voice.stopSpeaking()
                statusText = if (continuous) "Listening..." else "Interrupted"
                if (continuous) vm.voice.startListening()
            }) {
                Icon(Icons.Default.Stop, contentDescription = "Interrupt MAX", tint = NdError, modifier = Modifier.size(26.dp))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = {
                if (!speaking) {
                    statusText = "Voice test..."
                    vm.speak("Hello ${settings.userName}. I am ${settings.assistantName}. My voice and my mouth are in sync.")
                }
            }) {
                Icon(Icons.Default.Call, contentDescription = null, tint = NdNeon, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Test MAX voice", color = NdNeon, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
