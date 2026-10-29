package app.pureeq.profile

import app.pureeq.dsp.Curves
import app.pureeq.dsp.Ear
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.dsp.FreqGrid
import app.pureeq.dsp.ParametricFit
import app.pureeq.dsp.Response
import app.pureeq.dsp.StereoEqSpec
import app.pureeq.hearing.HeadphoneType
import app.pureeq.hearing.HearingTestResult
import app.pureeq.hearing.ReferenceCurve
import app.pureeq.hearing.ThresholdBelief
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign

/**
 * Turns a hearing test into a compensation curve per ear.
 *
 * 1. Deviation: the smooth part of each ear's thresholds relative to the reference (what a
 *    normal-hearing listener needs through Harman-tuned headphones of this type), taken from the
 *    test's Bayesian belief. Narrow resonances and measurement noise stay out; a left/right
 *    difference counts only as far as the answers support it. The mid band (500 Hz-4 kHz) is 0.
 *    Each frequency then gives up [UNCERTAINTY_SHRINK] of its own uncertainty, so what the answers
 *    can't tell from noise isn't corrected (in simulation this halves the coloration a normal
 *    listener gets, at little cost to real corrections).
 * 2. Gain: up to [KNEE_DB] the deviation is corrected by the strength (75 % by default). Small,
 *    smooth deviations are mostly the headphones' own response, which can be corrected nearly
 *    fully. Beyond the knee only [ABOVE_KNEE] of that applies: larger differences are hearing loss,
 *    where full gain sounds harsh (cochlear loss recruits; fitting rules give about 0.3 dB per dB).
 * 3. Range: full between 400 Hz and 8 kHz, fading out by 200 Hz and 16 kHz, where thresholds say
 *    little about music. Boosts are capped ([maxBoost]): +2 dB in the bass, +8 dB in the mids,
 *    +6 dB in the treble. Cuts stop at −6 dB.
 */
object HearingCompensation {
    const val DEFAULT_STRENGTH = 0.75
    const val KNEE_DB = 10.0
    const val ABOVE_KNEE = 0.4
    const val UNCERTAINTY_SHRINK = 0.25
    const val MAX_CUT_DB = -6.0

    class EarCurves(val left: DoubleArray, val right: DoubleArray)

    /** Smooth deviation per ear on [FreqGrid] (dB, positive = heard less well), with the mid band at 0. */
    fun deviations(result: HearingTestResult, type: HeadphoneType): EarCurves {
        val belief = ThresholdBelief.of(result, type)
        val left = Array(FreqGrid.SIZE) { belief.prior.shape(Ear.LEFT, FreqGrid.hz[it], belief) }
        val right = Array(FreqGrid.SIZE) { belief.prior.shape(Ear.RIGHT, FreqGrid.hz[it], belief) }
        var sum = 0.0
        var n = 0
        for (i in FreqGrid.hz.indices) {
            if (FreqGrid.hz[i] in MID_LOW_HZ..MID_HIGH_HZ) {
                sum += (left[i].first + right[i].first) / 2
                n++
            }
        }
        val anchor = sum / n
        fun shrink(p: Pair<Double, Double>): Double {
            val d = p.first - anchor
            return sign(d) * max(abs(d) - UNCERTAINTY_SHRINK * p.second, 0.0)
        }
        return EarCurves(DoubleArray(FreqGrid.SIZE) { shrink(left[it]) }, DoubleArray(FreqGrid.SIZE) { shrink(right[it]) })
    }

    fun curves(result: HearingTestResult, type: HeadphoneType, strength: Double): EarCurves {
        val d = deviations(result, type)
        return EarCurves(gain(d.left, strength), gain(d.right, strength))
    }

    private fun gain(dev: DoubleArray, strength: Double) = DoubleArray(FreqGrid.SIZE) { i ->
        val f = FreqGrid.hz[i]
        val d = dev[i]
        val g = strength * (d.coerceIn(-KNEE_DB, KNEE_DB) + ABOVE_KNEE * sign(d) * max(abs(d) - KNEE_DB, 0.0))
        (taper(f) * g).coerceIn(MAX_CUT_DB, maxBoost(f))
    }

