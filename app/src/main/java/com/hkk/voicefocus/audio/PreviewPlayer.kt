package com.hkk.voicefocus.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.content.Context
import com.hkk.voicefocus.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.Closeable
import kotlin.math.min

data class Playback(val playing: Boolean = false, val frame: Long = 0, val error: String? = null)

/** One AudioTrack mixes all stems on the same sample clock; sliders update without a re-render. */
class PreviewPlayer(context: Context) : Closeable {
    val state = MutableStateFlow(Playback())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    @Volatile private var generation = 0
    @Volatile private var gains = floatArrayOf()
    @Volatile var original = false
    @Volatile private var seekTo = -1L
    fun update(p: Project) { gains = p.gains() }
    fun seek(frame: Long) { seekTo = frame; state.value = state.value.copy(frame = frame) }
    fun play(store: ProjectStore, p: Project) {
        if (state.value.playing) { pause(); return }
        update(p)
        val start = if (state.value.frame >= p.frames) 0L else state.value.frame
        val previousJob = job
        previousJob?.cancel()
        val token = ++generation
        state.value = Playback(true, start)
        job = scope.launch {
            previousJob?.join()
            ensureActive()
            val readers = p.stems.map { PcmReader(store.stem(p, it.id)) }
            val source = PcmReader(store.pcm(p))
            var track: AudioTrack? = null
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS || it == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause() }.build()
            var baseFrame = start
            try {
                require(audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "暂时无法获取音频播放焦点，请重试。" }
                val size = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(8192)
                val output = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes(size * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
                track = output
                require(output.state == AudioTrack.STATE_INITIALIZED) { "无法启动音频播放" }
                output.play()
                var at = start
                var limiter = PeakLimiter()
                var prior = gains.copyOf()
                var priorOriginal = original
                while (isActive && at < p.frames) {
                    if (seekTo >= 0) {
                        at = seekTo.coerceIn(0, p.frames); seekTo = -1
                        output.pause(); output.flush(); output.play(); limiter = PeakLimiter()
                        baseFrame = at
                    }
                    val n = min(1024L, p.frames - at).toInt()
                    if (n <= 0) break
                    val current = gains.copyOf()
                    val compare = original
                    val stems = readers.map { it.read(at, n) }
                    val mixed = MixMath.mix(stems, current)
                    val previous = MixMath.mix(stems, prior)
                    val originalSamples = source.read(at, n)
                    for (i in 0 until n) {
                        val ramp = min(1f, i / 440f)
                        for (ch in 0..1) {
                            val k = 2 * i + ch
                            val from = if (priorOriginal) originalSamples[k] else previous[k]
                            val to = if (compare) originalSamples[k] else mixed[k]
                            mixed[k] = from + ramp * (to - from)
                        }
                    }
                    limiter.apply(mixed)
                    var written = 0
                    while (written < mixed.size && isActive) {
                        val count = output.write(mixed, written, mixed.size - written, AudioTrack.WRITE_BLOCKING)
                        require(count > 0) { "音频播放失败：$count" }
                        written += count
                    }
                    prior = current; priorOriginal = compare; at += n
                    if (token == generation) state.value = Playback(true, (baseFrame + (output.playbackHeadPosition.toLong() and 0xffffffffL)).coerceAtMost(p.frames))
                }
                while (isActive && baseFrame + (output.playbackHeadPosition.toLong() and 0xffffffffL) < at) {
                    if (token == generation) state.value = Playback(true, (baseFrame + (output.playbackHeadPosition.toLong() and 0xffffffffL)).coerceAtMost(p.frames))
                    delay(20)
                }
                if (token == generation) state.value = Playback(false, at)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) state.value = state.value.copy(error = e.message) }
            finally {
                if (token == generation && track != null) state.value = state.value.copy(frame = (baseFrame + (track.playbackHeadPosition.toLong() and 0xffffffffL)).coerceAtMost(p.frames))
                runCatching { track?.pause(); track?.flush(); track?.release() }
                readers.forEach { it.close() }; source.close()
                audioManager.abandonAudioFocusRequest(focus)
                if (token == generation) state.value = state.value.copy(playing = false)
            }
        }
    }
    fun pause() { job?.cancel(); state.value = state.value.copy(playing = false) }
    fun reset() { pause(); generation++; seekTo = -1; original = false; state.value = Playback() }
    override fun close() { pause(); scope.cancel() }
}
