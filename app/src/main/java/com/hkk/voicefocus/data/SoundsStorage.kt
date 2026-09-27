package com.hkk.voicefocus.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Recoverable public projects. Private PCM files are a working cache of these snapshots. */
class SoundsStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("sounds_storage", 0)
    private val resolver = context.contentResolver
    val treeUri get() = prefs.getString("tree", null)?.let(Uri::parse)
    val granted get() = treeUri?.let { tree -> resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission } } == true

    fun authorize(uri: Uri) {
        require(uri.authority == "com.android.externalstorage.documents" && DocumentsContract.getTreeDocumentId(uri) == "primary:Sounds") {
            "请选择内部存储中的 Sounds 文件夹；如果尚不存在，请在系统选择器中新建 Sounds。"
        }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("tree", uri.toString()).commit()
    }

    private fun root(): DocumentFile {
        require(granted) { "请先授权 Sounds 文件夹。" }
        return requireNotNull(DocumentFile.fromTreeUri(context, treeUri!!)).also {
            require(it.exists() && it.canWrite()) { "Sounds 文件夹不可写，请重新授权。" }
        }
    }
    private fun directory(parent: DocumentFile, name: String) = parent.findFile(name)?.also {
        require(it.isDirectory) { "$name 已存在但不是文件夹" }
    } ?: requireNotNull(parent.createDirectory(name)) { "无法创建 $name 文件夹" }
    private fun projects() = directory(directory(root(), "VoiceFocus"), "Projects")

    @Synchronized fun needsArchive(p: Project): Boolean {
        val folder = projects().findFile(p.id) ?: return true
        val manifest = readManifest(folder) ?: return true
        return manifest.optLong("modified", manifest.optLong("created")) < p.modified
    }

    @Synchronized fun archive(store: ProjectStore, p: Project, check: () -> Unit = {}, progress: (Float) -> Unit = {}) {
        val parent = projects()
        val stage = directory(parent, ".${p.id}.pending-${UUID.randomUUID()}")
        var replaced: DocumentFile? = null
        var committed = false
        try {
            val inputs = linkedMapOf<String, Pair<File, String>>()
            val extension = p.name.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.take(10).ifEmpty { "bin" }
            inputs["source.media"] = store.source(p) to "原始文件.$extension"
            if (store.pcm(p).exists()) inputs["source.f32"] = store.pcm(p) to "原音.wav"
            p.stems.forEach { inputs["${it.id}.f32"] = store.stem(p, it.id) to "${it.name}.wav" }
            val total = inputs.values.sumOf { it.first.length() }.coerceAtLeast(1)
            var copied = 0L
            val files = JSONArray()
            inputs.forEach { (localName, pair) ->
                check()
                val (file, name) = pair
                require(file.isFile) { "工程音轨缺失：$name" }
                val pcm = localName.endsWith(".f32")
                val mime = if (pcm) "audio/wav" else android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                val document = requireNotNull(stage.createFile(mime, name))
                val digest = MessageDigest.getInstance("SHA-256")
                resolver.openOutputStream(document.uri, "wt")!!.use { stream -> DigestOutputStream(stream.buffered(), digest).use { out ->
                    if (pcm) out.write(floatWavHeader(file.length()))
                    file.inputStream().buffered().use { input ->
                        val bytes = ByteArray(256 * 1024)
                        while (true) {
                            check()
                            val n = input.read(bytes)
                            if (n < 0) break
                            out.write(bytes, 0, n); copied += n
                            progress(copied.toFloat() / total)
                        }
                    }
                } }
                files.put(JSONObject().put("local", localName).put("name", document.name).put("bytes", file.length())
                    .put("floatWav", pcm).put("sha256", hex(digest.digest())))
            }
            val manifest = store.toJson(p).put("archiveVersion", 1).put("files", files)
            writeManifest(stage, manifest)
            check()
            parent.findFile(p.id)?.let { previous ->
                require(previous.renameTo(".${p.id}.previous-${UUID.randomUUID()}")) { "无法替换旧工程" }
                replaced = previous
            }
            require(stage.renameTo(p.id)) { "无法完成工程保存" }
            committed = true
            replaced?.delete()
        } finally {
            if (!committed) {
                stage.delete()
                replaced?.renameTo(p.id)
            }
        }
    }

    @Synchronized fun saveMetadata(store: ProjectStore, p: Project) {
        val folder = projects().findFile(p.id) ?: return
        val existing = readManifest(folder) ?: return
        // Saving sliders never rewrites hundreds of megabytes of immutable audio.
        writeManifest(folder, store.toJson(p).put("archiveVersion", 1).put("files", existing.getJSONArray("files")))
    }

    /** Import complete public snapshots after a reinstall or when the local cache is missing. */
    @Synchronized fun restore(store: ProjectStore, check: () -> Unit = {}, projectId: String? = null): List<Project> {
        val manifests = projects().listFiles().filter { it.isDirectory }.mapNotNull { folder ->
            readManifest(folder)?.let { folder to it }
        }.filter { runCatching { UUID.fromString(it.second.getString("id")) }.isSuccess }
            .filter { projectId == null || it.second.getString("id") == projectId }
            .groupBy { it.second.getString("id") }.values.map { group -> group.maxBy { it.second.optLong("modified", it.second.optLong("created")) } }
        val local = store.all().associateBy { it.id }
        val restored = mutableListOf<Project>()
        for ((folder, manifest) in manifests) {
            check()
            val p = store.fromJson(manifest)
            val previous = local[p.id]
            if (previous != null && previous.modified >= p.modified && store.source(previous).exists() &&
                (previous.frames == 0L || store.pcm(previous).length() == previous.frames * 8) &&
                previous.stems.all { store.stem(previous, it.id).length() == previous.frames * 8 }) continue
            val stage = File(store.root, ".restore-${UUID.randomUUID()}").apply { mkdirs() }
            val backup = File(store.root, ".previous-${UUID.randomUUID()}")
            var replaced = false
            try {
                val files = manifest.getJSONArray("files")
                require(files.length() in 1..8) { "工程文件列表异常" }
                for (i in 0 until files.length()) {
                    check()
                    val entry = files.getJSONObject(i)
                    val localName = entry.getString("local")
                    require(localName == "source.media" || localName.matches(Regex("(source|vocals|instrumental|guitar|piano|drums|bass|other)\\.f32")))
                    val name = entry.getString("name")
                    require(!name.contains('/') && !name.contains('\\'))
                    val doc = requireNotNull(folder.findFile(name)) { "公共工程缺少文件：$name" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    resolver.openInputStream(doc.uri)!!.use { raw -> DigestInputStream(raw.buffered(), digest).use { input ->
                        if (entry.getBoolean("floatWav")) {
                            val header = ByteArray(44)
                            readFully(input, header)
                            require(header.contentEquals(floatWavHeader(entry.getLong("bytes")))) { "工程 WAV 格式不匹配" }
                        }
                        File(stage, localName).outputStream().buffered().use { out ->
                            val bytes = ByteArray(256 * 1024)
                            while (true) { check(); val n = input.read(bytes); if (n < 0) break; out.write(bytes, 0, n) }
                        }
                    } }
                    require(File(stage, localName).length() == entry.getLong("bytes") && hex(digest.digest()) == entry.getString("sha256")) { "工程文件校验失败：$name" }
                }
                File(stage, "project.json").writeText(store.toJson(p).toString())
                val destination = store.folder(p.id)
                if (destination.exists()) { require(destination.renameTo(backup)); replaced = true }
                require(stage.renameTo(destination)) { "无法恢复工程缓存" }
                backup.deleteRecursively()
                restored += p
            } catch (e: Throwable) {
                if (replaced && !store.folder(p.id).exists()) backup.renameTo(store.folder(p.id))
                throw e
            } finally { stage.deleteRecursively() }
        }
        return restored
    }

    @Synchronized fun deleteProject(id: String) { projects().findFile(id)?.let { require(it.delete()) { "无法删除 Sounds 中的工程" } } }

    @Synchronized fun createExport(name: String, mime: String): Uri = requireNotNull(root().createFile(mime, name)) { "无法在 Sounds 中创建文件" }.uri

    fun deleteExport(uri: Uri) { DocumentsContract.deleteDocument(resolver, uri) }

    private fun readManifest(folder: DocumentFile): JSONObject? = listOf("project.json", "project.new.json", "project.bak.json").mapNotNull { name ->
        runCatching {
            val file = folder.findFile(name) ?: return@runCatching null
            require(file.length() <= 2_000_000)
            resolver.openInputStream(file.uri)!!.bufferedReader().use { JSONObject(it.readText()) }
        }.getOrNull()
    }.maxByOrNull { it.optLong("modified", it.optLong("created")) }

    private fun writeManifest(folder: DocumentFile, json: JSONObject) {
        folder.findFile("project.new.json")?.delete()
        val pending = requireNotNull(folder.createFile("application/json", "project.new.json"))
        resolver.openOutputStream(pending.uri, "wt")!!.bufferedWriter().use { it.write(json.toString(2)) }
        folder.findFile("project.bak.json")?.delete()
        folder.findFile("project.json")?.let { require(it.renameTo("project.bak.json")) }
        require(pending.renameTo("project.json")) { "无法保存工程配置" }
        folder.findFile("project.bak.json")?.delete()
    }

    companion object {
        const val DISPLAY_PATH = "/storage/emulated/0/Sounds/"
        val INITIAL_URI: Uri get() = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Sounds")
        fun floatWavHeader(bytes: Long): ByteArray {
            require(bytes >= 0 && bytes % 8 == 0L && bytes <= 0xffffffffL - 36) { "分轨文件超过 WAV 格式大小限制" }
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()).putInt((bytes + 36).toInt()).put("WAVEfmt ".toByteArray())
                putInt(16).putShort(3).putShort(2).putInt(44100).putInt(44100 * 8)
                putShort(8).putShort(32).put("data".toByteArray()).putInt(bytes.toInt())
            }.array()
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun readFully(input: InputStream, buffer: ByteArray) {
            var offset = 0
            while (offset < buffer.size) { val n = input.read(buffer, offset, buffer.size - offset); require(n > 0); offset += n }
        }
    }
}
