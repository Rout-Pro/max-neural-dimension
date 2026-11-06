package com.max.neuraldimension.features.studio

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.max.neuraldimension.core.theme.NdBlack
import com.max.neuraldimension.core.theme.NdLavender
import com.max.neuraldimension.core.theme.NdMuted
import com.max.neuraldimension.core.theme.NdNeon
import com.max.neuraldimension.core.theme.NdPanelDeep
import com.max.neuraldimension.core.theme.NdSilver
import com.max.neuraldimension.core.theme.NdViolet
import java.io.File

/**
 * Web Development Studio (blueprint Part 15): local projects, code editor,
 * WebView live preview with console capture, export/import. Fully on-device.
 */
@Composable
fun StudioScreen() {
    val context = LocalContext.current
    val store = remember { StudioStore(context) }
    var projects by remember { mutableStateOf(store.list()) }
    var open by remember { mutableStateOf<StudioProject?>(null) }
    var newName by remember { mutableStateOf("") }
    var templateMenu by remember { mutableStateOf(false) }
    var chosenTemplate by remember { mutableStateOf(StudioStore.Template.BLANK) }
    var notice by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.readBytes()?.decodeToString() ?: return@runCatching
                val base = File(uri.lastPathSegment ?: "imported").nameWithoutExtension.ifBlank { "imported" }
                var name = base; var n = 2
                while (store.exists(name)) { name = "$base-$n"; n++ }
                val p = store.create(name, StudioStore.Template.BLANK)
                store.write(File(p.dir, "index.html"), text)
                projects = store.list()
                notice = "Imported $name"
            }.onFailure { notice = "Import failed: ${it.message}" }
        }
    }

    val current = open
    if (current == null) {
        Column(Modifier.fillMaxSize().background(NdBlack).statusBarsPadding().padding(18.dp)) {
            Text("WEB STUDIO", color = NdLavender, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Local web projects - HTML, CSS, JavaScript. Nothing leaves the device.",
                color = NdMuted, fontSize = 11.sp)
            if (notice.isNotBlank()) {
                Spacer(Modifier.height(4.dp)); Text(notice, color = NdNeon, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = newName, onValueChange = { newName = it },
                    textStyle = TextStyle(color = NdSilver, fontSize = 14.sp),
                    cursorBrush = SolidColor(NdNeon),
                    modifier = Modifier.weight(1f).background(NdPanelDeep, RoundedCornerShape(10.dp)).padding(12.dp),
                    decorationBox = { inner ->
                        if (newName.isEmpty()) Text("New project name...", color = NdMuted, fontSize = 13.sp)
                        inner()
                    }
                )
                Spacer(Modifier.width(8.dp))
                Box {
                    TextButton(onClick = { templateMenu = true }) { Text(chosenTemplate.label, color = NdLavender, fontSize = 12.sp) }
                    DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                        StudioStore.Template.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.label) }, onClick = { chosenTemplate = t; templateMenu = false })
                        }
                    }
                }
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        val p = store.create(newName, chosenTemplate)
                        newName = ""
                        projects = store.list()
                        open = p
                        notice = ""
                    }
                }) { Icon(Icons.Default.Add, contentDescription = "Create project", tint = NdNeon) }
            }
            TextButton(onClick = { importLauncher.launch(arrayOf("text/html", "text/*")) }) {
                Text("Import .html", color = NdLavender, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                projects.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .background(NdPanelDeep, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { open = p }, modifier = Modifier.weight(1f)) {
                            Text(p.name, color = NdSilver, fontSize = 14.sp)
                        }
                        IconButton(onClick = { store.delete(p); projects = store.list() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete project", tint = NdMuted)
                        }
                    }
                }
                if (projects.isEmpty()) {
                    Text("No projects yet - create one above.", color = NdMuted, fontSize = 12.sp)
                }
            }
        }
    } else {
        StudioEditor(store, current, onClose = { open = null; projects = store.list() })
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun StudioEditor(store: StudioStore, project: StudioProject, onClose: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(project.files()) }
    var activeIdx by remember { mutableIntStateOf(files.indexOfFirst { it.name == "index.html" }.coerceAtLeast(0)) }
    var code by remember { mutableStateOf(if (files.isEmpty()) "" else store.read(files[activeIdx])) }
    var dirty by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    val consoleLines = remember { mutableStateListOf<String>() }
    var showConsole by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var previewWidth by remember { mutableIntStateOf(0) } // 0=full, else dp

    fun save() {
        if (files.isNotEmpty()) { store.write(files[activeIdx], code); dirty = false; notice = "Saved ${files[activeIdx].name}" }
    }

    Column(Modifier.fillMaxSize().background(NdBlack).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            IconButton(onClick = { save(); onClose() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to projects", tint = NdLavender)
            }
            Text(project.name, color = NdLavender, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                consoleLines.clear(); showConsole = false; showPreview = true
            }) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = NdNeon)
                Text("Run", color = NdNeon, fontSize = 13.sp)
            }
            IconButton(onClick = {
                save()
                val html = store.combinedHtml(project)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, project.name + ".html")
                    put(MediaStore.Downloads.MIME_TYPE, "text/html")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Downloads.RELATIVE_PATH, "Download/NeuralDimension")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(html.toByteArray()) }
                        notice = "Exported to Downloads/NeuralDimension/${project.name}.html"
                    }.onFailure { notice = "Export failed: ${it.message}" }
                } else notice = "Export failed"
            }) {
                Icon(Icons.Default.Share, contentDescription = "Export project as single HTML file", tint = NdLavender)
            }
        }
        if (notice.isNotBlank()) Text(notice, color = NdNeon, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp))

        // file explorer row
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            files.forEachIndexed { i, f ->
                TextButton(onClick = {
                    save()
                    activeIdx = i; code = store.read(files[i]); dirty = false
                }) {
                    Text(f.name, color = if (i == activeIdx) NdNeon else NdMuted, fontSize = 12.sp)
                }
            }
            BasicTextField(
                value = newFile, onValueChange = { newFile = it },
                textStyle = TextStyle(color = NdSilver, fontSize = 12.sp),
                cursorBrush = SolidColor(NdNeon),
                modifier = Modifier.width(110.dp).background(NdPanelDeep, RoundedCornerShape(8.dp)).padding(8.dp),
                decorationBox = { inner -> if (newFile.isEmpty()) Text("new file...", color = NdMuted, fontSize = 11.sp); inner() }
            )
            IconButton(onClick = {
                store.addFile(project, newFile)?.let {
                    newFile = ""; files = project.files()
                }
            }) { Icon(Icons.Default.Add, contentDescription = "Add file", tint = NdMuted) }
        }

        if (showPreview) {
            // live preview + console
            Column(Modifier.fillMaxWidth().weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                    Text("Preview", color = NdMuted, fontSize = 11.sp)
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = { previewWidth = if (previewWidth == 0) 380 else 0 }) {
                        Text(if (previewWidth == 0) "Width: full" else "Width: 380dp", color = NdLavender, fontSize = 11.sp)
                    }
                    TextButton(onClick = { showConsole = !showConsole }) {
                        Text("Console (${consoleLines.size})", color = NdLavender, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showPreview = false }) { Text("Edit", color = NdSilver, fontSize = 11.sp) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = WebViewClient()
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                        message?.let {
                                            consoleLines.add("[${it.messageLevel()}] ${it.message()} (line ${it.lineNumber()})")
                                        }
                                        return true
                                    }
                                }
                                loadDataWithBaseURL("https://studio.local/", store.combinedHtml(project),
                                    "text/html", "utf-8", null)
                            }
                        },
                        update = { },
                        modifier = if (previewWidth == 0) Modifier.fillMaxSize() else Modifier.width(previewWidth.dp).fillMaxSize()
                    )
                }
                if (showConsole) {
                    Column(
                        Modifier.fillMaxWidth().height(140.dp).background(NdPanelDeep).padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("CONSOLE", color = NdMuted, fontSize = 10.sp)
                        consoleLines.forEach { Text(it, color = NdSilver, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        if (consoleLines.isEmpty()) Text("(no console output)", color = NdMuted, fontSize = 11.sp)
                    }
                }
            }
        } else {
            // code editor
            BasicTextField(
                value = code,
                onValueChange = { code = it; dirty = true },
                textStyle = TextStyle(color = NdSilver, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
                cursorBrush = SolidColor(NdNeon),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp)
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (dirty) "Unsaved changes" else "Saved", color = if (dirty) NdViolet else NdMuted, fontSize = 11.sp)
                TextButton(onClick = { save() }) { Text("Save", color = NdNeon, fontSize = 13.sp) }
            }
        }
    }
}
