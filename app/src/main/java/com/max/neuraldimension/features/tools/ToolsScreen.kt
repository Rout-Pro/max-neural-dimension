package com.max.neuraldimension.features.tools

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.app.ActivityManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.max.neuraldimension.core.theme.NdBlack
import com.max.neuraldimension.core.theme.NdLavender
import com.max.neuraldimension.core.theme.NdMuted
import com.max.neuraldimension.core.theme.NdNeon
import com.max.neuraldimension.core.theme.NdPanelDeep
import com.max.neuraldimension.core.theme.NdSilver
import com.max.neuraldimension.core.theme.NdViolet
import com.max.neuraldimension.data.db.TaskItem
import com.max.neuraldimension.features.chat.AppViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val tasks by vm.tasks.collectAsState()
    var newTask by remember { mutableStateOf("") }
    var torchOn by remember { mutableStateOf(false) }
    var torchStatus by remember { mutableStateOf<String?>(null) }

    fun setTorch(want: Boolean) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val id = cm.cameraIdList.firstOrNull()
            if (id == null) {
                torchStatus = "No camera flash on this device"
            } else {
                cm.setTorchMode(id, want)
                torchOn = want
                torchStatus = null
            }
        } catch (e: SecurityException) {
            torchStatus = "Camera permission needed for the flashlight"
        } catch (e: Exception) {
            torchStatus = "Flashlight unavailable: " + (e.message ?: e.javaClass.simpleName)
        }
    }
    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setTorch(true) else torchStatus = "Camera permission denied"
    }

    Column(
        Modifier.fillMaxSize().background(NdBlack).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("TOOLS", color = NdLavender, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)

        // Tasks (backed by Room; shown on the home hologram panel)
        ToolCard("Tasks") {
            tasks.filter { !it.done }.take(5).forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.toggleTask(t) }) { Text("Done", color = NdNeon, fontSize = 11.sp) }
                    Text(t.title, color = NdSilver, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    value = newTask, onValueChange = { newTask = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = NdSilver, fontSize = 13.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(NdLavender),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    decorationBox = { inner ->
                        if (newTask.isEmpty()) Text("Add a task...", color = NdMuted, fontSize = 13.sp)
                        inner()
                    }
                )
                TextButton(onClick = { vm.addTask(newTask); newTask = "" }) { Text("Add", color = NdLavender) }
            }
        }

        // Flashlight (real torch via CameraManager)
        ToolCard("Flashlight") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (torchOn) "On" else "Off", color = NdSilver, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = torchOn,
                    onCheckedChange = { want ->
                        if (!want) { setTorch(false) }
                        else {
                            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.CAMERA) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (granted) setTorch(true) else camPermission.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = NdViolet)
                )
            }
            torchStatus?.let { Text(it, color = NdMuted, fontSize = 11.sp) }
        }

        // Device info (real)
        ToolCard("Device") {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            Text("Battery: ${if (level >= 0) "$level%" else "unknown"}", color = NdSilver, fontSize = 13.sp)
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeGb = stat.availableBytes / (1024f * 1024f * 1024f)
            val totalGb = stat.totalBytes / (1024f * 1024f * 1024f)
            Text("Storage free: %.1f GB of %.1f GB".format(freeGb, totalGb), color = NdSilver, fontSize = 13.sp)
            val am2 = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am2.getMemoryInfo(it) }
            Text("RAM free: %.1f GB of %.1f GB".format(mi.availMem / (1024f*1024f*1024f), mi.totalMem / (1024f*1024f*1024f)), color = NdSilver, fontSize = 13.sp)
            Text("Model: ${Build.MODEL} - Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", color = NdSilver, fontSize = 13.sp)
            val upMin = SystemClock.elapsedRealtime() / 60000
            Text("Uptime: ${upMin / 60}h ${upMin % 60}m", color = NdSilver, fontSize = 13.sp)
        }

        // Open installed apps (real launches)
        ToolCard("Open apps") {
            val pm = context.packageManager
            val apps = remember {
                pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
                    .take(12)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                apps.forEach { (pkg, label) ->
                    TextButton(onClick = {
                        pm.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it) }
                    }) { Text(label, color = NdSilver, fontSize = 12.sp) }
                }
            }
            if (apps.isEmpty()) Text("No launchable apps found", color = NdMuted, fontSize = 12.sp)
        }

        // Volume + system shortcuts (real)
        ToolCard("Controls") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                }) { Text("Volume panel", color = NdLavender, fontSize = 12.sp) }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) {
                    Text("Wi-Fi", color = NdLavender, fontSize = 12.sp)
                }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                    Text("Bluetooth", color = NdLavender, fontSize = 12.sp)
                }
                TextButton(onClick = {
                    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    context.startActivity(i)
                }) { Text("Notifications", color = NdLavender, fontSize = 12.sp) }
                TextButton(onClick = {
                    val i = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    if (i.resolveActivity(context.packageManager) != null) context.startActivity(i)
                }) { Text("Camera", color = NdLavender, fontSize = 12.sp) }
            }
            val am3 = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am3.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            var vol by remember { mutableStateOf(am3.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Media volume", color = NdMuted, fontSize = 11.sp)
                Slider(
                    value = vol,
                    onValueChange = {
                        vol = it
                        am3.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
                    },
                    valueRange = 0f..maxVol.toFloat(),
                    colors = SliderDefaults.colors(activeTrackColor = NdViolet, thumbColor = NdNeon)
                )
            }
        }

        ToolCard("Coming in later phases") {
            Text(
                "Reminders and alarms, notes, calendar and weather, image and video generation. Each arrives only when it genuinely works.",
                color = NdMuted, fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun ToolCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(NdPanelDeep, RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Text(title, color = NdLavender, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}
