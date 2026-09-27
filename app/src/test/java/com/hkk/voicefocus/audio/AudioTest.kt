package com.hkk.voicefocus.audio

import com.hkk.voicefocus.data.Project
import com.hkk.voicefocus.data.Stem
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs

class AudioTest {
    @Test fun residualAndGainsHaveExpectedMeaning() {
        val source = floatArrayOf(.5f, -.4f, .2f, -.1f)
        val voice = floatArrayOf(.1f, -.1f, .08f, -.04f)
        val music = FloatArray(4) { source[it] - voice[it] }
        assertArrayEquals(source, MixMath.mix(listOf(voice, music), floatArrayOf(1f, 1f)), 1e-6f)
        assertArrayEquals(voice, MixMath.mix(listOf(voice, music), floatArrayOf(1f, 0f)), 1e-6f)
        val quieter = MixMath.mix(listOf(voice, music), floatArrayOf(1f, .6f))
        for (i in source.indices) assertEquals(voice[i] + .6f * music[i], quieter[i], 1e-6f)
    }
    @Test fun overlapsHaveNoZeroWeightsAtEdgesOrSeams() {
        val n = 100; val overlap = 25; val step = n - overlap
        val total = 230
        val sums = FloatArray(total)
        val weights = FloatArray(total)
        var at = 0
        while (at < total) {
            val last = at + n >= total
            val valid = minOf(n, total - at)
            for (i in 0 until valid) {
                val w = MixMath.overlapWeight(i, n, overlap, at == 0, last)
                sums[at + i] += .31f * w; weights[at + i] += w
            }
            if (last) break
            at += step
        }
        for (i in sums.indices) { assertTrue(weights[i] > 0f); assertEquals(.31f, sums[i] / weights[i], 1e-6f) }
    }
    @Test fun limiterLinksStereoAndPreservesQuietAudio() {
        val samples = floatArrayOf(.2f, -.1f, 2f, 1f, .1f, .05f)
        val result = PeakLimiter().apply(samples)
        assertEquals(.2f, result[0], 0f)
        assertTrue(result.all { abs(it) <= .98001f })
        assertEquals(result[2] / 2f, result[3], 1e-6f)
    }
    @Test fun muteWinsOverSoloAndSoloSuppressesOtherTracks() {
        val p = Project("id", "test", stems = listOf(Stem("vocals", "v", 1.2f, solo = true), Stem("guitar", "g", .4f)))
        assertArrayEquals(floatArrayOf(1.2f, 0f), p.gains(), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), p.copy(stems = p.stems.map { it.copy(muted = true) }).gains(), 0f)
    }
    @Test fun pcmHandlesPartialReadsAndWavHasCorrectHeader() {
        val folder = Files.createTempDirectory("voicefocus").toFile()
        try {
            val f = java.io.File(folder, "test.f32")
            PcmWriter(f).use { it.write(floatArrayOf(.1f, -.1f, .3f, -.3f)) }
            PcmReader(f).use { reader ->
                assertEquals(2L, reader.frames)
                assertArrayEquals(floatArrayOf(0f, 0f, .1f, -.1f, .3f, -.3f, 0f, 0f), reader.read(-1, 4), 0f)
            }
            val wav = java.io.File(folder, "test.wav")
            writeWav(wav, 2, { _, _ -> floatArrayOf(.1f, -.1f, .3f, -.3f) })
            val b = wav.readBytes()
            assertEquals(52, b.size)
            assertEquals("RIFF", b.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals(8, ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        } finally { folder.deleteRecursively() }
    }
}
