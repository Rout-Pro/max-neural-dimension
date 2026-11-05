package com.max.neuraldimension

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val Night = Color(0xFF090611)
private val Panel = Color(0xFF211632)
private val Violet = Color(0xFF914BFF)
private val Lavender = Color(0xFFD9BFFF)
private val Muted = Color(0xFFB9A9CC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaxTheme { MaxRoot() } }
    }
}

@Composable
private fun MaxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Night,
            surface = Panel,
            primary = Violet,
            secondary = Lavender,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Home", Icons.Default.Home),
    CHAT("Chat", Icons.Default.Chat),
    VOICE("Voice", Icons.Default.Mic),
    TOOLS("Tools", Icons.Default.AutoAwesome),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
private fun MaxRoot(vm: MaxViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    Scaffold(
        containerColor = Night,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF130D1E)) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Lavender,
                            selectedTextColor = Lavender,
                            indicatorColor = Color(0xFF382052),
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding)
                .background(Brush.verticalGradient(listOf(Color(0xFF211235), Night, Color(0xFF10091A))))
        ) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    onChat = { tab = Tab.CHAT },
                    onVoice = { tab = Tab.VOICE },
                    onTools = { tab = Tab.TOOLS }
                )
                Tab.CHAT -> ChatScreen(vm)
                Tab.VOICE -> VoiceScreen()
                Tab.TOOLS -> ToolsScreen()
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(
            Brush.linearGradient(listOf(Color(0xAA49306A), Color(0x66302243), Color(0x553C2850))),
            RoundedCornerShape(24.dp)
        ).padding(16.dp),
        content = content
    )
}

@Composable
private fun Orb(size: Int = 190) {
    Box(
        Modifier.size(size.dp).background(
            Brush.radialGradient(
                listOf(Color(0xFFFFD7FF), Color(0xFFB45CFF), Color(0xFF57269D), Color.Transparent)
            ), CircleShape
        ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size((size * .58f).dp).background(
                Brush.sweepGradient(
                    listOf(Color(0xFFFCDBFF), Color(0xFF8A4BFF), Color(0xFF24143E), Color(0xFFE9C7FF))
                ), CircleShape
            )
        )
        Box(Modifier.size((size * .35f).dp).background(
            Brush.radialGradient(listOf(Color(0xFF120B20), Color(0xFF5E3291))), CircleShape
        ))
    }
}

@Composable
private fun HomeScreen(onChat: () -> Unit, onVoice: () -> Unit, onTools: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("MAX", color = Lavender, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                Text("NEURAL DIMENSION", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(26.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text("MAX PREMIUM", color = Lavender, fontWeight = FontWeight.Bold)
                Text("Your AI workspace, unified.", color = Color.White, fontSize = 18.sp)
                Text("Connect an AI provider in Settings to activate live responses.", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(24.dp))
            Orb()
            Spacer(Modifier.height(12.dp))
            Text("Hello, I'm MAX", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Your intelligent assistant", color = Muted)
            Spacer(Modifier.height(24.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("Chat with MAX", Icons.Default.Chat, Modifier.weight(1f), onChat)
                    ActionTile("Talk with MAX", Icons.Default.Mic, Modifier.weight(1f), onVoice)
                }
                Spacer(Modifier.height(10.dp))
                ActionTile("Explore all tools", Icons.Default.AutoAwesome, Modifier.fillMaxWidth(), onTools)
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth()) { Text("Quick access", color = Lavender, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Research", "Translate", "Documents").forEach {
                    AssistChip(onClick = onTools, label = { Text(it) })
                }
            }
        }
    }
}

@Composable
private fun ActionTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 58.dp).background(Color(0x553F2A56), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Lavender)
        Spacer(Modifier.width(9.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ChatScreen(vm: MaxViewModel) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        PageTitle("MAX Chat", "Your conversations stay on this device in this starter build.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.messages) { msg ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (msg.fromUser) Color(0xFF59358A) else Panel, shape = RoundedCornerShape(18.dp)) {
                        Text(msg.text, Modifier.padding(13.dp), color = Color.White)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message MAX…", color = Muted) },
                shape = RoundedCornerShape(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { if (input.isNotBlank()) { vm.addMessage(input); input = "" } }) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Lavender)
            }
        }
        Text("Live AI is inactive until a secure backend endpoint is configured.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun VoiceScreen() {
    var listening by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PageTitle("Talk with MAX", "Voice interface preview")
        Orb(220)
        Spacer(Modifier.height(18.dp))
        Text(if (listening) "Listening UI preview" else "Ready to talk", color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.height(18.dp))
        Button(onClick = { listening = !listening }) {
            Icon(Icons.Default.Mic, null)
            Spacer(Modifier.width(8.dp))
            Text(if (listening) "Stop preview" else "Start preview")
        }
        Text("Microphone capture and speech processing must be connected before production use.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ToolsScreen() {
    val tools = listOf(
        "Deep Research" to "Search and synthesize sources through a configured provider",
        "Web Dev Studio" to "Code editor, project files and isolated preview",
        "Image Generation" to "Requires an image generation provider",
        "Video Generation" to "Requires a supported video provider",
        "Translator" to "Text translation workflow",
        "PDF & Documents" to "Import and read supported documents",
        "Homework Helper" to "Explain learning exercises",
        "Tasks & Notes" to "Local task and note management",
        "Device Health" to "Battery, storage and permission overview"
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageTitle("MAX Tools", "Available modules and integration status") }
        items(tools) { (name, description) ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text(name, color = Lavender, fontWeight = FontWeight.Bold)
                Text(description, color = Muted, fontSize = 12.sp)
                Text("Setup required where noted", color = Color(0xFFFFD38A), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: MaxViewModel) {
    var endpoint by remember { mutableStateOf(vm.endpoint) }
    var mode by remember { mutableStateOf(vm.mode) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        PageTitle("Settings", "Configure MAX")
        OutlinedTextField(
            value = endpoint, onValueChange = { endpoint = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Secure backend URL") },
            placeholder = { Text("https://your-domain.example/api/chat") }
        )
        Spacer(Modifier.height(10.dp))
        Text("Response mode", color = Lavender)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "Fast", onClick = { mode = "Fast" }, label = { Text("Fast") })
            FilterChip(selected = mode == "Thinking", onClick = { mode = "Thinking" }, label = { Text("Thinking") })
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.saveSettings(endpoint, mode) }) { Text("Save settings") }
        Text("Keep provider API keys on a backend. Do not paste secret keys into a public APK.", color = Muted, fontSize = 12.sp)
    }
}
