package app.pureeq.profile

import app.pureeq.audio.ListeningLevel
import app.pureeq.audio.OutputDevice
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.dsp.FreqGrid
import app.pureeq.dsp.Response
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bass that follows the volume. Ears lose bass faster than mids as sound gets quieter (ISO 226:
 * about 0.31 dB more at 100 Hz for each dB the level drops, and a little at 10 kHz), so a balance
 * chosen at one volume sounds thin below it. The taste test's volume is the reference: turn down
 * from there and the system-wide EQ adds back half of what the loudness contours lose (Holman and
 * Kampmann: full compensation overshoots, and only the change from the reference counts), up to
 * [MAX_BASS_DB]; above it, a little less bass, down to [MIN_BASS_DB].
 *
 * It needs the volume the profile was tuned at, and listening on the same kind of output (another
 * kind has another volume curve and other headphones).
 */
object VolumeBass {
    /** Share of the loudness contours' change that is compensated. */
    const val SHARE = 0.5
    private const val BASS_PER_DB = 0.31
    private const val TREBLE_PER_DB = 0.07
    const val MAX_BASS_DB = 6.0
    const val MIN_BASS_DB = -2.0
    const val MAX_TREBLE_DB = 2.0
    private const val BASS_HZ = 105.0
    private const val TREBLE_HZ = 10000.0
    private const val Q = 0.7

    /** Changes smaller than this aren't worth rebuilding the effect for. */
    const val STEP_DB = 0.25

    /** What the volume adds right now. */
    data class Shelves(val bassDb: Double, val trebleDb: Double) {
        val isFlat: Boolean get() = abs(bassDb) < 0.05 && abs(trebleDb) < 0.05

        fun filters(): List<FilterSpec> = listOfNotNull(
            FilterSpec(FilterType.LOW_SHELF, BASS_HZ, bassDb, Q).takeIf { abs(bassDb) >= 0.05 },
            FilterSpec(FilterType.HIGH_SHELF, TREBLE_HZ, trebleDb, Q).takeIf { abs(trebleDb) >= 0.05 },
        )

        /** The shelves' response on [FreqGrid], in dB. */
        fun curve(): DoubleArray = Response.of(filters(), FreqGrid.hz)

        /** Rounded to [STEP_DB], so small volume moves don't rebuild the effect. */
        fun rounded() = Shelves(round(bassDb), round(trebleDb))

        private fun round(db: Double) = (db / STEP_DB).roundToInt() * STEP_DB
    }

    val FLAT = Shelves(0.0, 0.0)

    /**
     * The shelves for listening at [now] on [output] with [profile]; [FLAT] when it's off, when
     * the profile has no tuning volume, or when the output is another kind than the one it was
     * tuned on.
     */
    fun shelves(profile: Profile, output: OutputDevice, now: ListeningLevel?): Shelves {
        val ref = profile.tasteLevel ?: return FLAT
        if (!profile.volumeBass || now == null) return FLAT
        if (OutputDevice("", ref.deviceType).kind != output.kind) return FLAT
        return forDrop(dropDb(ref, now) ?: return FLAT)
    }

    /** The shelves for listening [dropDb] below the tuning volume (negative: above it). */
    fun forDrop(dropDb: Double): Shelves = Shelves(
        bassDb = (SHARE * BASS_PER_DB * dropDb).coerceIn(MIN_BASS_DB, MAX_BASS_DB),
        trebleDb = (SHARE * TREBLE_PER_DB * dropDb).coerceIn(0.0, MAX_TREBLE_DB),
    )

    /**
     * How much quieter [now] is than [ref], in dB: from Android's own dB for each volume step when
     * both have it, else from AOSP's default media curve. Null when it can't be told.
     */
    fun dropDb(ref: ListeningLevel, now: ListeningLevel): Double? {
        if (ref.db != null && now.db != null) return ref.db - now.db
        val r = defaultCurveDb(ref) ?: return null
        val n = defaultCurveDb(now) ?: return null
        return r - n
    }

    /** AOSP's default media volume curve: share of the volume range → attenuation in dB. */
    private val CURVE = arrayOf(0.01 to -58.0, 0.20 to -40.0, 0.60 to -17.0, 1.0 to 0.0)

    private fun defaultCurveDb(l: ListeningLevel): Double? {
        if (l.maxIndex <= 0) return null
        val share = l.index.toDouble() / l.maxIndex
        if (share <= 0.0) return CURVE.first().second
        for (i in 1 until CURVE.size) {
            val (x0, y0) = CURVE[i - 1]
            val (x1, y1) = CURVE[i]
            if (share <= x1) return y0 + (y1 - y0) * ((share - x0) / (x1 - x0)).coerceAtLeast(0.0)
        }
        return 0.0
    }

    /**
     * Whether the effect may see full-scale audio on [output]. With Bluetooth absolute volume, and
     * some USB devices, Android leaves the volume to the headset, so the mix reaches the effect at
     * full scale and a boost needs its own headroom. Elsewhere the volume is applied before the
     * effect, and listening below the tuning volume leaves more headroom than the boost uses.
     */
    fun needsHeadroom(output: OutputDevice): Boolean = when (output.kind) {
        OutputDevice.Kind.BLUETOOTH, OutputDevice.Kind.USB, OutputDevice.Kind.HEARING_AID -> true
        else -> false
    }
}
