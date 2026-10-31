package app.pureeq.system

import android.media.audiofx.DynamicsProcessing
import app.pureeq.audio.OutputDevice
import app.pureeq.dsp.Curves
import app.pureeq.dsp.FreqGrid
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
        val down = if (headroom) maxOf(shelves.bassDb, shelves.trebleDb, 0.0) else 0.0
        return SystemCurves(
            DoubleArray(FreqGrid.SIZE) { left[it] + add[it] - down },
            DoubleArray(FreqGrid.SIZE) { right[it] + add[it] - down },
        )
    }

    companion object {
        fun of(eq: ProfileEq) = SystemCurves(
            DoubleArray(FreqGrid.SIZE) { eq.leftCurve[it] + eq.preampDb },
            DoubleArray(FreqGrid.SIZE) { eq.rightCurve[it] + eq.preampDb },
        )
    }
}

/**
 * Band layout for Android's DynamicsProcessing pre-EQ.
 * The engine is FFT based: each band's gain is applied to every bin up to its cutoff, as a
 * staircase without interpolation. With 40 ms frames the FFT is 2048 points at 44.1/48 kHz
 * (about 22-23 Hz per bin), so up to about 530 Hz every bin gets its own band and above that
 * bands are 1/16 octave: about 107 bands, inside the 128 that newer Android versions allow.
 * The last edge sits far above Nyquist so every bin is written; the engine bounds its loops
 * to the spectrum size. The block also sets the latency: about 43 ms at 48 kHz.
 */
object DpBands {
    const val FRAME_MS = 40f
    private const val BIN_HZ = 48000.0 / 2048
    private val STEP = 2.0.pow(1.0 / 16)

    /**
     * The DC bin also holds everything below about 12 Hz. A boost there only spends headroom
     * and driver excursion on sound nobody hears, so it's held well down instead.
     */
    private const val DC_GAIN_DB = -12.0

    /** Bands below this index each own one FFT bin; their gain is the curve at that bin. */
    private val binBands: Int

    val edges: DoubleArray = buildList {
        var k = 0
        while (true) {
            val e = (k + 0.25) * BIN_HZ
            if (k > 0 && e * (STEP - 1) >= BIN_HZ) break
            add(e)
            k++
        }
        binBands = size
        var f = last() * STEP
        while (f < 20000.0) {
            add(f)
            f *= STEP
        }
        add(48000.0)
    }.toDoubleArray()

    /**
     * Gain of each band. Narrow bass bands take the curve at the bin they own (bin k sits at
     * k × 23.4 Hz at 48 kHz, and lands on almost the same frequency at 44.1 kHz). Wider bands take
     * the curve averaged over the band in log-frequency.
     */
    fun gains(curveDb: DoubleArray): FloatArray {
        var lo = 0.0
        return FloatArray(edges.size) { b ->
            val hi = edges[b]
            val g = if (b == 0) {
                DC_GAIN_DB
            } else if (b < binBands) {
                Curves.sample(curveDb, max(b * BIN_HZ, FreqGrid.MIN_HZ))
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
}

/** One DynamicsProcessing instance on an audio session (0 = the global output mix). */
class DpEffect(val sessionId: Int, curves: SystemCurves) {
    private val bands = DpBands.edges.size
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
        ).setPreferredFrameDuration(DpBands.FRAME_MS).build()
        config.setPreEqByChannelIndex(0, eqFor(curves.left))
        config.setPreEqByChannelIndex(1, eqFor(curves.right))
        // A ceiling on sustained level only: it follows each frame's RMS (about 21 ms) without
        // look-ahead, so it can't catch sample peaks. The preamp is what keeps music below full scale.
        config.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        dp = DynamicsProcessing(PRIORITY, sessionId, config)
        dp.enabled = true
    }

    fun update(curves: SystemCurves) {
        dp.setPreEqByChannelIndex(0, eqFor(curves.left))
        dp.setPreEqByChannelIndex(1, eqFor(curves.right))
    }

    fun setEnabled(on: Boolean) {
        runCatching { dp.enabled = on }
    }

    fun release() {
        runCatching { dp.release() }
    }

    private fun eqFor(curve: DoubleArray): DynamicsProcessing.Eq {
        val gains = DpBands.gains(curve)
        val eq = DynamicsProcessing.Eq(true, true, bands)
        for (b in 0 until bands) {
            // Keep every band enabled: a disabled band gets the input gain applied twice.
            eq.setBand(b, DynamicsProcessing.EqBand(true, DpBands.edges[b].toFloat(), gains[b]))
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
}