    /** 1 from 400 Hz to 8 kHz, fading over an octave to 0 at 200 Hz and at 16 kHz. */
    fun taper(f: Double): Double = log2(f / 200.0).coerceIn(0.0, 1.0) * log2(16000.0 / f).coerceIn(0.0, 1.0)

    /**
     * Largest boost the hearing correction may add: +2 dB up to 250 Hz (bass is taste's job), +8 dB
     * from 500 Hz to 4 kHz, +6 dB from 8 kHz (sizzle and hiss), changing smoothly in between.
     */
    fun maxBoost(f: Double): Double = when {
        f <= 250.0 -> 2.0
        f < 500.0 -> 2.0 + 6.0 * log2(f / 250.0)
        f <= 4000.0 -> 8.0
        f < 8000.0 -> 8.0 - 2.0 * log2(f / 4000.0)
        else -> 6.0
    }

    /**
     * Each tested band relative to the reference and to the mid band (dB, positive = heard less
     * well), for the result chart. These are the per-band estimates, before smoothing.
     */
    fun chartPoints(result: HearingTestResult, type: HeadphoneType, ear: Ear): List<Pair<Double, Double>> {
        val rel = result.points.map { it.db - ReferenceCurve.at(it.freq, type) }
        val mid = result.points.indices.filter { result.points[it].freq in MID_LOW_HZ..MID_HIGH_HZ }.map { rel[it] }.ifEmpty { rel }.average()
        return result.points.indices.filter { result.points[it].ear == ear }
            .sortedBy { result.points[it].freq }
            .map { result.points[it].freq to rel[it] - mid }
    }

    /** Broad peaks an octave apart for the part both ears share. */
    private val commonSlots = listOf(500.0, 1000.0, 2000.0, 4000.0, 8000.0, 12000.0)
        .map { ParametricFit.Slot(FilterType.PEAK, it, 1.0, -9.0, 10.0, minQ = 0.5, maxQ = 1.6) }

    /** Three gentle peaks for the left/right difference, applied with opposite signs to each ear. */
    private val residualSlots = listOf(700.0, 2000.0, 5500.0)
        .map { ParametricFit.Slot(FilterType.PEAK, it, 0.7, -6.0, 6.0, minQ = 0.4, maxQ = 1.0) }

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

    /**
     * Compensation targets and filters for one result. Both ears share one filter set for what
     * they have in common, so a centred voice stays centred (separate per-ear filters would also
     * shift the timing between the ears). Only a real left/right difference adds a small
     * mirrored set on top.
     */
    class Fitted(result: HearingTestResult, type: HeadphoneType, strength: Double) {
        private val targets = curves(result, type, strength)
        val targetLeft: DoubleArray = targets.left
        val targetRight: DoubleArray = targets.right
        val filtersCommon: List<FilterSpec> = fit(Curves.average(targetLeft, targetRight), commonSlots)
        private val residual: List<FilterSpec> = run {
            val half = DoubleArray(FreqGrid.SIZE) { (targetLeft[it] - targetRight[it]) / 2 }
            if (Curves.max(DoubleArray(half.size) { abs(half[it]) }, 300.0, 12000.0) < MIN_DIFFERENCE_DB) emptyList() else fit(half, residualSlots)
        }
        val filtersLeft: List<FilterSpec> = filtersCommon + residual
        val filtersRight: List<FilterSpec> = filtersCommon + residual.map { it.copy(gainDb = -it.gainDb) }
    }

    private data class Key(val result: HearingTestResult, val type: HeadphoneType, val strength: Double)

