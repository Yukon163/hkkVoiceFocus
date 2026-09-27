package com.hkk.voicefocus.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.hkk.voicefocus.audio.*
import com.hkk.voicefocus.data.*
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.min

class DemucsSeparator {
    @Volatile private var activeRun: OrtSession.RunOptions? = null
    fun cancel() { runCatching { activeRun?.setTerminate(true) } }

    fun separate(model: File, store: ProjectStore, p: Project, check: () -> Unit, progress: (Float) -> Unit): List<Stem> {
        val env = OrtEnvironment.getEnvironment()
        val definitions = if (p.mode == "multi") listOf(
            Stem("vocals", "人声"), Stem("guitar", "吉他", .7f), Stem("piano", "钢琴", .7f),
            Stem("drums", "鼓", .7f), Stem("bass", "贝斯", .7f), Stem("other", "其他声部", .7f)
        ) else listOf(Stem("vocals", "人声"), Stem("instrumental", if (p.instrument == "自动") "乐器伴奏" else "${p.instrument}伴奏", .6f))
        val modelOrder = listOf("drums", "bass", "other", "vocals", "guitar", "piano")
        val files = definitions.map { File(store.folder(p.id), "${it.id}.part") }
        val writers = files.map { PcmWriter(it) }
        var success = false
        try {
            OrtSession.SessionOptions().use { options ->
                // ORT's arena can retain multi-gigabyte convolution scratch buffers on Android.
                // Release scratch storage between operators/runs instead of growing the arena.
                options.setCPUArenaAllocator(false)
                options.setMemoryPatternOptimization(false)
                options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 2))
                options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(model.absolutePath, options).use { session ->
                    require(session.inputNames.contains("mix") && session.outputNames.contains("stems")) { "模型接口不匹配" }
                    OrtSession.RunOptions().use { run ->
                        activeRun = run
                        PcmReader(store.pcm(p)).use { reader ->
                            var at = 0L
                            val tail = Array(6) { FloatArray(OVERLAP * 2) }
                            val tailWeights = FloatArray(OVERLAP)
                            while (at < reader.frames) {
                                check()
                                val valid = min(LENGTH.toLong(), reader.frames - at).toInt()
                                val last = at + LENGTH >= reader.frames
                                val source = reader.read(at, LENGTH)
                                val planar = FloatArray(LENGTH * 2)
                                for (i in 0 until LENGTH) { planar[i] = source[i * 2]; planar[LENGTH + i] = source[i * 2 + 1] }
                                OnnxTensor.createTensor(env, FloatBuffer.wrap(planar), longArrayOf(1, 2, LENGTH.toLong())).use { tensor ->
                                    session.run(mapOf("mix" to tensor), run).use { result ->
                                        check()
                                        val prediction = (result[0] as OnnxTensor).floatBuffer
                                        require(prediction.remaining() == 6 * 2 * LENGTH) { "模型输出尺寸不匹配" }
                                        val weights = FloatArray(LENGTH) { MixMath.overlapWeight(it, LENGTH, OVERLAP, at == 0L, last) }
                                        val emit = if (last) valid else STRIDE
                                        val output = Array(6) { FloatArray(emit * 2) }
                                        for (stem in 0 until 6) {
                                            for (i in 0 until valid) {
                                                val w = weights[i]
                                                val previousWeight = if (at > 0 && i < OVERLAP) tailWeights[i] else 0f
                                                for (channel in 0..1) {
                                                    var value = prediction.get((stem * 2 + channel) * LENGTH + i) * w
                                                    if (at > 0 && i < OVERLAP) value += tail[stem][i * 2 + channel]
                                                    if (i < emit) output[stem][i * 2 + channel] = value / (w + previousWeight)
                                                    else tail[stem][(i - STRIDE) * 2 + channel] = value
                                                }
                                            }
                                        }
                                        for (i in 0 until OVERLAP) tailWeights[i] = weights[STRIDE + i]
                                        definitions.forEachIndexed { index, stem ->
                                            val samples = when (stem.id) {
                                                "instrumental" -> FloatArray(emit * 2) { source[it] - output[3][it] }
                                                // Assign reconstruction error to 'other' so unity gains reproduce the input.
                                                "other" -> FloatArray(emit * 2) { i -> source[i] - output[0][i] - output[1][i] - output[3][i] - output[4][i] - output[5][i] }
                                                else -> output[modelOrder.indexOf(stem.id)]
                                            }
                                            require(samples.all { it.isFinite() }) { "模型输出异常，请重新处理。" }
                                            writers[index].write(samples)
                                        }
                                        at += emit
                                        progress(at.toFloat() / reader.frames)
                                    }
                                }
                            }
                        }
                        activeRun = null
                    }
                }
            }
            writers.forEach { it.close() }
            check()
            files.forEachIndexed { i, file ->
                val dest = store.stem(p, definitions[i].id)
                if (dest.exists()) dest.delete()
                require(file.renameTo(dest)) { "无法保存音轨" }
            }
            success = true
            return definitions
        } finally {
            activeRun = null
            writers.forEach { runCatching { it.close() } }
            if (!success) files.forEach { it.delete() }
        }
    }
    companion object {
        const val LENGTH = 343980
        const val OVERLAP = LENGTH / 4
        const val STRIDE = LENGTH - OVERLAP
    }
}
