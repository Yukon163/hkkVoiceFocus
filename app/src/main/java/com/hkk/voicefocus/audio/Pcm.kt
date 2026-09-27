package com.hkk.voicefocus.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

const val SAMPLE_RATE = 44100
const val CHANNELS = 2

/** Disk PCM: interleaved stereo float32, little endian, 44.1 kHz. */
class PcmReader(file: File) : Closeable {
    private val input = RandomAccessFile(file, "r")
    val frames = input.length() / 8
    fun read(start: Long, count: Int): FloatArray {
        val result = FloatArray(count * 2)
        val from = max(0, start)
        val until = min(frames, start + count)
        if (until <= from) return result
        val bytes = ByteArray(((until - from) * 8).toInt())
        input.seek(from * 8)
        input.readFully(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            .get(result, ((from - start) * 2).toInt(), bytes.size / 4)
        return result
    }
    override fun close() = input.close()
}

class PcmWriter(file: File) : Closeable {
    private val out = file.outputStream().buffered(256 * 1024)
    fun write(data: FloatArray, samples: Int = data.size) {
        val bytes = ByteBuffer.allocate(samples * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(data, 0, samples)
        out.write(bytes.array())
    }
    override fun close() = out.close()
}

class PeakLimiter {
    private var gain = 1f
    fun apply(samples: FloatArray): FloatArray {
        for (i in samples.indices step 2) {
            val peak = max(abs(samples[i]), abs(samples[i + 1]))
            val target = if (peak > .98f) .98f / peak else 1f
            gain = if (target < gain) target else min(target, gain + .00025f)
            samples[i] *= gain
            samples[i + 1] *= gain
        }
        return samples
    }
}

object MixMath {
    fun mix(stems: List<FloatArray>, gains: FloatArray): FloatArray {
        require(stems.isNotEmpty() && stems.size == gains.size)
        val output = FloatArray(stems[0].size)
        stems.forEachIndexed { index, stem ->
            require(stem.size == output.size)
            for (i in output.indices) output[i] += stem[i] * gains[index]
        }
        return output
    }

    fun overlapWeight(i: Int, length: Int, overlap: Int, first: Boolean, last: Boolean): Float {
        val left = if (first || i >= overlap) 1f else (i + 1f) / overlap
        val right = if (last || i < length - overlap) 1f else (length - i).toFloat() / overlap
        return min(left, right)
    }
}

fun writeWav(file: File, frames: Long, samples: (Long, Int) -> FloatArray, check: () -> Unit = {}) {
    require(frames * 4 <= 0xffffffffL - 36) { "WAV 超过 4 GB，请缩短音频或选择 M4A。" }
    file.outputStream().buffered().use { out ->
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((frames * 4 + 36).toInt()).put("WAVEfmt ".toByteArray())
        h.putInt(16).putShort(1).putShort(2).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 4)
        h.putShort(4).putShort(16).put("data".toByteArray()).putInt((frames * 4).toInt())
        out.write(h.array())
        var at = 0L
        while (at < frames) {
            check()
            val count = min(4096L, frames - at).toInt()
            val data = samples(at, count)
            val bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
            data.forEach { bytes.putShort((it.coerceIn(-1f, 1f) * 32767).roundToInt().toShort()) }
            out.write(bytes.array())
            at += count
        }
    }
}
