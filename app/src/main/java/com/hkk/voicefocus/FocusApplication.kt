package com.hkk.voicefocus

import android.app.Application
import android.content.Context
import android.net.Uri
import com.hkk.voicefocus.audio.PreviewPlayer
import com.hkk.voicefocus.data.*
import com.hkk.voicefocus.processing.ModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class TaskState(val active: Boolean = false, val title: String = "", val progress: Float = 0f, val projectId: String? = null)
data class ExportResult(val file: File, val mime: String, val destination: Uri, val location: String = "你选择的位置")

class FocusRepository(context: Context) {
    private val preferences = context.getSharedPreferences("workspace", 0)
    val store = ProjectStore(context)
    val model = ModelManager(context)
    val player = PreviewPlayer(context.applicationContext)
    val projects = MutableStateFlow(store.all().map { if (it.status in listOf("分离中", "解码中")) it.copy(status = "已中断", error = "上次处理被系统中断，可重新分离。") else it })
    val selected = MutableStateFlow(preferences.getString("selected", null)?.takeIf { id -> projects.value.any { it.id == id } })
    val task = MutableStateFlow(TaskState())
    val modelReady = MutableStateFlow(model.ready)
    val storageReady = MutableStateFlow(store.sounds.granted)
    val message = MutableStateFlow<String?>(null)
    val exported = MutableStateFlow<ExportResult?>(null)
    init {
        projects.value.filter { it.status == "已中断" }.forEach { p ->
            store.folder(p.id).listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() }
        }
    }
    fun get(id: String) = projects.value.firstOrNull { it.id == id } ?: error("项目不存在")
    @Synchronized fun update(p: Project, persist: Boolean = true) {
        if (persist) store.save(p)
        projects.value = (projects.value.filterNot { it.id == p.id } + p).sortedByDescending { it.created }
        if (selected.value == p.id && p.ready) player.update(p)
    }
    fun persist(id: String) {
        val p = get(id)
        store.save(p)
        if (storageReady.value) store.sounds.saveMetadata(store, p)
    }
    fun select(id: String) { player.reset(); selected.value = id; preferences.edit().putString("selected", id).apply() }
    @Synchronized fun delete(p: Project) {
        require(!task.value.active) { "请等待当前任务完成" }
        player.reset()
        if (store.sounds.granted) store.sounds.deleteProject(p.id)
        store.delete(p)
        projects.value = projects.value.filterNot { it.id == p.id }
        if (selected.value == p.id) { selected.value = null; preferences.edit().remove("selected").apply() }
    }
}

class FocusApplication : Application() {
    val repository by lazy { FocusRepository(this) }
}
val Context.repository get() = (applicationContext as FocusApplication).repository
