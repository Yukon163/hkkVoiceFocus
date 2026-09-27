package com.hkk.voicefocus.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class Decoded(val frames: Long, val hasVideo: Boolean, val waveform: List<Float>)

class MediaDecoder {
    fun decode(source: File, target: File, check: () -> Unit, progress: (Float) -> Unit): Decoded {
        val extractor = MediaExtractor()
        val native = File(target.parentFile, "decode.part")
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val index = tracks.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            require(index >= 0) { "这个文件没有可读取的音轨。" }
            val video = tracks.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(index)
            val format = tracks[index]
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
            PcmWriter(native).use { writer ->
                if (format.getString(MediaFormat.KEY_MIME) == "audio/raw") {
                    val buffer = ByteBuffer.allocateDirect(256 * 1024)
                    while (true) {
                        check()
                        buffer.clear()
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) break
                        buffer.position(0); buffer.limit(n)
                        writer.write(toStereo(buffer, channels, encoding))
                        if (duration > 0) progress(extractor.sampleTime.toFloat() / duration * .7f)
                        extractor.advance()
                    }
                } else {
                    val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                    codec = decoder
                    decoder.configure(format, null, null, 0)
                    decoder.start()
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    var written = false
                    var idle = 0
                    while (!outputDone) {
                        check()
                        if (!inputDone) {
                            val slot = decoder.dequeueInputBuffer(10_000)
                            if (slot >= 0) {
                                val input = decoder.getInputBuffer(slot)!!
                                input.clear()
                                val n = extractor.readSampleData(input, 0)
                                if (n < 0) {
                                    decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(slot, 0, n, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val slot = decoder.dequeueOutputBuffer(info, 10_000)
                        when {
                            slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val out = decoder.outputFormat
                                val nextRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                require(!written || nextRate == rate) { "音轨在中途改变了采样率，暂不支持该文件。" }
                                rate = nextRate
                                channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            }
                            slot >= 0 -> {
                                idle = 0
                                if (info.size > 0) {
                                    val output = decoder.getOutputBuffer(slot)!!
                                    output.position(info.offset); output.limit(info.offset + info.size)
                                    writer.write(toStereo(output.slice(), channels, encoding))
                                    written = true
                                    if (duration > 0) progress((info.presentationTimeUs.toFloat() / duration * .7f).coerceIn(0f, .7f))
                                }
                                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                decoder.releaseOutputBuffer(slot, false)
                            }
                            inputDone -> { idle++; require(idle < 3000) { "音频解码超时" } }
                        }
                    }
                }
            }
            require(native.length() > 0) { "音轨为空或编码不受设备支持。" }
            resample(native, target, rate, check) { progress(.7f + .25f * it) }
            val frames = target.length() / 8
            val wave = waveform(target, check)
            progress(1f)
            return Decoded(frames, video, wave)
        } finally {
            runCatching { codec?.stop() }; codec?.release(); extractor.release(); native.delete()
        }
    }

    private fun toStereo(input: ByteBuffer, channels: Int, encoding: Int): FloatArray {
        require(channels in 1..8) { "不支持的声道数：$channels" }
        input.order(ByteOrder.LITTLE_ENDIAN)
        val bytes = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            else -> error("不支持的 PCM 格式：$encoding")
        }
        val count = input.remaining() / (bytes * channels)
        val result = FloatArray(count * 2)
        val frame = FloatArray(channels)
        for (i in 0 until count) {
            for (ch in 0 until channels) frame[ch] = when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> input.float
                AudioFormat.ENCODING_PCM_32BIT -> (input.int / 2147483648.0).toFloat()
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val v = (input.get().toInt() and 255) or ((input.get().toInt() and 255) shl 8) or (input.get().toInt() shl 16)
                    v / 8388608f
                }
                AudioFormat.ENCODING_PCM_8BIT -> ((input.get().toInt() and 255) - 128) / 128f
                else -> input.short / 32768f
            }
            var left = frame[0]
            var right = frame[if (channels == 1) 0 else 1]
            if (channels > 2) {
                var scale = 1f
                for (ch in 2 until channels) {
                    val weight = if (ch == 2) .707f else .35f
                    left += frame[ch] * weight; right += frame[ch] * weight
                    scale += weight
                }
                left /= scale; right /= scale
            }
            result[2 * i] = if (left.isFinite()) left else 0f
            result[2 * i + 1] = if (right.isFinite()) right else 0f
        }
        return result
    }

    private fun resample(input: File, output: File, sourceRate: Int, check: () -> Unit, progress: (Float) -> Unit) {
        require(sourceRate > 0)
        if (sourceRate == SAMPLE_RATE) { input.copyTo(output, overwrite = true); return }
        PcmReader(input).use { reader -> PcmWriter(output).use { writer ->
            val count = (reader.frames * SAMPLE_RATE.toDouble() / sourceRate).roundToLong()
            val ratio = sourceRate.toDouble() / SAMPLE_RATE
            val cutoff = min(1.0, 1.0 / ratio) * .95
            var at = 0L
            while (at < count) {
                check()
                val n = min(2048L, count - at).toInt()
                val first = floor(at * ratio).toLong() - 16
                val last = ceil((at + n) * ratio).toLong() + 16
                val data = reader.read(first, (last - first).toInt())
                val out = FloatArray(n * 2)
                for (i in 0 until n) {
                    val pos = (at + i) * ratio
                    val center = floor(pos).toLong()
                    var weightSum = 0.0
                    for (k in -15..16) {
                        val d = center + k - pos
                        val x = PI * d * cutoff
                        val weight = (if (abs(x) < 1e-9) 1.0 else sin(x) / x) * (.5 + .5 * cos(PI * d / 16)) * cutoff
                        val index = ((center + k - first) * 2).toInt()
                        out[2 * i] += (data[index] * weight).toFloat()
                        out[2 * i + 1] += (data[index + 1] * weight).toFloat()
                        weightSum += weight
                    }
                    out[2 * i] /= weightSum.toFloat(); out[2 * i + 1] /= weightSum.toFloat()
                }
                writer.write(out); at += n; progress(at.toFloat() / count)
            }
        } }
    }

    private fun waveform(file: File, check: () -> Unit): List<Float> = PcmReader(file).use { reader ->
        val peaks = FloatArray(240)
        var at = 0L
        while (at < reader.frames) {
            check()
            val n = min(8192L, reader.frames - at).toInt()
            val samples = reader.read(at, n)
            for (i in 0 until n) {
                val bin = ((at + i) * peaks.size / reader.frames).toInt().coerceAtMost(peaks.lastIndex)
                peaks[bin] = max(peaks[bin], max(abs(samples[2 * i]), abs(samples[2 * i + 1])))
            }
            at += n
        }
        peaks.toList()
    }
}
