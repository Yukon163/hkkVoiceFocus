package com.hkk.voicefocus.processing

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelManager(private val context: Context) {
    val file = File(context.filesDir, "models/htdemucs_6s_fp16weights.onnx")
    val ready get() = file.isFile && file.length() == SIZE
    fun download(check: () -> Unit, progress: (Float) -> Unit) {
        file.parentFile!!.mkdirs()
        val partial = File(file.parentFile, "model.part")
        var offset = partial.takeIf { it.length() < SIZE }?.length() ?: 0L
        val connection = URL(URL_STRING).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000; connection.readTimeout = 30_000
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        try {
            val response = connection.responseCode
            require(response in listOf(200, 206)) { "模型下载失败（HTTP $response），可重试或从电脑导入模型。" }
            if (response == 200) offset = 0
            if (response == 206) require(connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) { "下载服务器返回了错误的数据区间，请重试。" }
            connection.inputStream.use { input -> FileOutputStream(partial, offset > 0).buffered().use { out ->
                val buffer = ByteArray(256 * 1024)
                var size = offset
                while (true) {
                    check()
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    require(size <= SIZE) { "模型下载大小异常" }
                    out.write(buffer, 0, n)
                    progress(size.toFloat() / SIZE * .98f)
                }
            } }
            verify(partial, check)
            require(partial.renameTo(file)) { "无法保存模型" }
            progress(1f)
        } finally { connection.disconnect() }
    }

    fun import(uri: Uri, check: () -> Unit) {
        file.parentFile!!.mkdirs()
        val temp = File(file.parentFile, "import.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().buffered().use { out ->
                val buffer = ByteArray(256 * 1024)
                var size = 0L
                while (true) {
                    check()
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    require(size <= SIZE) { "文件不是所需的六轨模型" }
                    out.write(buffer, 0, n)
                }
            } } ?: error("无法读取模型文件")
            verify(temp, check)
            if (file.exists()) file.delete()
            require(temp.renameTo(file)) { "无法保存模型" }
        } finally { temp.delete() }
    }

    fun verify(input: File = file, check: () -> Unit = {}) {
        require(input.length() == SIZE) { "模型不完整，请重新下载或导入指定模型。" }
        val digest = MessageDigest.getInstance("SHA-256")
        input.inputStream().buffered().use { stream ->
            val bytes = ByteArray(256 * 1024)
            while (true) {
                check()
                val n = stream.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == HASH) { "模型校验失败，文件可能损坏。" }
    }

    companion object {
        const val SIZE = 136428532L
        const val HASH = "7ce55792e2231c93fbf92de95f5fd5b3a5e6c89f7db690dfd693e8f1dce56869"
        const val URL_STRING = "https://huggingface.co/adowu/htdemucs-6s-onnx/resolve/d83893853e32af79f4ddc8d97d7e513eb5058d14/htdemucs_6s_fp16weights.onnx"
    }
}
