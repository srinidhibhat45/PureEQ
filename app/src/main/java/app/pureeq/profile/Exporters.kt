package app.pureeq.profile

import app.pureeq.dsp.Curves
import app.pureeq.dsp.Ear
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.dsp.FreqGrid
import app.pureeq.dsp.ImpulseResponse
import app.pureeq.dsp.Loudness
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.round
import kotlin.math.roundToInt

/** Fixed-band graphic equalizers a profile can be typed into by hand. */
enum class BandSet(val label: String, val where: String, val freqs: DoubleArray, val minDb: Double, val maxDb: Double, val stepDb: Double) {
    TEN("10-band", "Poweramp graphic mode, most player and phone EQs", doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0), -12.0, 12.0, 0.5),
    SAMSUNG("Samsung 9-band", "Galaxy Settings > Sound quality and effects > Equalizer > Custom", doubleArrayOf(63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0), -10.0, 10.0, 1.0),
    ANDROID5("5-band", "Android's built-in equalizer, Spotify on Android, many phone EQs", doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0), -15.0, 15.0, 1.0),
    JAMES15("JamesDSP 15-band", "JamesDSP / RootlessJamesDSP multimodal equalizer", doubleArrayOf(25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0), -12.0, 12.0, 0.1),
    THIRD_OCTAVE("31-band", "1/3-octave graphic EQs", doubleArrayOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0), -12.0, 12.0, 0.5),
}

data class BandValue(val freq: Double, val gainDb: Double, val clipped: Boolean)

/** One filter of [Exporters.filterTable]: its number and type, and its values written as the exports write them. */
data class FilterRow(val number: Int, val type: FilterType, val freq: String, val gain: String, val q: String)

/** The ear that needs more level, and how much more in dB ([db], written as the exports write it). */
data class BalanceAdvice(val ear: Ear, val db: String)

/**
 * Text formats for other apps. The parametric and GraphicEQ formats follow AutoEq's conventions
 * exactly (Fc to 1 Hz, gain to 0.1 dB, Q to 0.01; GraphicEQ peaks at -0.2 dB), because Wavelet,
 * Poweramp Equalizer, JamesDSP and Equalizer APO already import AutoEq files.
 */
object Exporters {

    /** Wavelet only accepts GraphicEQ files on this exact 127-point grid (AutoEq's, step 1.0563 from 20 Hz). */
    val WAVELET_FREQS = intArrayOf(
        20, 21, 22, 23, 24, 26, 27, 29, 30, 32, 34, 36, 38, 40, 43, 45,
        48, 50, 53, 56, 59, 63, 66, 70, 74, 78, 83, 87, 92, 97, 103, 109,
        115, 121, 128, 136, 143, 151, 160, 169, 178, 188, 199, 210, 222, 235, 248, 262,
        277, 292, 309, 326, 345, 364, 385, 406, 429, 453, 479, 506, 534, 565, 596, 630,
        665, 703, 743, 784, 829, 875, 924, 977, 1032, 1090, 1151, 1216, 1284, 1357, 1433, 1514,
        1599, 1689, 1784, 1885, 1991, 2103, 2221, 2347, 2479, 2618, 2766, 2921, 3086, 3260, 3443, 3637,
        3842, 4058, 4287, 4528, 4783, 5052, 5337, 5637, 5955, 6290, 6644, 7018, 7414, 7831, 8272, 8738,
        9230, 9749, 10298, 10878, 11490, 12137, 12821, 13543, 14305, 15110, 15961, 16860, 17809, 18812, 19871,
    )

    private fun fmt(v: Double, decimals: Int): String {
        val r = String.format(Locale.US, "%.${decimals}f", v)
        return if (r.startsWith("-") && r.trim('-', '0', '.').isEmpty()) r.substring(1) else r
    }

    private fun filterLine(i: Int, f: FilterSpec) =
        "Filter ${i + 1}: ON ${f.type.apoCode} Fc ${f.freq.roundToInt()} Hz Gain ${fmt(f.gainDb, 1)} dB Q ${fmt(f.q, 2)}"

    /** Equalizer APO ParametricEQ.txt; Poweramp Equalizer imports this file directly. */
    fun parametric(filters: List<FilterSpec>, preampDb: Double): String = buildString {
        appendLine("Preamp: ${fmt(preampDb, 1)} dB")
        filters.forEachIndexed { i, f -> appendLine(filterLine(i, f)) }
    }

