package app.pureeq.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import app.pureeq.dsp.FilterChain
import app.pureeq.dsp.Stimulus
import app.pureeq.dsp.StereoEqSpec
import app.pureeq.dsp.ToneGenerator
import app.pureeq.dsp.dbToGain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * PureEQ's own player: one float AudioTrack, fed by a dedicated thread.
 * - Test beeps are written straight to the output, untouched by any EQ.
 * - Music goes through two EQ slots (A and B) that both run all the time, so switching between
 *   them is an instant, click-free 40 ms crossfade on the same musical moment.
 * - Replacing a slot's filters warms the new filters on the last ~85 ms of input first, so
 *   filters never "start from silence" audibly.
 */
class AudioEngine(context: Context) {

    val nativeRate: Int = run {
        val am = context.getSystemService(AudioManager::class.java)
        am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 48000
    }

    private sealed interface Cmd {
        data class Tone(val stimulus: Stimulus) : Cmd
        data object StopTone : Cmd
        data class Music(val pcm: FloatArray?, val rate: Int) : Cmd
        data class Slot(val index: Int, val spec: StereoEqSpec) : Cmd
        data class Select(val index: Int) : Cmd
    }

    private val commands = ConcurrentLinkedQueue<Cmd>()
    @Volatile private var running = false
    @Volatile private var musicPlaying = false
    @Volatile private var keepAlive = false
    private var thread: Thread? = null

    private val _toneActive = MutableStateFlow(false)
    /** True while a beep (or a silent catch trial) is being rendered. */
    val toneActive: StateFlow<Boolean> = _toneActive.asStateFlow()

    /**
     * Time from writing a sample to it leaving the device, in ms. Measured from the track's
     * playback timestamps, which include the mixer, the HAL and (on modern Android) Bluetooth.
     */
    @Volatile var latencyMs: Int = 150
        private set

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "PureEQ-audio").apply { start() }
    }

    /** While on, the thread never idles out, so the output stream stays awake between beeps. */
    fun setKeepAlive(on: Boolean) {
        keepAlive = on
        if (on) start()
    }

    /** Called by the audio thread as it exits; restarts it if work arrived while it was stopping. */
    @Synchronized
    private fun onLoopExit() {
        running = false
        thread = null
        _toneActive.value = false
        if (commands.isNotEmpty() || musicPlaying || keepAlive) start()
    }

    fun playTone(stimulus: Stimulus) {
        start()
        commands += Cmd.Tone(stimulus)
        _toneActive.value = true
    }

    fun stopTone() {
        commands += Cmd.StopTone
    }

    /** Sets the music loop (interleaved stereo at [rate]); null clears it. */
    fun setMusic(pcm: FloatArray?, rate: Int) {
        commands += Cmd.Music(pcm, rate)
    }

    fun playMusic(play: Boolean) {
        if (play) start()
        musicPlaying = play
    }

    val isMusicPlaying: Boolean get() = musicPlaying

    fun setSlot(index: Int, spec: StereoEqSpec) {
        commands += Cmd.Slot(index, spec)
    }

    fun select(index: Int) {
        commands += Cmd.Select(index)
    }

    // ---- Audio thread ----------------------------------------------------------------------

    private fun buildTrack(rate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        // ~60 ms: A/B switches feel immediate, and still safe against scheduling hiccups.
        val bytes = max(minBuf, rate * 60 / 1000 * 8)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bytes)
            .build()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var rate = nativeRate
        var track = runCatching { buildTrack(rate) }.getOrElse {
            Log.e(TAG, "No audio output available", it)
            commands.clear()
            musicPlaying = false
            onLoopExit()
            return
        }
        track.play()
        var tone = ToneGenerator(rate.toDouble())
        var eq = EqMixer(rate.toDouble())
        val frames = 256
        val out = FloatArray(frames * 2)
        val musicBuf = FloatArray(frames * 2)
        var music: FloatArray? = null
        var musicPos = 0
        var musicLevel = 0.0
        val levelStep = 1.0 / (0.03 * rate)
        val slotSpecs = arrayOfNulls<StereoEqSpec>(2)
        var selected = 0
        var idleBlocks = 0
        var framesWritten = 0L
        var blocks = 0L
        val stamp = AudioTimestamp()
        try {
            while (running) {
                while (true) {
                    when (val c = commands.poll() ?: break) {
                        is Cmd.Tone -> tone.start(c.stimulus)
                        Cmd.StopTone -> tone.stop()
                        is Cmd.Music -> {
                            music = c.pcm
                            musicPos = 0
                            musicLevel = 0.0
                            if (c.pcm != null && c.rate != rate) {
                                // Rebuild everything at the music's own rate; AudioFlinger resamples well.
                                track.stop()
                                track.release()
                                rate = c.rate
                                track = buildTrack(rate)
                                track.play()
                                framesWritten = 0
                                tone = ToneGenerator(rate.toDouble())
                                eq = EqMixer(rate.toDouble())
                                slotSpecs.forEachIndexed { i, s -> if (s != null) eq.setSlot(i, s) }
                                eq.select(selected, immediate = true)
                            }
                        }
                        is Cmd.Slot -> {
                            slotSpecs[c.index] = c.spec
                            eq.setSlot(c.index, c.spec)
                        }
                        is Cmd.Select -> {
                            selected = c.index
                            eq.select(c.index, immediate = false)
                        }
                    }
                }
                out.fill(0f)
                val m = music
                val wantMusic = musicPlaying && m != null
                if (m != null && (wantMusic || musicLevel > 0.0)) {
                    for (n in 0 until frames) {
                        musicBuf[2 * n] = m[musicPos]
                        musicBuf[2 * n + 1] = m[musicPos + 1]
                        musicPos += 2
                        if (musicPos >= m.size) musicPos = 0
                    }
                    eq.process(musicBuf, frames)
                    for (n in 0 until frames) {
                        musicLevel = if (wantMusic) min(1.0, musicLevel + levelStep) else max(0.0, musicLevel - levelStep)
                        out[2 * n] = softClip(musicBuf[2 * n] * musicLevel.toFloat())
                        out[2 * n + 1] = softClip(musicBuf[2 * n + 1] * musicLevel.toFloat())
                    }
                }
                tone.render(out, frames)
                if (_toneActive.value != tone.isActive) _toneActive.value = tone.isActive
                track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                framesWritten += frames
                if (++blocks % 64 == 0L && track.getTimestamp(stamp)) {
                    val presented = stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * rate / 1_000_000_000L
                    val ms = ((framesWritten - presented) * 1000 / rate).toInt()
                    if (ms in 1..1500) latencyMs = ms
                }
                // Stop the thread after ~3 s of nothing to play (saves battery); it restarts on demand.
                idleBlocks = if (tone.isActive || wantMusic || musicLevel > 0.0) 0 else idleBlocks + 1
                if (!keepAlive && idleBlocks > 3 * rate / frames && commands.isEmpty()) break
            }
        } catch (e: Exception) {
            // A routing change can invalidate the track; drop pending work rather than crash the app.
            Log.e(TAG, "Audio thread stopped", e)
            commands.clear()
            musicPlaying = false
        } finally {
            runCatching {
                track.pause()
                track.flush()
                track.release()
            }
            onLoopExit()
        }
    }

    /** Transparent below -0.5 dBFS, then a smooth knee instead of hard digital clipping. */
    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val over = (a - KNEE) / (1f - KNEE)
        val y = KNEE + (1f - KNEE) * tanh(over.toDouble()).toFloat()
        return if (x < 0) -y else y
    }

    private companion object {
        const val KNEE = 0.944f
        const val TAG = "PureEQ"
    }
}

