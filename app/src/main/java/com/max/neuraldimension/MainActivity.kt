package com.max.neuraldimension

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.max.neuraldimension.core.theme.NdBlack
import com.max.neuraldimension.core.theme.NdLavender
import com.max.neuraldimension.core.theme.NdMuted
import com.max.neuraldimension.core.theme.NdTheme
import com.max.neuraldimension.features.chat.AppViewModel
import com.max.neuraldimension.features.chat.ChatScreen
import com.max.neuraldimension.features.home.HomeScreen
import com.max.neuraldimension.features.settings.SettingsScreen
import com.max.neuraldimension.features.studio.StudioScreen
import com.max.neuraldimension.features.tools.ToolsScreen
import com.max.neuraldimension.features.voice.VoiceScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    CHAT("Chat", Icons.Default.Chat),
    VOICE("Voice", Icons.Default.Mic),
    TOOLS("Tools", Icons.Default.AutoAwesome),
    STUDIO("Studio", Icons.Default.Code),
    SETTINGS("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // CI-only: seed backend config for automated end-to-end tests. Compiled
        // out of the distributed APK (ND_TEST_HOOK=false there).
        if (BuildConfig.ND_TEST_HOOK) {
            val url = intent.getStringExtra("nd_backend_url").orEmpty()
            val tok = intent.getStringExtra("nd_app_token").orEmpty()
            if (url.isNotBlank()) {
                val repo = com.max.neuraldimension.data.prefs.SettingsRepository(applicationContext)
                kotlinx.coroutines.runBlocking {
                    repo.update { it.copy(backendUrl = url.trim(), appToken = tok.trim()) }
                }
            }
        }
        setContent {
            NdTheme {
                val vm: AppViewModel = viewModel()
                var tab by remember { mutableStateOf(Tab.HOME) }
                Scaffold(
                    containerColor = NdBlack,
                    bottomBar = {
                        NavigationBar(containerColor = Color(0xFF0E0918)) {
                            Tab.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item,
                                    onClick = { tab = item },
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = NdLavender,
                                        selectedTextColor = NdLavender,
                                        indicatorColor = Color(0xFF2E1A52),
                                        unselectedIconColor = NdMuted,
                                        unselectedTextColor = NdMuted
                                    )
                                )
                            }
                        }
                    }
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad).background(NdBlack)) {
                        when (tab) {
                            Tab.HOME -> HomeScreen(
                                vm,
                                onOpenChat = { tab = Tab.CHAT },
                                onOpenVoice = { tab = Tab.VOICE },
                                onOpenTools = { tab = Tab.TOOLS },
                                onOpenSettings = { tab = Tab.SETTINGS }
                            )
                            Tab.CHAT -> ChatScreen(vm, onOpenSettings = { tab = Tab.SETTINGS })
                            Tab.VOICE -> VoiceScreen(vm)
                            Tab.TOOLS -> ToolsScreen(vm)
                            Tab.STUDIO -> StudioScreen()
                            Tab.SETTINGS -> SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
