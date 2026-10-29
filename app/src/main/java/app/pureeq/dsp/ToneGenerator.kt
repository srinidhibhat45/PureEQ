package app.pureeq.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

enum class Ear { LEFT, RIGHT, BOTH }

/**
 * One test presentation: a few pulsed beeps in one ear, or a silent catch trial with the same timing.
 * [levelDb] is the peak level in dBFS. Timing follows clinical pulsed-tone practice
 * (about 225 ms on, 225 ms off, 25 ms raised-cosine ramps so the beep has no click).
 */
data class Stimulus(
    val freq: Double,
    val ear: Ear,
    val levelDb: Double,
    val warble: Boolean = true,
    val pulses: Int = 3,
    val onMs: Double = 225.0,
    val offMs: Double = 225.0,
    val rampMs: Double = 25.0,
    val silent: Boolean = false,
    /** Keep pulsing until stopped (used while the listener sets the volume). */
    val repeat: Boolean = false,
) {
    val durationMs: Double get() = pulses * onMs + (pulses - 1) * offMs
}

/**
 * Renders a [Stimulus] sample-accurately. A warble tone sweeps ±5 % around the test frequency
 * eight times a second. That averages over the narrow peaks and notches earbuds have around
 * 5-10 kHz, so one notch doesn't masquerade as hearing loss. Used only on the audio thread.
 */
class ToneGenerator(private val sampleRate: Double) {
    private var stim: Stimulus? = null
    private var pos = 0L
    private var onFrames = 0L
    private var periodFrames = 1L
    private var endFrame = 0L
    private var rampFrames = 1.0
    private var phase = 0.0
    private var amp = 0.0
    private var fadeFrames = 0
    private var fadeLeft = -1

    val isActive: Boolean get() = stim != null

    fun start(s: Stimulus) {
        stim = s
        pos = 0
        phase = 0.0
        onFrames = (s.onMs * sampleRate / 1000).toLong()
        periodFrames = ((s.onMs + s.offMs) * sampleRate / 1000).toLong().coerceAtLeast(1)
        rampFrames = (s.rampMs * sampleRate / 1000).coerceAtLeast(1.0)
        endFrame = (s.pulses - 1) * periodFrames + onFrames
        amp = if (s.silent) 0.0 else 10.0.pow(s.levelDb / 20.0)
        fadeLeft = -1
    }

    /** Ends the tone with a short fade so stopping mid-beep doesn't click. */
    fun stop() {
        if (stim == null) return
        fadeFrames = (0.015 * sampleRate).toInt()
        fadeLeft = fadeFrames
    }

    /** Adds the tone into interleaved stereo [out]. */
    fun render(out: FloatArray, frames: Int) {
        val s = stim ?: return
        val twoPi = 2 * PI
        for (n in 0 until frames) {
            if ((!s.repeat && pos >= endFrame) || fadeLeft == 0) {
                stim = null
                return
            }
            val within = pos % periodFrames
            var env = 0.0
            if (within < onFrames) {
                env = ramp(within.toDouble()) * ramp((onFrames - within).toDouble())
            }
            if (fadeLeft > 0) {
                env *= fadeLeft.toDouble() / fadeFrames
                fadeLeft--
            }
            val t = pos / sampleRate
            val f = if (s.warble) s.freq * (1.0 + WARBLE_DEPTH * sin(twoPi * WARBLE_RATE * t)) else s.freq
            phase += twoPi * f / sampleRate
            if (phase > twoPi) phase -= twoPi
            val v = (amp * env * sin(phase)).toFloat()
            when (s.ear) {
                Ear.LEFT -> out[2 * n] += v
                Ear.RIGHT -> out[2 * n + 1] += v
                Ear.BOTH -> {
                    out[2 * n] += v
                    out[2 * n + 1] += v
                }
            }
            pos++
        }
    }

    private fun ramp(x: Double): Double = if (x >= rampFrames) 1.0 else 0.5 * (1 - cos(PI * x / rampFrames))

    private companion object {
        const val WARBLE_DEPTH = 0.05
        const val WARBLE_RATE = 8.0
    }
}
