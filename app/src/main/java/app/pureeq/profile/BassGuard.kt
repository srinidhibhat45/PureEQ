package app.pureeq.profile

import app.pureeq.dsp.Curves
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.dsp.FreqGrid
import app.pureeq.dsp.Response
import app.pureeq.hearing.HeadphoneType
import kotlin.math.abs
import kotlin.math.round

/**
 * Keeps the bass of the whole EQ (the headphone correction with its target's bass, ear tuning and
 * taste together) within what it's worth:
 *
 * - Over-ears: below [CORNER_HZ] at most [CORNER_ALLOWANCE_DB] above the gain at 50 Hz, unless
 *   full sub-bass is on. At normal levels 20-40 Hz sits near threshold and Harman's over-ear
 *   preference model starts at 50 Hz, while a boost there costs driver excursion and as much
 *   volume (an HD 650 asks +10.5 dB at 20 Hz to reach the target). Sealed in-ears keep it: their
 *   couplers and the in-ear model reach 20 Hz.
 * - Everyone: below [CEILING_BELOW_HZ] at most [ceilingDb] above 1 kHz, the target's bass plus
 *   what Harman's bass-loving listeners add.
 *
 * The cuts are low shelves shared by both ears, so the ears' timing stays matched, each sized by
 * direct search to meet its limit with the least cut above it.
 */
object BassGuard {
    const val CORNER_HZ = 50.0
    const val CORNER_ALLOWANCE_DB = 2.0
    const val CEILING_BELOW_HZ = 200.0

    fun ceilingDb(type: HeadphoneType): Double = if (type == HeadphoneType.IN_EAR) 15.0 else 12.0

    private const val Q = 0.7
    private const val MAX_CUT_DB = 24.0
    private const val TOLERANCE_DB = 0.1
    private val CORNER_SHELVES = doubleArrayOf(25.0, 30.0, 35.0, 40.0, 45.0)
    private val CEILING_SHELVES = doubleArrayOf(60.0, 80.0, 100.0, 130.0)

    /** Where the limits are checked: the grid up to 500 Hz (for the cuts' spill), and 1 kHz. */
    private val idx: IntArray = FreqGrid.hz.indices.filter { FreqGrid.hz[it] <= 500.0 }.toIntArray()
    private val hz = DoubleArray(idx.size) { FreqGrid.hz[idx[it]] }
    private val at50 = hz.indices.minBy { abs(hz[it] - CORNER_HZ) }

    /**
     * Cuts for [curves] (each ear's total response in dB on [FreqGrid], before preamp); empty when
     * they already keep within the limits.
     */
    fun filters(curves: List<DoubleArray>, type: HeadphoneType, fullSubBass: Boolean): List<FilterSpec> {
        val low = curves.map { c -> DoubleArray(hz.size) { c[idx[it]] } }
        val ref = curves.map { Curves.sample(it, 1000.0) }
        val guard = mutableListOf<FilterSpec>()
        if (type != HeadphoneType.IN_EAR && !fullSubBass) {
            fit(low, guard, CORNER_SHELVES, spillFrom = CORNER_HZ, spillTo = CEILING_BELOW_HZ) { c, _ ->
                var worst = 0.0
                for (i in hz.indices) if (hz[i] < CORNER_HZ) worst = maxOf(worst, c[i] - c[at50] - CORNER_ALLOWANCE_DB)
                worst
            }?.let { guard += it }
        }
        val ceiling = ceilingDb(type)
        fit(low, guard, CEILING_SHELVES, spillFrom = CEILING_BELOW_HZ, spillTo = 500.0) { c, e ->
            var worst = 0.0
            for (i in hz.indices) if (hz[i] < CEILING_BELOW_HZ) worst = maxOf(worst, c[i] - ref[e] - ceiling)
            worst
        }?.let { guard += it }
        return guard
    }

    /**
     * The shallowest low-shelf cut (over [shelves]) that brings [excess] within tolerance for every
     * ear, on top of [guard] so far; of those, the one that spills least into [spillFrom]..[spillTo].
     * Null when nothing needs cutting.
     */
    private fun fit(
        low: List<DoubleArray>,
        guard: List<FilterSpec>,
        shelves: DoubleArray,
        spillFrom: Double,
        spillTo: Double,
        excess: (DoubleArray, Int) -> Double,
    ): FilterSpec? {
        val base = Response.of(guard, hz)
        fun worst(cut: DoubleArray): Double = low.indices.maxOf { e -> excess(DoubleArray(hz.size) { low[e][it] + base[it] + cut[it] }, e) }
        if (worst(DoubleArray(hz.size)) <= TOLERANCE_DB) return null
        var best: FilterSpec? = null
        var bestSpill = Double.POSITIVE_INFINITY
        for (f in shelves) {
            fun cut(g: Double) = Response.of(listOf(FilterSpec(FilterType.LOW_SHELF, f, -g, Q)), hz)
            if (worst(cut(MAX_CUT_DB)) > TOLERANCE_DB) continue
            var lo = 0.0
            var hi = MAX_CUT_DB
            repeat(20) {
                val mid = (lo + hi) / 2
                if (worst(cut(mid)) <= TOLERANCE_DB) hi = mid else lo = mid
            }
            val g = round(hi * 10) / 10 + 0.1
            val c = cut(g)
            var spill = 0.0
            var n = 0
            for (i in hz.indices) {
                if (hz[i] in spillFrom..spillTo) {
                    spill += abs(c[i])
                    n++
                }
            }
            spill /= n
            if (spill < bestSpill) {
                bestSpill = spill
                best = FilterSpec(FilterType.LOW_SHELF, f, -g, Q)
            }
        }
        // Nothing within reach (an extreme curve): the deepest cut at the lowest corner still helps.
        return best ?: FilterSpec(FilterType.LOW_SHELF, shelves.first(), -MAX_CUT_DB, Q)
    }
}
