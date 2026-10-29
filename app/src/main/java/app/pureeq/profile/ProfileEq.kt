package app.pureeq.profile

import app.pureeq.dsp.Curves
import app.pureeq.dsp.Ear
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.dsp.FreqGrid
import app.pureeq.dsp.ParametricFit
import app.pureeq.dsp.Response
import app.pureeq.dsp.StereoEqSpec
import app.pureeq.hearing.HearingTestResult
import app.pureeq.hearing.ReferenceThreshold
import app.pureeq.hearing.ThresholdFlag
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Turns hearing thresholds into a compensation curve per ear.
 *
 * 1. Relative level = measured threshold minus the normal-hearing curve (ISO 226), i.e. how much
 *    less well this band is heard through these headphones.
 * 2. The 25th percentile of both ears is the baseline ("your good bands"). Only the shape matters;
 *    the preamp absorbs the offset.
 * 3. Gain = strength × (relative level − baseline). 50 % is the classic half-gain rule: it
 *    compensates for audibility without over-correcting at normal listening levels.
 * 4. Monotone interpolation between tested bands, a fade-out beyond the tested range, limits
 *    (+12 dB, +6 dB in the deep bass where earbud fit and room noise skew thresholds, −6 dB cut)
 *    and 1/3-octave smoothing.
 */
object HearingCompensation {
    const val DEFAULT_STRENGTH = 0.5
    const val MAX_BOOST_DB = 12.0
    const val MAX_BASS_BOOST_DB = 6.0
    const val MAX_CUT_DB = -6.0

    class EarCurves(val left: DoubleArray, val right: DoubleArray)

    /** Relative hearing level (dB) per tested frequency for [ear]; higher means heard less well. */
    fun relativeLevels(result: HearingTestResult, ear: Ear): List<Pair<Double, Double>> =
        result.pointsFor(ear).map { it.freq to (it.db - ReferenceThreshold.at(it.freq)) }

    /** Baseline subtracted from relative levels: the 25th percentile over both ears. */
    fun baseline(result: HearingTestResult): Double {
        val all = result.points.filter { it.flag == ThresholdFlag.OK }
            .map { it.db - ReferenceThreshold.at(it.freq) }
            .ifEmpty { result.points.map { it.db - ReferenceThreshold.at(it.freq) } }
            .sorted()
        if (all.isEmpty()) return 0.0
        val pos = 0.25 * (all.size - 1)
        val i = floor(pos).toInt()
        val t = pos - i
        return if (i + 1 < all.size) all[i] * (1 - t) + all[i + 1] * t else all[i]
    }

    fun curves(result: HearingTestResult, strength: Double): EarCurves {
        val base = baseline(result)
        return EarCurves(curve(result, Ear.LEFT, base, strength), curve(result, Ear.RIGHT, base, strength))
    }

    private fun curve(result: HearingTestResult, ear: Ear, base: Double, strength: Double): DoubleArray {
        val rel = relativeLevels(result, ear)
        if (rel.isEmpty()) return DoubleArray(FreqGrid.SIZE)
        val xs = DoubleArray(rel.size) { log2(rel[it].first) }
        val ys = DoubleArray(rel.size) { (rel[it].second - base) * strength }
        val lo = xs.first()
        val hi = xs.last()
        val top = log2(FreqGrid.MAX_HZ)
        val raw = DoubleArray(FreqGrid.SIZE) { i ->
            val f = FreqGrid.hz[i]
            val x = log2(f)
            var v = Curves.pchip(xs, ys, x)
            // Beyond the tested range: hold half an octave, then fade to 0.
            if (x < lo - 0.5) v *= (1 - (lo - 0.5 - x) / 1.5).coerceIn(0.0, 1.0)
            val fadeStart = hi + 0.5
            if (x > fadeStart) v *= if (fadeStart >= top) 1.0 else ((top - x) / (top - fadeStart)).coerceIn(0.0, 1.0)
            v.coerceIn(MAX_CUT_DB, boostLimit(f))
        }
        return Curves.smooth(raw, 1.0 / 3.0)
    }

