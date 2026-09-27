package com.hkk.voicefocus.processing

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hkk.voicefocus.*
import com.hkk.voicefocus.audio.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var separator: DemucsSeparator? = null
    private val repo get() = repository
    private var lastNotification = 0L
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("processing", "音频处理", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { cancel(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        val action = intent?.action ?: return START_NOT_STICKY
        val id = intent.getStringExtra("id")
        repo.player.pause()
        repo.exported.value = null
        val initial = notification("正在准备", 0f)
        if (Build.VERSION.SDK_INT >= 35) startForeground(7, initial, if (action in listOf("separate", "export")) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(7, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(7, initial)
        repo.task.value = TaskState(true, "正在准备", 0f, id)
        job = scope.launch {
            val wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicefocus:process")
            wake.acquire(6 * 60 * 60 * 1000L)
            val ensure: () -> Unit = { ensureActive() }
            try {
                when (action) {
                    "import" -> {
                        update("正在导入文件", 0f)
                        val p = repo.store.import(this@ProcessingService, Uri.parse(intent.getStringExtra("uri")), ensure)
                        repo.update(p); repo.select(p.id)
                        if (repo.storageReady.value) repo.store.sounds.archive(repo.store, p, ensure) { update("保存工程到 Sounds", it) }
                        repo.message.value = "导入成功，选择分轨方式后开始分离。"
                    }
                    "storage", "storageRefresh" -> {
                        repo.storageReady.value = false
                        if (action == "storage") repo.store.sounds.authorize(Uri.parse(intent.getStringExtra("uri")))
                        update("正在读取 Sounds 中的工程", 0f)
                        repo.store.sounds.restore(repo.store, ensure)
                        val projects = repo.store.all()
                        projects.forEachIndexed { index, p ->
                            ensure()
                            if (repo.store.sounds.needsArchive(p)) repo.store.sounds.archive(repo.store, p, ensure) {
                                update("正在将作品保存到 Sounds", (index + it) / projects.size.coerceAtLeast(1))
                            }
                        }
                        repo.projects.value = repo.store.all()
                        repo.storageReady.value = true
                        repo.message.value = "默认目录已设为 Sounds，作品工程已同步。"
                    }
                    "download" -> {
                        repo.model.download(ensure) { update("下载离线模型 · 130 MB", it) }
                        repo.modelReady.value = true
                        repo.message.value = "模型已就绪，后续分离无需联网。"
                    }
                    "importModel" -> {
                        update("正在校验并导入模型", 0f)
                        repo.model.import(Uri.parse(intent.getStringExtra("uri")), ensure)
                        repo.modelReady.value = true
                        repo.message.value = "模型导入成功。"
                    }
                    "separate" -> separate(requireNotNull(id), ensure)
                    "export" -> {
                        val p = repo.get(requireNotNull(id))
                        val format = intent.getStringExtra("format") ?: "m4a"
                        val customDestination = intent.getStringExtra("uri")?.let(Uri::parse)
                        if (customDestination == null) require(repo.store.sounds.granted) { "请先授权 Sounds 文件夹" }
                        var destination = customDestination
                        val folder = File(cacheDir, "exports").apply { mkdirs() }
                        val file = File(folder, "VoiceFocus-${System.currentTimeMillis()}.$format")
                        val mime = when (format) { "wav" -> "audio/wav"; "mp4" -> "video/mp4"; "zip" -> "application/zip"; else -> "audio/mp4" }
                        try {
                            MediaExporter().export(repo.store, p, format, file, ensure) { update("正在导出", it * .9f) }
                            if (destination == null) {
                                val name = p.name.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
                                val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                destination = repo.store.sounds.createExport("${name}_VoiceFocus_$date.$format", mime)
                            }
                            val savedUri = requireNotNull(destination)
                            contentResolver.openOutputStream(savedUri, "wt")?.use { out ->
                                file.inputStream().use { input ->
                                    val bytes = ByteArray(256 * 1024)
                                    var copied = 0L
                                    while (true) {
                                        ensure()
                                        val n = input.read(bytes)
                                        if (n < 0) break
                                        out.write(bytes, 0, n); copied += n
                                        update("正在保存", .9f + .1f * copied / file.length())
                                    }
                                }
                            } ?: error("无法写入所选位置")
                            val location = if (customDestination == null) com.hkk.voicefocus.data.SoundsStorage.DISPLAY_PATH else "你选择的位置"
                            repo.exported.value = ExportResult(file, mime, savedUri, location)
                            repo.message.value = "导出成功，已保存到 $location"
                        } catch (e: Exception) {
                            file.delete()
                            destination?.let { runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, it) } }
                            throw e
                        }
                    }
                }
            } catch (e: CancellationException) {
                id?.let { if (action == "separate") repo.update(repo.get(it).copy(status = "已中断", error = "处理已取消，可以重试。")) }
                repo.message.value = "已取消当前任务。"
            } catch (e: Throwable) {
                android.util.Log.e("VoiceFocus", "Processing failed", e)
                val cancelled = !isActive
                val detail = if (cancelled) "处理已取消，可以重试。" else if (e is OutOfMemoryError) "可用内存不足，请关闭其他应用后重试。" else e.message ?: "处理失败，请重试。"
                id?.let { if (action == "separate") repo.update(repo.get(it).copy(status = if (cancelled) "已中断" else "处理失败", error = detail)) }
                repo.message.value = detail
            } finally {
                separator = null
                repo.modelReady.value = repo.model.ready
                repo.storageReady.value = repo.store.sounds.granted
                repo.task.value = TaskState()
                if (wake.isHeld) wake.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun separate(id: String, ensure: () -> Unit) {
        require(repo.model.ready) { "请先在模型页面下载或导入离线模型。" }
        require(repo.store.sounds.granted) { "请先授权 Sounds 文件夹，以便保存作品。" }
        update("正在校验模型", .01f)
        repo.model.verify(check = ensure)
        var p = repo.get(id)
        repo.update(p.copy(status = "解码中", error = null))
        val decoded = MediaDecoder().decode(repo.store.source(p), repo.store.pcm(p), ensure) { update("正在读取音轨", .03f + it * .12f) }
        p = p.copy(frames = decoded.frames, hasVideo = decoded.hasVideo, waveform = decoded.waveform, status = "分离中", error = null)
        repo.update(p)
        val need = p.frames * 8 * (if (p.mode == "multi") 7 else 3) + 200_000_000
        require(StatFs(filesDir.absolutePath).availableBytes > need) { "剩余存储不足，分离这段音频至少还需约 ${need / 1_048_576} MB。" }
        update("正在加载分离模型", .16f)
        val engine = DemucsSeparator().also { separator = it }
        val stems = engine.separate(repo.model.file, repo.store, p, ensure) { update("正在分离声音", .16f + it * .72f) }
        val completed = p.copy(status = "已完成", stems = stems, error = null, modified = System.currentTimeMillis())
        // Persist before publishing a completed project, so it is recoverable outside app data.
        repo.store.save(completed)
        repo.store.sounds.archive(repo.store, completed, ensure) { update("正在保存分轨到 Sounds", .88f + .12f * it) }
        repo.update(completed)
        repo.message.value = "分离完成，拖动滑块即可调整并试听。"
    }

    private fun update(title: String, progress: Float) {
        repo.task.value = repo.task.value.copy(active = true, title = title, progress = progress.coerceIn(0f, 1f))
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotification > 600) {
            getSystemService(NotificationManager::class.java).notify(7, notification(title, progress))
            lastNotification = now
        }
    }
    private fun notification(title: String, progress: Float): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, ProcessingService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "processing").setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Voice Focus · $title").setContentText("正在手机上处理音频")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).roundToInt(), progress == 0f)
            .addAction(0, "取消", cancel).build()
    }
    private fun cancel() { job?.cancel(); separator?.cancel() }
    override fun onTimeout(startId: Int, fgsType: Int) { cancel(); stopSelf() }
    override fun onDestroy() { cancel(); scope.cancel(); super.onDestroy() }
    companion object {
        fun start(context: Context, action: String, id: String? = null, uri: Uri? = null, format: String? = null) {
            ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java).setAction(action)
                .putExtra("id", id).putExtra("uri", uri?.toString()).putExtra("format", format))
        }
        fun cancel(context: Context) { context.startService(Intent(context, ProcessingService::class.java).setAction("cancel")) }
    }
}
