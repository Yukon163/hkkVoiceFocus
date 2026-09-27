@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.hkk.voicefocus.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hkk.voicefocus.*
import com.hkk.voicefocus.R
import com.hkk.voicefocus.ui.radiant.components.*
import com.hkk.voicefocus.audio.*
import com.hkk.voicefocus.data.*
import com.hkk.voicefocus.processing.ProcessingService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

@Composable
fun FocusApp(vm: FocusViewModel = viewModel()) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val repo = vm.repo
    val projects by repo.projects.collectAsStateWithLifecycle()
    val selected by repo.selected.collectAsStateWithLifecycle()
    val task by repo.task.collectAsStateWithLifecycle()
    val modelReady by repo.modelReady.collectAsStateWithLifecycle()
    val storageReady by repo.storageReady.collectAsStateWithLifecycle()
    val playback by repo.player.state.collectAsStateWithLifecycle()
    val message by repo.message.collectAsStateWithLifecycle()
    val exported by repo.exported.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var exportSheet by remember { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf("m4a") }
    var exportId by rememberSaveable { mutableStateOf("") }
    var customExport by rememberSaveable { mutableStateOf(false) }
    var afterStorage by rememberSaveable { mutableStateOf("") }
    var afterStorageId by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf<Project?>(null) }
    var numeric by remember { mutableStateOf<Stem?>(null) }
    var numericValue by remember { mutableStateOf("") }
    var licensesVisible by remember { mutableStateOf(false) }
    var compare by remember(selected) { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val project = projects.firstOrNull { it.id == selected }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun start(action: String, id: String? = null, uri: android.net.Uri? = null, format: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        ProcessingService.start(context, action, id, uri, format)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = MediaImportPicker.selectedUri(result.data)
            if (uri != null) {
                val flags = result.data?.flags ?: 0
                if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                tab = 0; start("import", uri = uri)
            } else repo.message.value = "未收到可读取的文件，请一次选择一个音频或视频。"
        }
    }
    fun launchMediaPicker() {
        val preferred = MediaImportPicker.preferredIntent(context)
        try {
            importer.launch(preferred)
        } catch (_: ActivityNotFoundException) {
            runCatching { importer.launch(MediaImportPicker.systemIntent()) }
                .onFailure { repo.message.value = "找不到可用的文件选择器，请安装或启用文件管理器。" }
        } catch (_: SecurityException) {
            runCatching { importer.launch(MediaImportPicker.systemIntent()) }
                .onFailure { repo.message.value = "文件选择器无法打开，请检查文件管理器的权限。" }
        }
    }
    val modelImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) start("importModel", uri = uri) }
    val storagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) start("storage", uri = uri) else afterStorage = ""
    }
    fun authorizeStorage(action: String = "", id: String = "") {
        afterStorage = action; afterStorageId = id
        storagePicker.launch(SoundsStorage.INITIAL_URI)
    }
    fun importMedia() {
        if (storageReady) launchMediaPicker() else authorizeStorage("import")
    }
    fun openProject(id: String) {
        // Opening the current project is navigation too; its selected ID will not emit again.
        if (repo.selected.value != id) repo.select(id)
        tab = 0
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { if (result.resultCode == android.app.Activity.RESULT_OK) start("export", exportId, it, exportFormat) }
    }
    LaunchedEffect(selected) { if (selected != null) tab = 0 }
    LaunchedEffect(tab, selected) { listState.scrollToItem(0) }
    LaunchedEffect(task.active) { if (task.active) listState.animateScrollToItem(0) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); if (repo.message.value == it) repo.message.value = null } }
    LaunchedEffect(playback.error) { playback.error?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(storageReady, task.active) {
        if (storageReady && !task.active && afterStorage.isNotEmpty()) {
            val action = afterStorage; afterStorage = ""
            when (action) {
                "import" -> launchMediaPicker()
                "separate" -> start("separate", afterStorageId)
                "export" -> { customExport = false; exportSheet = true }
            }
        }
    }
    LaunchedEffect(Unit) { if (storageReady && projects.isEmpty() && !task.active) start("storageRefresh") }

    FocusTheme(theme) {
        RadiantHost { capture ->
            LazyColumn(Modifier.fillMaxSize().then(capture).statusBarsPadding(), state = listState, contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 126.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(appName.uppercase(Locale.ROOT), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            Text(listOf("可可的声音工作室", "我的作品", "设置")[tab], fontSize = 29.sp, fontWeight = FontWeight.Bold)
                        }
                        if (tab == 1) {
                            IconButton(onClick = { importMedia() }, enabled = !task.active) {
                                Icon(Icons.Rounded.Add, "导入音频或视频", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        } else Icon(Icons.Rounded.GraphicEq, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (task.active) item {
                    GlassPanel {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.title, fontWeight = FontWeight.SemiBold)
                                Text("在设备上处理 · ${(task.progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { ProcessingService.cancel(context) }) { Text("取消") }
                        }
                        LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), drawStopIndicator = {})
                    }
                }
                if (!storageReady && !task.active && tab != 2) item {
                    GlassPanel {
                        Text("把作品保存在 Sounds", fontWeight = FontWeight.SemiBold)
                        Text("授权一次后，默认保存到手机公共 Sounds 文件夹，已有作品也会同步到那里。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Button(onClick = { authorizeStorage() }) { Text("授权 Sounds 文件夹") }
                    }
                }
                when (tab) {
                    0 -> {
                        if (project == null) {
                            item {
                                GlassPanel {
                                    Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.GraphicEq, null, Modifier.size(84.dp), MaterialTheme.colorScheme.primary)
                                    }
                                    Text("让每一种声音\n都有合适的位置", fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
                                    Text("导入弹唱、录音或视频，分离人声与乐器，调到你喜欢的比例。", color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 23.sp)
                                    Button(onClick = { importMedia() }, enabled = !task.active, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp)) {
                                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("导入音频或视频", fontWeight = FontWeight.SemiBold)
                                    }
                                    Text("MP3 · M4A · WAV · FLAC · MP4 · 更多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    listOf("钢琴弹唱", "吉他弹唱", "尤克里里").forEach { label ->
                                        Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f), shape = CircleShape) { Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 12.sp) }
                                    }
                                }
                            }
                            item {
                                GlassPanel {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.OfflineBolt, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(if (modelReady) "离线模型已就绪" else "先准备你的离线工作室", fontWeight = FontWeight.SemiBold)
                                            Text(if (modelReady) "声音留在手机，处理无需上传。" else "首次下载约 130 MB，之后可离线使用。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (!modelReady) TextButton(onClick = { tab = 2 }) { Text("准备模型  →") }
                                }
                            }
                        } else {
                            item {
                                GlassPanel {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                            Icon(if (project.hasVideo) Icons.Rounded.Movie else Icons.Rounded.AudioFile, null, Modifier.padding(15.dp).size(26.dp), MaterialTheme.colorScheme.primary)
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(if (project.frames > 0) "${time(project.frames)} · ${project.status}" else project.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { importMedia() }, enabled = !task.active) { Icon(Icons.Rounded.Add, "导入新文件") }
                                    }
                                    if (project.error != null) Text(project.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (!project.ready) {
                                item {
                                    GlassPanel {
                                        Text("分轨方式", fontWeight = FontWeight.SemiBold)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            listOf("duet" to "弹唱 · 两轨", "multi" to "多乐器 · 六轨").forEach { (value, label) ->
                                                FilterChip(selected = project.mode == value, onClick = { vm.change(project.copy(mode = value)) }, label = { Text(label) }, enabled = !task.active)
                                            }
                                        }
                                        if (project.mode == "duet") {
                                            Text("保留完整伴奏，适合钢琴、吉他和尤克里里弹唱。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                listOf("自动", "钢琴", "吉他", "尤克里里").forEach { instrument ->
                                                    FilterChip(selected = project.instrument == instrument, onClick = { vm.change(project.copy(instrument = instrument)) }, label = { Text(instrument) }, enabled = !task.active)
                                                }
                                            }
                                            Text("乐器选项用于标记伴奏，分离时会保留所有非人声。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else Text("独立调节人声、吉他、钢琴、鼓、贝斯和其他声部。尤克里里可能落入吉他或其他轨，不能保证独立提取。", fontSize = 13.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Button(onClick = { if (!modelReady) tab = 2 else if (!storageReady) authorizeStorage("separate", project.id) else start("separate", project.id) }, enabled = !task.active, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                                            Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(if (modelReady) "开始分离声音" else "先准备离线模型")
                                        }
                                    }
                                }
                            } else {
                                item {
                                    GlassPanel {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("试听你的配比", fontWeight = FontWeight.SemiBold)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("对比原声", fontSize = 12.sp)
                                                Spacer(Modifier.width(8.dp))
                                                Switch(compare, onCheckedChange = { compare = it; repo.player.original = it }, enabled = !task.active,
                                                    colors = focusSwitchColors())
                                            }
                                        }
                                        AudioWave(project.waveform, playback.frame.toFloat() / project.frames, !task.active) { repo.player.seek((it * project.frames).toLong()) }
                                        Slider(value = (playback.frame.toFloat() / project.frames).coerceIn(0f, 1f), onValueChange = { repo.player.seek((it * project.frames).toLong()) }, enabled = !task.active,
                                            track = { state -> SliderDefaults.Track(sliderState = state, enabled = !task.active, drawStopIndicator = null) },
                                            modifier = Modifier.semantics { contentDescription = "播放进度" })
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(time(playback.frame), style = MaterialTheme.typography.labelMedium)
                                            FilledIconButton(onClick = { repo.player.play(repo.store, project) }, enabled = !task.active, modifier = Modifier.size(52.dp)) {
                                                Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "暂停" else "播放", Modifier.size(30.dp))
                                            }
                                            Text(time(project.frames), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                item {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("focus" to "人声突出", "balance" to "原始比例", "voice" to "只听人声", "music" to "只听伴奏").forEach { (key, label) ->
                                            SuggestionChip(onClick = { vm.preset(project, key) }, label = { Text(label, fontSize = 12.sp) }, enabled = !task.active)
                                        }
                                    }
                                }
                                item {
                                    GlassPanel {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("自由混音", fontWeight = FontWeight.SemiBold)
                                            Text("${project.stems.size} 条音轨", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                        }
                                        project.stems.forEachIndexed { index, stem ->
                                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                                            StemControl(stem, !task.active, onGain = { vm.gains(repo.get(project.id), stem.id, it) }, onFinished = { vm.save(project) },
                                                onMute = { vm.change(project.copy(stems = project.stems.map { if (it.id == stem.id) it.copy(muted = !it.muted) else it })) },
                                                onNumber = { numeric = stem; numericValue = (stem.gain * 100).roundToInt().toString() })
                                        }
                                    }
                                }
                                item {
                                    Button(onClick = { repo.player.pause(); customExport = false; if (storageReady) exportSheet = true else authorizeStorage("export") }, enabled = !task.active, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                                        Icon(Icons.Rounded.FileDownload, null); Spacer(Modifier.width(10.dp)); Text("导出作品", fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(onClick = { repo.player.reset(); vm.change(project.copy(status = "待分离", stems = emptyList())) }, enabled = !task.active, modifier = Modifier.fillMaxWidth()) { Text("更换分轨方式") }
                                }
                            }
                        }
                        if (exported != null) item {
                            GlassPanel {
                                Text("作品已导出", fontWeight = FontWeight.SemiBold)
                                Text("已保存到 ${exported!!.location}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = {
                                    val result = exported!!
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", result.file)
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(result.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享作品"))
                                }) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("分享") }
                            }
                        }
                    }
                    1 -> {
                        if (projects.isEmpty()) item {
                            GlassPanel {
                                Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("把声音变成作品", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                Text("导入后的文件和配比会保存在这里，随时继续调整。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { importMedia() }, enabled = !task.active) { Text("导入音频或视频") }
                            }
                        }
                        projects.forEach { p -> item(key = p.id) {
                            GlassPanel(onClick = { openProject(p.id) }, enabled = !task.active) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                        Text("${SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(p.created))} · ${p.status}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (p.frames > 0) Text("${time(p.frames)} · ${if (p.mode == "multi") "六轨分离" else "弹唱分离"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { delete = p }, enabled = !task.active) { Icon(Icons.Rounded.DeleteOutline, "删除 ${p.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        } }
                    }
                    2 -> {
                        item {
                            GlassPanel {
                                Text("默认保存位置", fontWeight = FontWeight.SemiBold)
                                Text(SoundsStorage.DISPLAY_PATH, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                Text("导出文件直接保存在 Sounds；作品工程、原始文件和独立 WAV 音轨位于 Sounds/VoiceFocus/Projects/。", fontSize = 13.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = { authorizeStorage() }, enabled = !task.active, modifier = Modifier.fillMaxWidth()) { Text(if (storageReady) "重新授权 Sounds" else "授权 Sounds 文件夹") }
                                if (storageReady) TextButton(onClick = { start("storageRefresh") }, enabled = !task.active, modifier = Modifier.fillMaxWidth()) { Text("恢复或同步作品") }
                            }
                        }
                        item {
                            GlassPanel {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (modelReady) Icons.Rounded.CheckCircle else Icons.Rounded.CloudDownload, null, Modifier.size(28.dp), MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column { Text("离线声音模型", fontWeight = FontWeight.SemiBold); Text(if (modelReady) "已就绪 · 可离线使用" else "首次下载约 130 MB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                Text("模型下载后，导入、分离、试听和导出都在手机上完成。音视频不会上传。", lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!modelReady) Button(onClick = { start("download") }, enabled = !task.active, modifier = Modifier.fillMaxWidth()) { Text("下载离线模型") }
                                OutlinedButton(onClick = { modelImporter.launch(arrayOf("*/*")) }, enabled = !task.active, modifier = Modifier.fillMaxWidth()) { Text("从文件导入模型") }
                                Text("模型：HT-Demucs 6s。分离时需要较多内存；处理长文件时请保持足够电量和存储空间。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            GlassPanel {
                                Text("外观", fontWeight = FontWeight.SemiBold)
                                Text("固定青蓝主题，仅切换深浅色。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                                        FilterChip(selected = theme == value, onClick = { vm.theme(value) }, label = { Text(label) })
                                    }
                                }
                            }
                        }
                        item {
                            GlassPanel {
                                Text("使用提示", fontWeight = FontWeight.SemiBold)
                                Text("• 弹唱模式保留完整伴奏，适合钢琴、吉他、尤克里里。\n• 六轨模式可单独调节吉他和钢琴。模型没有独立的尤克里里声部。\n• 分离可能有串音，现场混响越多越难分离。\n• 100% 是分离后音轨的原始振幅；调到 0% 即静音。\n• WAV/M4A 导出当前配比，ZIP 导出原始分轨。\n• 导入格式取决于设备解码能力。视频导出保留画面，只重编码音轨。", fontSize = 13.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            TextButton(onClick = { licensesVisible = true }) { Text("模型来源与开源许可") }
                            Text("$appName ${BuildConfig.VERSION_NAME}\n声音留在你的设备里。", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }
            RadiantNavigation(tab, { tab = it }) {
                listOf(Icons.Rounded.Tune to "工作室", Icons.Rounded.LibraryMusic to "作品", Icons.Rounded.Settings to "设置").forEachIndexed { i, (icon, label) ->
                    LiquidBottomTab(onClick = { tab = i }, selected = tab == i) {
                        Icon(icon, label)
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 95.dp, start = 16.dp, end = 16.dp))
        }
        if (exportSheet && project != null) ModalBottomSheet(onDismissRequest = { exportSheet = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("导出作品", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(if (customExport) "选择格式后指定保存位置" else SoundsStorage.DISPLAY_PATH, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!customExport, { customExport = false }, { Text("保存到 Sounds") })
                    FilterChip(customExport, { customExport = true }, { Text("另存为…") })
                }
                listOf(Triple("m4a", "M4A 音频", "体积小，适合分享"), Triple("wav", "WAV 音频", "无损 PCM，方便后续剪辑"), Triple("mp4", "MP4 视频", "保留原画面，替换为你的混音"), Triple("zip", "独立音轨 ZIP", "打包原始分轨，不应用配比")).forEach { (format, label, detail) ->
                    val enabled = format != "mp4" || project.hasVideo
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(enabled = enabled) {
                        exportFormat = format; exportId = project.id; exportSheet = false
                        val mime = when (format) { "wav" -> "audio/wav"; "m4a" -> "audio/mp4"; "mp4" -> "video/mp4"; else -> "application/zip" }
                        if (customExport) save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, "${project.name.substringBeforeLast('.')}_VoiceFocus.$format"))
                        else start("export", project.id, format = format)
                    }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (format == "mp4") Icons.Rounded.Movie else Icons.Rounded.AudioFile, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(16.dp))
                        Column { Text(label, fontWeight = FontWeight.SemiBold); Text(if (enabled) detail else "原文件没有视频画面", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        delete?.let { p -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除这个项目？") }, text = { Text("将删除 Sounds 中的这份工程及应用工作缓存。已导出的音视频文件会保留。") }, confirmButton = {
            TextButton(onClick = { vm.delete(p); delete = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
        }, dismissButton = { TextButton(onClick = { delete = null }) { Text("保留") } }) }
        if (licensesVisible) {
            val text = remember { context.assets.list("licenses").orEmpty().joinToString("\n\n") { name ->
                "$name\n" + context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            } }
            AlertDialog(onDismissRequest = { licensesVisible = false }, title = { Text("开源许可") },
                text = { Text(text, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp) },
                confirmButton = { TextButton(onClick = { licensesVisible = false }) { Text("关闭") } })
        }
        numeric?.let { stem -> AlertDialog(onDismissRequest = { numeric = null }, title = { Text("${stem.name}音量") }, text = {
            OutlinedTextField(numericValue, { numericValue = it }, label = { Text("音量百分比（0–200）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), suffix = { Text("%") })
        }, confirmButton = { TextButton(enabled = numericValue.toFloatOrNull()?.let { it.isFinite() && it in 0f..200f } == true, onClick = {
            if (project != null) { vm.gains(project, stem.id, numericValue.toFloat() / 100); vm.save(project) }; numeric = null
        }) { Text("应用") } }, dismissButton = { TextButton(onClick = { numeric = null }) { Text("取消") } }) }
    }
}

@Composable
private fun StemControl(stem: Stem, enabled: Boolean, onGain: (Float) -> Unit, onFinished: () -> Unit, onMute: () -> Unit, onNumber: () -> Unit) {
    val color = when (stem.id) { "guitar" -> Color(0xFFAE763A); "piano" -> Color(0xFF7E77B1); "drums" -> Color(0xFFBD6A60); "bass" -> Color(0xFF579281); else -> MaterialTheme.colorScheme.primary }
    val sliderColors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(stem.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            IconButton(onClick = onMute, enabled = enabled) { Icon(if (stem.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "${stem.name}${if (stem.muted) "取消静音" else "静音"}", Modifier.size(20.dp), if (stem.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = onNumber, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("${(stem.gain * 100).roundToInt()}%", fontWeight = FontWeight.Bold, color = color) }
        }
        Slider(value = stem.gain, onValueChange = onGain, onValueChangeFinished = onFinished, valueRange = 0f..2f, enabled = enabled,
            colors = sliderColors,
            track = { state -> SliderDefaults.Track(sliderState = state, enabled = enabled, colors = sliderColors, drawStopIndicator = null) },
            modifier = Modifier.semantics { contentDescription = "${stem.name}音量" })
    }
}

@Composable
private fun AudioWave(peaks: List<Float>, progress: Float, enabled: Boolean, onSeek: (Float) -> Unit) {
    val active = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.primary.copy(alpha = .25f)
    Canvas(Modifier.fillMaxWidth().height(78.dp).semantics { contentDescription = "音频波形" }.pointerInput(enabled) {
        if (enabled) detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
    }) {
        if (peaks.isEmpty()) return@Canvas
        val count = min(90, peaks.size)
        val maxPeak = peaks.max().coerceAtLeast(.001f)
        for (i in 0 until count) {
            val value = peaks[i * peaks.size / count] / maxPeak
            val h = max(3.dp.toPx(), sqrt(value) * size.height * .9f)
            val x = (i + .5f) / count * size.width
            drawLine(if (i.toFloat() / count <= progress) active else rest, Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

private fun time(frames: Long): String {
    val seconds = frames / SAMPLE_RATE
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