/**
 * Two always-running stereo EQ slots, mixed with per-sample crossfade weights, plus an
 * outgoing chain that fades out when a slot's filters are replaced while it is heard.
 */
private class EqMixer(private val sampleRate: Double) {

    private class Chain(spec: StereoEqSpec, sr: Double) {
        val l = FilterChain(spec.left, sr, dbToGain(spec.leftGainDb))
        val r = FilterChain(spec.right, sr, dbToGain(spec.rightGainDb))
    }

    private val slots = arrayOfNulls<Chain>(2)
    private val weight = DoubleArray(2).also { it[0] = 1.0 }
    private val target = DoubleArray(2).also { it[0] = 1.0 }
    private var outgoing: Chain? = null
    private var outgoingWeight = 0.0
    private val step = 1.0 / (0.04 * sampleRate)

    private val histL = FloatArray(HISTORY)
    private val histR = FloatArray(HISTORY)
    private var histPos = 0

    fun setSlot(index: Int, spec: StereoEqSpec) {
        val chain = Chain(spec, sampleRate)
        for (k in 0 until HISTORY) {
            val i = (histPos + k) % HISTORY
            chain.l.process(histL[i].toDouble())
            chain.r.process(histR[i].toDouble())
        }
        val old = slots[index]
        if (old != null && weight[index] > 0.0) {
            outgoing = old
            outgoingWeight = weight[index]
            weight[index] = 0.0
        }
        slots[index] = chain
    }

    fun select(index: Int, immediate: Boolean) {
        for (i in 0..1) {
            target[i] = if (i == index) 1.0 else 0.0
            if (immediate) weight[i] = target[i]
        }
    }

    fun process(buf: FloatArray, frames: Int) {
        val a = slots[0]
        val b = slots[1]
        val o = outgoing
        for (n in 0 until frames) {
            val xl = buf[2 * n]
            val xr = buf[2 * n + 1]
            histL[histPos] = xl
            histR[histPos] = xr
            histPos = (histPos + 1) % HISTORY
            for (i in 0..1) {
                val w = weight[i]
                val t = target[i]
                if (w < t) weight[i] = min(t, w + step) else if (w > t) weight[i] = max(t, w - step)
            }
            var yl = 0.0
            var yr = 0.0
            // Both slots always process so the silent one stays warm for an instant switch.
            if (a != null) {
                val la = a.l.process(xl.toDouble())
                val ra = a.r.process(xr.toDouble())
                yl += la * weight[0]
                yr += ra * weight[0]
            }
            if (b != null) {
                val lb = b.l.process(xl.toDouble())
                val rb = b.r.process(xr.toDouble())
                yl += lb * weight[1]
                yr += rb * weight[1]
            }
            if (o != null) {
                yl += o.l.process(xl.toDouble()) * outgoingWeight
                yr += o.r.process(xr.toDouble()) * outgoingWeight
                outgoingWeight -= step
                if (outgoingWeight <= 0.0) outgoing = null
            }
            if (a == null && b == null) {
                yl = xl.toDouble()
                yr = xr.toDouble()
            }
            buf[2 * n] = yl.toFloat()
            buf[2 * n + 1] = yr.toFloat()
        }
    }

    private companion object {
        const val HISTORY = 4096
    }
}
