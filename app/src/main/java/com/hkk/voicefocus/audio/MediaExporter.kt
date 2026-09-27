package com.hkk.voicefocus.audio

import android.media.*
import android.os.Build
import com.hkk.voicefocus.data.*
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

class MixSource(store: ProjectStore, val project: Project) : Closeable {
    private val readers = project.stems.map { PcmReader(store.stem(project, it.id)) }
    private val limiter = PeakLimiter()
    private val gains = project.gains()
    fun read(at: Long, count: Int): FloatArray = limiter.apply(MixMath.mix(readers.map { it.read(at, count) }, gains))
    override fun close() = readers.forEach { it.close() }
}

class MediaExporter {
    fun export(store: ProjectStore, p: Project, format: String, target: File, check: () -> Unit, progress: (Float) -> Unit) {
        require(p.ready) { "请先完成音轨分离" }
        when (format) {
            "wav" -> MixSource(store, p).use { mix ->
                writeWav(target, p.frames, { at, n -> progress(at.toFloat() / p.frames); mix.read(at, n) }, check)
            }
            "zip" -> ZipOutputStream(target.outputStream().buffered()).use { zip ->
                p.stems.forEachIndexed { index, stem ->
                    check()
                    val temp = File(target.parentFile, "${p.id}-${stem.id}.wav")
                    try {
                        val limiter = PeakLimiter()
                        PcmReader(store.stem(p, stem.id)).use { reader ->
                            writeWav(temp, p.frames, { at, n ->
                                progress((index + at.toFloat() / p.frames) / p.stems.size)
                                limiter.apply(reader.read(at, n))
                            }, check)
                        }
                        zip.putNextEntry(ZipEntry("${stem.name}.wav"))
                        temp.inputStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) { check(); val n = input.read(buffer); if (n < 0) break; zip.write(buffer, 0, n) }
                        }
                        zip.closeEntry()
                    } finally { temp.delete() }
                }
            }
            "m4a", "mp4" -> encode(store, p, target, format == "mp4", check, progress)
            else -> error("不支持的导出格式")
        }
        progress(1f)
    }

    private fun encode(store: ProjectStore, p: Project, target: File, video: Boolean, check: () -> Unit, progress: (Float) -> Unit) {
        require(!video || p.hasVideo) { "原文件没有视频画面，请选择音频格式。" }
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var videoReader: MediaExtractor? = null
        var muxStarted = false
        try {
            var videoTrack = -1
            if (video) {
                val extractor = MediaExtractor().also { videoReader = it }
                extractor.setDataSource(store.source(p).absolutePath)
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                val vf = extractor.getTrackFormat(track)
                videoTrack = try { muxer.addTrack(vf) } catch (e: Exception) { throw IllegalArgumentException("该视频编码无法直接封装为 MP4，请导出音频或先将原视频转为 H.264/H.265。", e) }
                if (vf.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION))
                extractor.selectTrack(track)
            }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 256000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            var audioTrack = -1
            var at = 0L
            var inputDone = false
            var done = false
            var idle = 0
            val info = MediaCodec.BufferInfo()
            MixSource(store, p).use { mix ->
                while (!done) {
                    check()
                    if (!inputDone) {
                        val slot = encoder.dequeueInputBuffer(10_000)
                        if (slot >= 0) {
                            val buffer = encoder.getInputBuffer(slot)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                            val count = min(buffer.remaining() / 4L, p.frames - at).toInt()
                            if (count == 0) {
                                encoder.queueInputBuffer(slot, 0, 0, at * 1_000_000 / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                mix.read(at, count).forEach { buffer.putShort((it.coerceIn(-1f, 1f) * 32767).roundToInt().toShort()) }
                                encoder.queueInputBuffer(slot, 0, count * 4, at * 1_000_000 / SAMPLE_RATE, 0)
                                at += count
                                progress(at.toFloat() / p.frames * if (video) .75f else .99f)
                            }
                        }
                    }
                    val slot = encoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            audioTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start(); muxStarted = true
                        }
                        slot >= 0 -> {
                            idle = 0
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                require(muxStarted)
                                val output = encoder.getOutputBuffer(slot)!!
                                output.position(info.offset); output.limit(info.offset + info.size)
                                info.presentationTimeUs = info.presentationTimeUs.coerceAtLeast(0)
                                muxer.writeSampleData(audioTrack, output, info)
                            }
                            done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(slot, false)
                        }
                        inputDone -> { idle++; require(idle < 3000) { "音频编码超时" } }
                    }
                }
            }
            videoReader?.let { extractor ->
                var buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
                val firstPts = extractor.sampleTime.coerceAtLeast(0)
                while (true) {
                    check()
                    val size = if (Build.VERSION.SDK_INT >= 28) extractor.sampleSize else buffer.capacity().toLong()
                    if (size < 0) break
                    require(size <= 64 * 1024 * 1024) { "视频帧过大，无法封装" }
                    if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                    buffer.clear()
                    val n = extractor.readSampleData(buffer, 0)
                    if (n < 0) break
                    info.set(0, n, (extractor.sampleTime - firstPts).coerceAtLeast(0), if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(videoTrack, buffer, info)
                    progress(.75f + .24f * (info.presentationTimeUs.toDouble() * SAMPLE_RATE / 1_000_000 / p.frames).toFloat().coerceIn(0f, 1f))
                    extractor.advance()
                }
            }
            muxer.stop(); muxStarted = false
        } finally {
            runCatching { encoder.stop() }; encoder.release(); videoReader?.release()
            if (muxStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
