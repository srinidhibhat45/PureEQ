package app.pureeq.system

import android.media.audiofx.DynamicsProcessing
import app.pureeq.audio.OutputDevice
import app.pureeq.dsp.Curves
import app.pureeq.dsp.FreqGrid
import app.pureeq.profile.ListeningMode
import app.pureeq.profile.ModeTuning
import app.pureeq.profile.ProfileEq
import app.pureeq.profile.VolumeBass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Per-ear curves to apply system-wide, in dB on [FreqGrid], preamp and balance included. */
class SystemCurves(val left: DoubleArray, val right: DoubleArray) {

    /**
     * With [shelves] for the volume added to both ears. Where the effect may see full-scale audio
     * ([VolumeBass.needsHeadroom]) the level drops by the largest boost, so it can't clip.
     */
    fun with(shelves: VolumeBass.Shelves, headroom: Boolean): SystemCurves {
        if (shelves.isFlat) return this
        val add = shelves.curve()
        val down = headroomDb(shelves, headroom)
        return SystemCurves(
            DoubleArray(FreqGrid.SIZE) { left[it] + add[it] - down },
            DoubleArray(FreqGrid.SIZE) { right[it] + add[it] - down },
        )
    }

    companion object {
        /** How far [with] drops the level for [shelves]' boost. */
        fun headroomDb(shelves: VolumeBass.Shelves, headroom: Boolean): Double =
            if (headroom && !shelves.isFlat) maxOf(shelves.bassDb, shelves.trebleDb, 0.0) else 0.0

        fun of(eq: ProfileEq) = SystemCurves(
            DoubleArray(FreqGrid.SIZE) { eq.leftCurve[it] + eq.preampDb },
            DoubleArray(FreqGrid.SIZE) { eq.rightCurve[it] + eq.preampDb },
        )
    }
}

/**
 * Band layout for Android's DynamicsProcessing pre-EQ, for one frame length.
 * The engine is FFT based: each band's gain is applied to every bin up to its cutoff, as a
 * staircase without interpolation. The FFT is the frame at the sample rate rounded up to a power
 * of two: 2048 points for 40 ms frames at 44.1/48 kHz (about 22-23 Hz per bin), 1024 for 20 ms
 * (about 43-47 Hz). Up to where 1/16 octave is wider than a bin (about 530 Hz at 2048 points,
 * 1 kHz at 1024) every bin gets its own band, and above that bands are 1/16 octave: about 107
 * bands at 2048 points and 91 at 1024, inside the 128 that newer Android versions allow. The
 * last edge is the highest cutoff the engine accepts (192 kHz), above Nyquist at every output
 * rate up to 384 kHz, so every bin is written (at 48 kHz an edge would leave bins above it at
 * 0 dB, without the preamp); the engine bounds its loops to the spectrum size. The block also
 * sets the latency: about 43 ms at 2048 points, 21 ms at 1024.
 */
class DpBands private constructor(fftSize: Int) {
    private val binHz = 48000.0 / fftSize

    /** Bands below this index each own one FFT bin; their gain is the curve at that bin. */
    private val binBands: Int

    /**
     * Edges a quarter bin above each bin: the engine gives a band every bin up to
     * (int)(0.5 + cutoff × N / rate), so band k ends exactly at bin k at 48 kHz (DPFrequency.cpp,
     * computeBinStartStop). Laid out for another bin width, bins would take a neighbour's gain.
     */
    val edges: DoubleArray = buildList {
        var k = 0
        while (true) {
            val e = (k + 0.25) * binHz
            if (k > 0 && e * (STEP - 1) >= binHz) break
            add(e)
            k++
        }
        binBands = size
        var f = last() * STEP
        while (f < 20000.0) {
            add(f)
            f *= STEP
        }
        add(LAST_EDGE_HZ)
    }.toDoubleArray()

    /**
     * Gain of each band. Narrow bass bands take the curve at the bin they own (bin k sits at
     * k × the bin width at 48 kHz, and lands on almost the same frequency at 44.1 kHz). Wider bands
     * take the curve averaged over the band in log-frequency. The DC band takes the first bin's
     * gain, never above 0 dB: the engine has no zero padding, so a step between neighbouring bins
     * is a long filter folded into each 43 ms block. Holding DC 12 dB under its neighbour (1.5 to
     * 1.7.2) left an error 30 dB under the music, most of it below 25 Hz; following the curve, 47
     * to 53 dB under (simulated against AOSP DPFrequency.cpp).
     */
    fun gains(curveDb: DoubleArray): FloatArray {
        var lo = 0.0
        return FloatArray(edges.size) { b ->
            val hi = edges[b]
            val g = if (b == 0) {
                min(Curves.sample(curveDb, max(binHz, FreqGrid.MIN_HZ)), 0.0)
            } else if (b < binBands) {
                Curves.sample(curveDb, max(b * binHz, FreqGrid.MIN_HZ))
            } else {
                val a = max(lo, FreqGrid.MIN_HZ)
                val z = min(hi, FreqGrid.MAX_HZ)
                if (z <= a) {
                    Curves.sample(curveDb, FreqGrid.MAX_HZ)
                } else {
                    val n = 8
                    var s = 0.0
                    for (i in 0 until n) s += Curves.sample(curveDb, a * (z / a).pow((i + 0.5) / n))
                    s / n
                }
            }
            lo = hi
            g.toFloat()
        }
    }

