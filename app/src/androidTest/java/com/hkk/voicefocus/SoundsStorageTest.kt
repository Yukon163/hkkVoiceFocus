package com.hkk.voicefocus

import android.content.ContextWrapper
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hkk.voicefocus.audio.PcmReader
import com.hkk.voicefocus.audio.PcmWriter
import com.hkk.voicefocus.data.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SoundsStorageTest {
    @Test fun publicProjectSurvivesCacheLossAndCancelledReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val temporary = File(context.cacheDir, "storage-test-${UUID.randomUUID()}").apply { mkdirs() }
        val testContext = object : ContextWrapper(context) { override fun getFilesDir() = temporary }
        val store = ProjectStore(testContext)
        val sounds = store.sounds
        require(sounds.granted) { "Authorize the Sounds folder in the app before running this test." }
        val p = Project(UUID.randomUUID().toString(), "Storage roundtrip test.bin", frames = 128, status = "已完成",
            stems = listOf(Stem("vocals", "人声"), Stem("instrumental", "伴奏", .6f)))
        val original = ByteArray(128) { it.toByte() }
        val voice = FloatArray(256) { it / 1024f - .1f }
        val backing = FloatArray(256) { -it / 2048f }
        try {
            store.folder(p.id).mkdirs()
            store.source(p).writeBytes(original)
            PcmWriter(store.pcm(p)).use { it.write(FloatArray(256) { voice[it] + backing[it] }) }
            PcmWriter(store.stem(p, "vocals")).use { it.write(voice) }
            PcmWriter(store.stem(p, "instrumental")).use { it.write(backing) }
            store.save(p)
            sounds.archive(store, p)
            val root = DocumentFile.fromTreeUri(context, sounds.treeUri!!)!!
            val projects = root.findFile("VoiceFocus")!!.findFile("Projects")!!
            val folder = projects.findFile(p.id)!!
            val track = folder.findFile("人声.wav")!!
            assertEquals(44 + voice.size * 4L, track.length())
            val changed = p.copy(stems = p.stems.map { if (it.id == "instrumental") it.copy(gain = .45f) else it }, modified = p.modified + 1)
            sounds.saveMetadata(store, changed)
            assertEquals(track.uri, folder.findFile("人声.wav")!!.uri)
            // Remove only this test's private cache; restore the exact public floating point audio.
            store.folder(p.id).deleteRecursively()
            val restored = sounds.restore(store, projectId = p.id).first { it.id == p.id }
            assertEquals(.45f, restored.stems[1].gain, 0f)
            assertArrayEquals(original, store.source(restored).readBytes())
            PcmReader(store.stem(restored, "vocals")).use { assertArrayEquals(voice, it.read(0, 128), 0f) }
            var checks = 0
            try {
                sounds.archive(store, changed.copy(modified = changed.modified + 1), check = {
                    if (++checks > 3) throw CancellationException("test cancellation")
                })
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertNotNull(projects.findFile(p.id)?.findFile("project.json"))
            assertFalse(projects.listFiles().any { it.name?.startsWith(".${p.id}.pending") == true })
            val export = sounds.createExport("VoiceFocus-storage-test-${p.id}.wav", "audio/wav")
            assertTrue(android.provider.DocumentsContract.getDocumentId(export).startsWith("primary:Sounds/"))
            sounds.deleteExport(export)
        } finally {
            sounds.deleteProject(p.id)
            temporary.deleteRecursively()
        }
    }
}