    /** +6 dB below 150 Hz, rising to +12 dB at 300 Hz and above. */
    fun boostLimit(f: Double): Double = when {
        f <= 150.0 -> MAX_BASS_BOOST_DB
        f >= 300.0 -> MAX_BOOST_DB
        else -> MAX_BASS_BOOST_DB + (MAX_BOOST_DB - MAX_BASS_BOOST_DB) * log2(f / 150.0)
    }

    /**
     * Fixed-shape filters to fit a compensation curve: a peak at every tested frequency (Q from
     * the spacing to its neighbours) plus shelves just outside the tested range.
     */
    fun slots(freqs: List<Double>): List<ParametricFit.Slot> {
        val f = freqs.distinct().sorted()
        if (f.isEmpty()) return emptyList()
        val slots = mutableListOf<ParametricFit.Slot>()
        slots += ParametricFit.Slot(FilterType.LOW_SHELF, f.first() / 2, 0.7, -6.0, 6.0)
        for (i in f.indices) {
            val left = if (i > 0) log2(f[i] / f[i - 1]) else if (f.size > 1) log2(f[1] / f[0]) else 1.0
            val right = if (i < f.size - 1) log2(f[i + 1] / f[i]) else left
            // Each peak spans about 1.6x the spacing to its neighbours so neighbouring peaks sum without ripple.
            val bw = 1.6 * (left + right) / 2
            val q = (sqrt(2.0.pow(bw)) / (2.0.pow(bw) - 1)).coerceIn(0.6, 2.0)
            slots += ParametricFit.Slot(FilterType.PEAK, f[i], q, -9.0, 14.0)
        }
        slots += ParametricFit.Slot(FilterType.HIGH_SHELF, minOf(f.last() * 1.5, 16000.0), 0.7, -6.0, 6.0)
        return slots
    }

    /**
     * Fits [target] with [slots]: gains first, then frequency, Q and gain together. Filters that do
     * almost nothing are dropped, and values are rounded for export (0.1 dB, 1 Hz, 0.01 Q).
     */
    fun fit(target: DoubleArray, slots: List<ParametricFit.Slot>): List<FilterSpec> {
        if (slots.isEmpty() || target.all { abs(it) < 0.05 }) return emptyList()
        var kept = slots
        var filters = ParametricFit.fit(target, kept)
        val significant = filters.indices.filter { abs(filters[it].gainDb) >= 0.25 }
        if (significant.size < filters.size) {
            kept = significant.map { kept[it] }
            filters = ParametricFit.fit(target, kept)
        }
        if (filters.isEmpty()) return emptyList()
        filters = ParametricFit.refine(target, filters, kept)
        return filters.map { roundFilter(it) }.filter { abs(it.gainDb) >= 0.1 }
    }

    fun roundFilter(f: FilterSpec) = FilterSpec(f.type, round(f.freq), round(f.gainDb * 10) / 10, round(f.q * 100) / 100)

    /** Compensation targets and fitted filters for one test result at one strength. */
    class Fitted(result: HearingTestResult, strength: Double) {
        private val slots = slots(result.points.map { it.freq })
        private val targets = curves(result, strength)
        val targetLeft: DoubleArray = targets.left
        val targetRight: DoubleArray = targets.right
        val filtersLeft: List<FilterSpec> = fit(targetLeft, slots)
        val filtersRight: List<FilterSpec> = fit(targetRight, slots)
        val filtersLinked: List<FilterSpec> by lazy { fit(Curves.average(targetLeft, targetRight), slots) }
    }

    private val cache = object : LinkedHashMap<Pair<HearingTestResult, Double>, Fitted>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<HearingTestResult, Double>, Fitted>?) = size > 8
    }

    /** Fitting takes tens of milliseconds, so slider moves that don't touch hearing reuse the last fit. */
    @Synchronized
    fun fitted(result: HearingTestResult, strength: Double): Fitted =
        cache.getOrPut(result to strength) { Fitted(result, strength) }
}

