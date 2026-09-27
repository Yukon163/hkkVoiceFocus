package com.hkk.voicefocus

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hkk.voicefocus.audio.*
import com.hkk.voicefocus.data.*
import com.hkk.voicefocus.processing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class OfflinePipelineTest {
    @Test fun actualModelDecodesSeparatesRemixesAndExportsVideo() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val external = ctx.getExternalFilesDir(null)!!
        val video = File(external, "validation.mp4")
        require(video.exists()) { "Push validation.mp4 to ${external.absolutePath} before running." }
        val model = ModelManager(ctx)
        if (!model.ready) model.import(Uri.fromFile(File(external, "test-model.onnx"))) { }
        model.verify()
        val store = ctx.repository.store
        var p = store.import(ctx, Uri.fromFile(video)).copy(name = "弹唱验证片段.mp4", mode = "multi")
        val d = MediaDecoder().decode(store.source(p), store.pcm(p), {}, {})
        assertTrue(d.hasVideo); assertTrue(d.frames > SAMPLE_RATE * 8)
        p = p.copy(frames = d.frames, hasVideo = true, waveform = d.waveform)
        val start = System.currentTimeMillis()
        val stems = DemucsSeparator().separate(model.file, store, p, {}, {})
        android.util.Log.i("VoiceFocusTest", "separation_ms=${System.currentTimeMillis() - start}, frames=${p.frames}")
        assertEquals(6, stems.size)
        stems.forEach { assertEquals(p.frames * 8, store.stem(p, it.id).length()) }
        val readers = stems.map { PcmReader(store.stem(p, it.id)) }
        try {
            PcmReader(store.pcm(p)).use { original ->
                for (at in listOf(0L, DemucsSeparator.STRIDE.toLong() - 20, p.frames - 200)) {
                    val expected = original.read(at, 200)
                    val actual = MixMath.mix(readers.map { it.read(at, 200) }, FloatArray(6) { 1f })
                    for (i in expected.indices) assertEquals("reconstruction at $at/$i", expected[i], actual[i], 2e-6f)
                }
            }
        } finally { readers.forEach { it.close() } }
        p = p.copy(stems = stems, status = "已完成")
        store.save(p)
        val output = File(external, "verification").apply { mkdirs() }
        for (format in listOf("wav", "m4a", "mp4", "zip")) {
            val file = File(output, "mix.$format")
            MediaExporter().export(store, p, format, file, {}, {})
            assertTrue(file.length() > 1000)
            if (format == "mp4" || format == "m4a") {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    assertEquals(if (format == "mp4") 2 else 1, extractor.trackCount)
                    assertTrue((0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
                } finally { extractor.release() }
            }
        }
        val restored = MediaDecoder().decode(File(output, "mix.m4a"), File(output, "roundtrip.f32"), {}, {})
        assertTrue(abs(restored.frames - p.frames) < 4096)
        ctx.repository.update(p)
        ctx.repository.modelReady.value = true
        ctx.repository.selected.value = p.id
    }
}
