package com.hkk.voicefocus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hkk.voicefocus.audio.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MediaFormatTest {
    @Test fun importsCommonFormatsAndResamplesMonoToStereo() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(ctx.getExternalFilesDir(null), "formats")
        for (name in listOf("mono48.wav", "stereo24.wav", "mono32.mp3", "stereo.flac", "audio.ogg", "audio.webm")) {
            val source = File(folder, name)
            require(source.exists()) { "Missing fixture $source" }
            val target = File(ctx.cacheDir, "$name.f32")
            try {
                val result = MediaDecoder().decode(source, target, {}, {})
                assertTrue("duration $name: ${result.frames}", abs(result.frames - SAMPLE_RATE * 3) < 4096)
                assertFalse(result.hasVideo)
                PcmReader(target).use { reader ->
                    val data = reader.read(SAMPLE_RATE.toLong(), 4096)
                    assertTrue(name, data.all { it.isFinite() })
                    assertTrue("non-silent $name", data.any { abs(it) > .001 })
                    if (name.startsWith("mono")) for (i in 0 until data.size / 2) assertEquals(data[i * 2], data[i * 2 + 1], 0f)
                }
            } finally { target.delete() }
        }
    }
}
