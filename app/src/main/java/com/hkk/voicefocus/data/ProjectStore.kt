package com.hkk.voicefocus.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Stem(val id: String, val name: String, val gain: Float = 1f, val muted: Boolean = false)
data class Project(
    val id: String, val name: String, val created: Long = System.currentTimeMillis(),
    val hasVideo: Boolean = false, val frames: Long = 0, val mode: String = "duet",
    val instrument: String = "自动", val status: String = "待分离", val error: String? = null,
    val stems: List<Stem> = emptyList(), val waveform: List<Float> = emptyList(),
    val modified: Long = created,
) {
    val ready get() = stems.isNotEmpty() && status == "已完成"
    fun gains(): FloatArray {
        return stems.map { if (it.muted) 0f else it.gain }.toFloatArray()
    }
}

class ProjectStore(context: Context) {
    val sounds = SoundsStorage(context.applicationContext)
    val root = File(context.filesDir, "projects").apply { mkdirs() }
    fun folder(id: String) = File(root, id)
    fun source(p: Project) = File(folder(p.id), "source.media")
    fun pcm(p: Project) = File(folder(p.id), "source.f32")
    fun stem(p: Project, id: String) = File(folder(p.id), "$id.f32")

    fun import(context: Context, uri: Uri, check: () -> Unit = {}): Project {
        var name = "音频"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val p = Project(UUID.randomUUID().toString(), name)
        folder(p.id).mkdirs()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                source(p).outputStream().buffered().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        check()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            } ?: error("无法读取所选文件")
            require(source(p).length() > 0) { "所选文件为空" }
            save(p)
            return p
        } catch (e: Exception) { folder(p.id).deleteRecursively(); throw e }
    }

    @Synchronized fun save(p: Project) {
        val atomic = AtomicFile(File(folder(p.id), "project.json"))
        val out = atomic.startWrite()
        try { out.write(toJson(p).toString().toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }

    fun toJson(p: Project): JSONObject {
        val json = JSONObject().put("id", p.id).put("name", p.name).put("created", p.created).put("modified", p.modified)
            .put("video", p.hasVideo).put("frames", p.frames).put("mode", p.mode)
            .put("instrument", p.instrument).put("status", p.status).put("error", p.error)
            .put("waveform", JSONArray(p.waveform))
        json.put("stems", JSONArray().apply { p.stems.forEach {
            put(JSONObject().put("id", it.id).put("name", it.name).put("gain", it.gain)
                .put("muted", it.muted))
        } })
        return json
    }

    fun all(): List<Project> = root.listFiles().orEmpty().mapNotNull { folder ->
        runCatching {
            val j = JSONObject(AtomicFile(File(folder, "project.json")).readFully().toString(Charsets.UTF_8))
            fromJson(j)
        }.getOrNull()
    }.distinctBy { it.id }.sortedByDescending { it.created }

    fun fromJson(j: JSONObject): Project {
            UUID.fromString(j.getString("id"))
            val a = j.optJSONArray("stems") ?: JSONArray()
            val w = j.optJSONArray("waveform") ?: JSONArray()
            require(a.length() <= 6 && w.length() <= 1000)
            return Project(j.getString("id"), j.getString("name"), j.getLong("created"), j.optBoolean("video"),
                j.optLong("frames"), j.optString("mode", "duet"), j.optString("instrument", "自动"),
                j.optString("status", "待分离"), j.optString("error").takeIf { it.isNotBlank() && it != "null" },
                (0 until a.length()).map { i -> a.getJSONObject(i).let {
                    val id = it.getString("id")
                    require(id in listOf("vocals", "instrumental", "guitar", "piano", "bass", "drums", "other"))
                    val gain = it.optDouble("gain", 1.0).toFloat()
                    require(gain.isFinite())
                    // Ignore legacy solo flags so old projects cannot retain an invisible solo state.
                    Stem(id, it.getString("name"), gain.coerceIn(0f, 2f), it.optBoolean("muted"))
                } }, (0 until w.length()).map { w.getDouble(it).toFloat() }, j.optLong("modified", j.getLong("created")))
    }

    fun delete(p: Project) { folder(p.id).deleteRecursively() }
}