    private val cache = object : LinkedHashMap<Key, Fitted>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Fitted>?) = size > 8
    }

    /** Fitting takes tens of milliseconds, so slider moves that don't touch hearing reuse the last fit. */
    @Synchronized
    fun fitted(result: HearingTestResult, type: HeadphoneType, strength: Double): Fitted =
        cache.getOrPut(Key(result, type, strength)) { Fitted(result, type, strength) }

    private const val MID_LOW_HZ = 500.0
    private const val MID_HIGH_HZ = 4000.0

    /** A left/right difference smaller than this everywhere is left out: it's below what anyone hears. */
    private const val MIN_DIFFERENCE_DB = 0.5
}

/**
 * Everything derived from a [Profile]: per-ear filters and curves, a preamp that keeps music
 * below 0 dBFS, and a stereo-linked version for apps that can't EQ each ear separately.
 */
class ProfileEq(val profile: Profile) {

    /** The headphone type the hearing result is measured against (older results: a guess from the device name). */
    val headphones: HeadphoneType = profile.hearing?.headphones ?: HeadphoneType.guess(profile.deviceName)

    /** Hearing-compensation target per ear (dB on [FreqGrid]); zero when there is no hearing test. */
    val hearingTargetLeft: DoubleArray
    val hearingTargetRight: DoubleArray
    val hearingFiltersLeft: List<FilterSpec>
    val hearingFiltersRight: List<FilterSpec>
    val tasteFilters: List<FilterSpec> = profile.taste.filters()

    private val fitted: HearingCompensation.Fitted? =
        profile.hearing?.takeIf { profile.hearingStrength > 0.0 }?.let { HearingCompensation.fitted(it, headphones, profile.hearingStrength) }

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

    val preampDb: Double = preampFor(leftCurve, rightCurve)

    /** Stereo-linked filters: the compensation both ears share, plus taste. */
    val linkedFilters: List<FilterSpec> by lazy { (fitted?.filtersCommon ?: emptyList()) + tasteFilters }
    val linkedCurve: DoubleArray by lazy { Response.of(linkedFilters, FreqGrid.hz) }
    val linkedPreampDb: Double by lazy { preampFor(linkedCurve) }

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
        /** Bands (Hz) and each band's typical share of a mastered mix's peak when it dominates that peak. */
        private val BAND_EDGES = doubleArrayOf(20.0, 200.0, 700.0, 2000.0, 5000.0, 10000.0, 20000.0)
        private val BAND_PEAK = doubleArrayOf(0.9, 0.6, 0.45, 0.35, 0.25, 0.15)

        /** Extra margin, as AutoEq uses. */
        const val HEADROOM_DB = 0.2

        /**
         * Preamp for music through [curves], rounded down to 0.1 dB. A mix's peaks are dominated by
         * one band at a time, mostly the bass: at such a peak that band supplies its typical share
         * ([BAND_PEAK]) and the other bands the rest. Peak growth is the worst of these peaks with
         * each band at its largest gain, and never more than the largest boost. So a +6 dB treble
         * lift costs about 2 dB of level instead of 6, and bass plus treble boosts cost about the
         * bass boost alone, which keeps the EQ much closer to bypass loudness without clipping.
         */
        fun preampFor(vararg curves: DoubleArray): Double {
            var need = 0.0
            for (c in curves) {
                val maxBoost = Curves.max(c)
                if (maxBoost <= 0.0) continue
                val gain = DoubleArray(BAND_PEAK.size) { 10.0.pow(Curves.max(c, BAND_EDGES[it], BAND_EDGES[it + 1]) / 20) }
                var growth = 0.0
                for (b in gain.indices) {
                    var rest = 0.0
                    var weight = 0.0
                    for (o in gain.indices) {
                        if (o == b) continue
                        rest += BAND_PEAK[o] * gain[o]
                        weight += BAND_PEAK[o]
                    }
                    growth = max(growth, BAND_PEAK[b] * gain[b] + (1 - BAND_PEAK[b]) * rest / weight)
                }
                need = max(need, min(maxBoost, 20 * log10(growth)))
            }
            // The epsilon keeps floating-point error from adding a tenth (3.2 must not round to 3.3).
            return if (need <= 0.0) 0.0 else -(ceil((need + HEADROOM_DB) * 10 - 1e-9) / 10)
        }
    }
}