    /** Equalizer APO / Peace config with separate left and right filters. */
    fun parametricPerEar(eq: ProfileEq): String = buildString {
        appendLine("# PureEQ: ${eq.profile.name} (per ear)")
        appendLine("Preamp: ${fmt(eq.preampDb, 1)} dB")
        appendLine("Channel: L")
        if (eq.leftTrimDb != 0.0) appendLine("Preamp: ${fmt(eq.leftTrimDb, 1)} dB")
        eq.leftFilters.forEachIndexed { i, f -> appendLine(filterLine(i, f)) }
        appendLine("Channel: R")
        if (eq.rightTrimDb != 0.0) appendLine("Preamp: ${fmt(eq.rightTrimDb, 1)} dB")
        eq.rightFilters.forEachIndexed { i, f -> appendLine(filterLine(i + eq.leftFilters.size, f)) }
        appendLine("Channel: all")
    }

    /**
     * GraphicEQ line (Wavelet, JamesDSP "arbitrary response", Equalizer APO). Pass the curve with its
     * preamp already applied ([linkedCurveWithPreamp]), so the export plays at the same level as
     * PureEQ's own system EQ and no app needs a separate preamp.
     */
    fun graphicEq(curveDb: DoubleArray, freqs: IntArray = WAVELET_FREQS): String =
        "GraphicEQ: " + freqs.joinToString("; ") { "$it ${fmt(Curves.sample(curveDb, it.toDouble()), 1)}" }

    /**
     * Gains for a fixed-band graphic EQ. The curve is smoothed to the band spacing so every slider
     * represents its whole band rather than a single point. Values are centred so perceived loudness
     * stays about the same (most phone EQs have no preamp), rounded to the app's step and limited to
     * its range.
     */
    fun bands(curveDb: DoubleArray, set: BandSet): List<BandValue> {
        val f = set.freqs
        val spacing = if (f.size > 1) log2(f.last() / f.first()) / (f.size - 1) else 1.0
        val smooth = Curves.smooth(curveDb, spacing)
        val raw = f.map { Curves.sample(smooth, it) }
        val centre = Loudness.offsetDb(smooth)
        return f.indices.map { i ->
            val v = raw[i] - centre
            val stepped = round(v / set.stepDb) * set.stepDb
            val clamped = stepped.coerceIn(set.minDb, set.maxDb)
            BandValue(f[i], clamped, abs(clamped - stepped) > 1e-9)
        }
    }

    fun bandsText(profileName: String, values: List<BandValue>, set: BandSet): String = buildString {
        appendLine("PureEQ: $profileName (${set.label})")
        values.forEach { appendLine("${freqLabel(it.freq)}: ${signed(it.gainDb)} dB") }
    }

    fun freqLabel(f: Double): String = when {
        f >= 1000 -> {
            val k = f / 1000
            if (abs(k - round(k)) < 1e-9) "${k.roundToInt()} kHz" else "${fmt(k, 1)} kHz"
        }
        abs(f - round(f)) < 1e-9 -> "${f.roundToInt()} Hz"
        else -> "${fmt(f, 1)} Hz"
    }

    fun signed(v: Double, decimals: Int = 1): String {
        val s = fmt(v, decimals)
        return if (v > 0 && !s.startsWith("+") && s.trim('0', '.').isNotEmpty()) "+$s" else s
    }

    /** Filter list for apps where values are typed in (Neutron, UAPP, Poweramp parametric); the Export screen words each row. */
    fun filterTable(filters: List<FilterSpec>): List<FilterRow> = filters.mapIndexed { i, f ->
        FilterRow(i + 1, f.type, freqLabel(f.freq), signed(f.gainDb), fmt(f.q, 2))
    }

    /**
     * When the ears differ, the side to move Android's own balance slider toward (the Export screen
     * says how), or null if it isn't needed.
     */
    fun balanceAdvice(eq: ProfileEq): BalanceAdvice? {
        val d = eq.earDifferenceDb
        if (abs(d) < 1.5) return null
        return BalanceAdvice(if (d > 0) Ear.LEFT else Ear.RIGHT, fmt(abs(d), 1))
    }

    /**
     * Per-ear impulse response for convolution engines (JamesDSP and ViPER4Android convolver,
     * Equalizer APO): the exact curve of each ear, left/right differences, balance and preamp
     * included, at the same level as PureEQ's own system EQ. A stereo 32-bit float WAV.
     */
    fun convolution(eq: ProfileEq, sampleRate: Int): ByteArray = ImpulseResponse.wav(
        ImpulseResponse.of(eq.leftFilters, eq.leftTrimDb + eq.preampDb, sampleRate),
        ImpulseResponse.of(eq.rightFilters, eq.rightTrimDb + eq.preampDb, sampleRate),
        sampleRate,
    )

    fun fileSafe(name: String): String = name.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "PureEQ" }

    /** The curve used for single-curve exports: both ears averaged. */
    fun linkedCurveWithPreamp(eq: ProfileEq): DoubleArray = DoubleArray(FreqGrid.SIZE) { eq.linkedCurve[it] + eq.linkedPreampDb }
}