    companion object {
        private val STEP = 2.0.pow(1.0 / 16)

        /** The highest band cutoff Android's DynamicsProcessing accepts (AIDL range 0-192000 Hz). */
        private const val LAST_EDGE_HZ = 192000.0

        /** The engine's FFT for [frameMs]: the frame at 48 kHz rounded up to a power of two (44.1 kHz gives the same). */
        fun fftSize(frameMs: Float): Int {
            val want = (frameMs * 48).toInt()
            var n = 8
            while (n < want) n *= 2
            return n
        }

        private val layouts = HashMap<Int, DpBands>()

        @Synchronized
        fun forFrame(frameMs: Float): DpBands = layouts.getOrPut(fftSize(frameMs)) { DpBands(fftSize(frameMs)) }
    }
}

/**
 * One DynamicsProcessing instance on an audio session (0 = the global output mix), with the
 * frame and leveller of the profile's mode ([ModeTuning]). The frame is fixed at creation.
 * [enabled] false makes it ready but silent, so it can take over from another at once ([EqService]).
 */
class DpEffect(val sessionId: Int, curves: SystemCurves, frameMs: Float, leveller: ModeTuning.Leveller?, enabled: Boolean = true) {
    private val layout = DpBands.forFrame(frameMs)
    private val bands = layout.edges.size
    private val dp: DynamicsProcessing

    init {
        val config = DynamicsProcessing.Config.Builder(
            // The only variant AOSP implements; others fail to create.
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, bands,
            false, 0,
            false, 0,
            true,
        ).setPreferredFrameDuration(frameMs).build()
        config.setPreEqByChannelIndex(0, eqFor(curves.left))
        config.setPreEqByChannelIndex(1, eqFor(curves.right))
        config.setLimiterAllChannelsTo(limiter(leveller))
        dp = DynamicsProcessing(PRIORITY, sessionId, config)
        dp.enabled = enabled
    }

    fun update(curves: SystemCurves) {
        dp.setPreEqByChannelIndex(0, eqFor(curves.left))
        dp.setPreEqByChannelIndex(1, eqFor(curves.right))
    }

    /** The mode's leveller, or the plain ceiling; the limiter stage is always in use, so this needs no new effect. */
    fun setLeveller(l: ModeTuning.Leveller?) {
        dp.setLimiterAllChannelsTo(limiter(l))
    }

    /**
     * Both channels share link group 0, so they always get the same gain and a sound on one side
     * never pulls the image to the other. Without a leveller it is a ceiling on sustained level
     * only: it follows each frame's RMS without look-ahead, so it can't catch sample peaks. The
     * preamp is what keeps music below full scale. A leveller has no make-up gain, so it only ever
     * turns down, and it turns sustained levels near full scale down further than the ceiling would.
     */
    private fun limiter(l: ModeTuning.Leveller?): DynamicsProcessing.Limiter =
        if (l == null) {
            DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f)
        } else {
            DynamicsProcessing.Limiter(true, true, 0, l.attackMs, l.releaseMs, l.ratio, l.thresholdDb, 0f)
        }

    fun setEnabled(on: Boolean) {
        runCatching { dp.enabled = on }
    }

    fun release() {
        runCatching { dp.release() }
    }

    private fun eqFor(curve: DoubleArray): DynamicsProcessing.Eq {
        val gains = layout.gains(curve)
        val eq = DynamicsProcessing.Eq(true, true, bands)
        for (b in 0 until bands) {
            // Keep every band enabled: a disabled band gets the input gain applied twice.
            eq.setBand(b, DynamicsProcessing.EqBand(true, layout.edges[b].toFloat(), gains[b]))
        }
        return eq
    }

    private companion object {
        const val CHANNELS = 2
        const val PRIORITY = 100
    }
}

sealed interface SystemEqStatus {
    data object Off : SystemEqStatus
    data class Active(val profileName: String, val global: Boolean, val sessions: Int) : SystemEqStatus
    data class Paused(val profileName: String) : SystemEqStatus

    /** Off for [output] (nothing applied there) and on for [onFor], which bring it back. */
    data class Standby(val output: OutputDevice, val onFor: List<String>) : SystemEqStatus
    data class Failed(val message: String) : SystemEqStatus
}

/** Process-wide runtime state shared by the UI and [EqService]. */
object EqRuntime {
    private val holds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * True while PureEQ itself plays test beeps or previews. The system EQ steps aside then,
     * so it doesn't colour the very sounds used to measure and compare.
     */
    val suspended = holds.map { it.isNotEmpty() }

    fun hold(tag: String, on: Boolean) {
        holds.value = if (on) holds.value + tag else holds.value - tag
    }

    val status = MutableStateFlow<SystemEqStatus>(SystemEqStatus.Off)

    /** What the volume adds to the system-wide EQ right now ([VolumeBass]); null while it isn't applied. */
    val volumeShelves = MutableStateFlow<VolumeBass.Shelves?>(null)

    /** Auto's pick for what's playing ([app.pureeq.profile.AutoSwitch]); null while the service isn't running or hasn't heard anything. */
    val autoMode = MutableStateFlow<ListeningMode?>(null)
}