/**
 * Everything derived from a [Profile]: per-ear filters and curves, a preamp that keeps peaks below
 * 0 dBFS, and a stereo-linked version for apps that can't EQ each ear separately.
 */
class ProfileEq(val profile: Profile) {

    /** Hearing-compensation target per ear (dB on [FreqGrid]); zero when there is no hearing test. */
    val hearingTargetLeft: DoubleArray
    val hearingTargetRight: DoubleArray
    val hearingFiltersLeft: List<FilterSpec>
    val hearingFiltersRight: List<FilterSpec>
    val tasteFilters: List<FilterSpec> = profile.taste.filters()

    private val fitted: HearingCompensation.Fitted? =
        profile.hearing?.takeIf { profile.hearingStrength > 0.0 }?.let { HearingCompensation.fitted(it, profile.hearingStrength) }

    init {
        if (fitted != null) {
            hearingTargetLeft = fitted.targetLeft
            hearingTargetRight = fitted.targetRight
            hearingFiltersLeft = fitted.filtersLeft
            hearingFiltersRight = fitted.filtersRight
        } else {
            hearingTargetLeft = DoubleArray(FreqGrid.SIZE)
            hearingTargetRight = DoubleArray(FreqGrid.SIZE)
            hearingFiltersLeft = emptyList()
            hearingFiltersRight = emptyList()
        }
    }

    val leftFilters: List<FilterSpec> = hearingFiltersLeft + tasteFilters
    val rightFilters: List<FilterSpec> = hearingFiltersRight + tasteFilters

    /** Manual balance only ever turns one side down, so it can't add clipping. */
    val leftTrimDb: Double = if (profile.balanceDb > 0) -profile.balanceDb else 0.0
    val rightTrimDb: Double = if (profile.balanceDb < 0) profile.balanceDb else 0.0

    /** Responses before preamp, including the balance trim. */
    val leftCurve: DoubleArray = Response.of(leftFilters, FreqGrid.hz, leftTrimDb)
    val rightCurve: DoubleArray = Response.of(rightFilters, FreqGrid.hz, rightTrimDb)

    /** Negative of the highest boost, rounded down to 0.1 dB: no band can exceed 0 dBFS. */
    val preampDb: Double = preampFor(maxOf(Curves.max(leftCurve), Curves.max(rightCurve)))

    /** Stereo-linked filters: compensation fitted to the average of both ears, plus taste. */
    val linkedFilters: List<FilterSpec> by lazy { (fitted?.filtersLinked ?: emptyList()) + tasteFilters }
    val linkedCurve: DoubleArray by lazy { Response.of(linkedFilters, FreqGrid.hz) }
    val linkedPreampDb: Double by lazy { preampFor(Curves.max(linkedCurve)) }

    /**
     * Average left-minus-right difference of the compensation (500 Hz-4 kHz), for apps that only
     * offer a balance slider. Positive: the left ear needs more gain.
     */
    val earDifferenceDb: Double by lazy {
        var s = 0.0
        var n = 0
        for (i in FreqGrid.hz.indices) {
            if (FreqGrid.hz[i] in 500.0..4000.0) {
                s += hearingTargetLeft[i] - hearingTargetRight[i]
                n++
            }
        }
        if (n == 0) 0.0 else s / n
    }

    fun curve(ear: Ear): DoubleArray = when (ear) {
        Ear.LEFT -> leftCurve
        Ear.RIGHT -> rightCurve
        Ear.BOTH -> Curves.average(leftCurve, rightCurve)
    }

    /** What the audio engine and the system EQ play: per-ear filters, preamp folded into the gains. */
    fun stereoSpec(): StereoEqSpec =
        StereoEqSpec(leftFilters, rightFilters, preampDb + leftTrimDb, preampDb + rightTrimDb)

    companion object {
        fun preampFor(maxBoost: Double): Double = if (maxBoost <= 0.0) 0.0 else -(ceil(maxBoost * 10) / 10)
    }
}
