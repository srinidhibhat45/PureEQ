package app.pureeq.headphones

import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.dsp.FreqGrid
import app.pureeq.dsp.ParametricFit
import app.pureeq.dsp.Response
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The target each rig's measurements are corrected to, without its bass: the Harman target as
 * measured on that rig (AutoEq's compatible targets in dbtools/create_webapp_data.py), and
 * [bassDb] the target's own bass shelf (105 Hz, Q 0.7), added back as a filter. For in-ear 2019
 * and over-ear 2018 that shelf reproduces the full Harman curve within 0.1 dB. AutoEq has no
 * Harman target for the Brüel & Kjær 5128 and 4620, so those use the targets AutoEq recommends.
 */
enum class AutoEqTarget(val label: String, val file: String, val bassDb: Double) {
    HARMAN_IN_EAR_2019("Harman in-ear 2019", "Harman in-ear 2019 without bass", 9.0),
    HARMAN_OVER_EAR_2018("Harman over-ear 2018", "Harman over-ear 2018 without bass", 6.0),
    HMS_HARMAN_IN_EAR_2019("Harman in-ear 2019", "HMS II.3 Harman in-ear 2019 without bass", 9.0),
    HMS_HARMAN_OVER_EAR_2018("Harman over-ear 2018", "HMS II.3 Harman over-ear 2018 without bass", 6.0),
    EARS_HARMAN_OVER_EAR_2018("Harman over-ear 2018", "crinacle EARS + 711 Harman over-ear 2018 without bass", 6.0),
    JM1_IN_EAR_5128("JM-1 with Harman filters", "JM-1 with Harman treble filter", 6.5),
    LMG_OVER_EAR_5128("LMG 5128 0.6", "LMG 5128 0.6 without bass", 6.0),
    ;

    val csvPath: String get() = "targets/$file.csv"

    companion object {
        fun of(e: AutoEqEntry): AutoEqTarget {
            val rig = e.rig.orEmpty()
            // HypetheSonics measures over-ears and earbuds on a 5128 without naming the rig.
            val bk = "5128" in rig || "4620" in rig || (e.source == "HypetheSonics" && rig.isEmpty())
            return when {
                bk -> if (e.form == "in-ear") JM1_IN_EAR_5128 else LMG_OVER_EAR_5128
                rig == "HMS II.3" || e.source == "Innerfidelity" || e.source == "Headphone.com Legacy" ->
                    if (e.form == "over-ear") HMS_HARMAN_OVER_EAR_2018 else HMS_HARMAN_IN_EAR_2019
                rig == "EARS + 711" -> EARS_HARMAN_OVER_EAR_2018
                e.form == "over-ear" -> HARMAN_OVER_EAR_2018
                else -> HARMAN_IN_EAR_2019
            }
        }

        /** crinacle's results come without the measurement CSV, only AutoEq's finished corrections. */
        fun hasMeasurement(e: AutoEqEntry) = hasMeasurement(e.source)

        fun hasMeasurement(source: String) = source != "crinacle"
    }
}

/** AutoEq's CSV files: a header ("frequency,raw,...") and one row per frequency. */
object AutoEqCsv {
    class Curve(val freqs: DoubleArray, val db: DoubleArray)

    /** The [column] against frequency, sorted, with empty or NaN rows dropped. Throws when it isn't a usable curve. */
    fun parse(text: String, column: String = "raw"): Curve {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.isNotEmpty()) { "empty file" }
        val head = lines[0].split(',').map { it.trim().lowercase() }
        val fi = head.indexOf("frequency")
        val ci = head.indexOf(column)
        require(fi >= 0 && ci >= 0) { "no $column column" }
        val rows = ArrayList<Pair<Double, Double>>()
        for (line in lines.drop(1)) {
            val cells = line.split(',')
            val f = cells.getOrNull(fi)?.trim()?.toDoubleOrNull() ?: continue
            val v = cells.getOrNull(ci)?.trim()?.toDoubleOrNull() ?: continue
            if (!f.isFinite() || !v.isFinite() || f <= 0.0) continue
            rows += f to v
        }
        val sorted = rows.sortedBy { it.first }.distinctBy { it.first }
        require(sorted.size >= 50) { "too few points" }
        require(sorted.first().first <= 100.0 && sorted.last().first >= 10000.0) { "doesn't cover 100 Hz to 10 kHz" }
        require(sorted.all { abs(it.second) <= 150.0 }) { "values out of range" }
        return Curve(DoubleArray(sorted.size) { sorted[it].first }, DoubleArray(sorted.size) { sorted[it].second })
    }
}

/**
 * A model's correction computed from its measurement the way AutoEq computes its results:
 * measurement and target on AutoEq's grid, both 0 dB at 1 kHz; error = measurement − target
 * without bass, shifted so its mean from 100 Hz to 10 kHz is 0 (so one narrow feature at 1 kHz
 * can't bias it); then [AutoEqEqualizer.equalize], +6 dB cap included. Above 4-8 kHz, where a
 * fixture's response says little about any one ear (people differ by 5-30 dB there), half of it
 * applies and boosts stop at +3 dB ([trebleTrust]). The target's own bass is added back as an
 * exact shelf, so sealed in-ears get the full Harman bass; how far the whole EQ's bass may go is
 * [app.pureeq.profile.BassGuard]'s job, after taste and ear tuning are added.
 */
object MeasuredCorrection {

    fun model(entry: AutoEqEntry, target: AutoEqTarget, raw: AutoEqCsv.Curve, targetCurve: AutoEqCsv.Curve): HeadphoneModel {
        val g = AutoEqEqualizer.GRID
        val eq = trebleTrust(g, equalization(raw, targetCurve))
        val curve = FreqGrid.hz.map { f -> round(onGrid(g, eq, f) * 100) / 100 }
        return HeadphoneModel(
            name = entry.name,
            source = entry.source,
            rig = entry.rig,
            type = entry.type,
            // The target's bass is an exact shelf of its own, so the fit spends its filters on the correction.
            filters = listOf(bassShelf(target.bassDb)) + CorrectionFit.filters(curve.toDoubleArray()),
            path = entry.path,
            target = target.label,
            curveDb = curve,
            bassDb = target.bassDb,
        )
    }

    /** The correction with the target's bass shelf: what the filters reproduce. */
    fun withBass(curveDb: List<Double>, bassDb: Double): DoubleArray {
        val shelf = Response.of(listOf(bassShelf(bassDb)), FreqGrid.hz)
        return DoubleArray(FreqGrid.SIZE) { curveDb[it] + shelf[it] }
    }

    fun bassShelf(bassDb: Double) = FilterSpec(FilterType.LOW_SHELF, 105.0, bassDb, 0.7)

    /** Share of a fixture's correction that applies above the 4-8 kHz blend (AutoEq's treble_gain_k). */
    const val TREBLE_TRUST = 0.5

    /**
     * [eq] on [freqs] with the treble trusted less: full up to 4 kHz, blending to [TREBLE_TRUST] by
     * 8 kHz along AutoEq's own log-frequency sigmoid, and boosts capped from +6 dB at 4 kHz down to
     * +3 dB from 6 kHz.
     */
    fun trebleTrust(freqs: DoubleArray, eq: DoubleArray): DoubleArray {
        val k = AutoEqEqualizer.logFSigmoid(freqs, 4000.0, 8000.0)
        return DoubleArray(eq.size) { i ->
            val f = freqs[i]
            val cap = when {
                f <= 4000.0 -> AutoEqEqualizer.MAX_GAIN_DB
                f >= 6000.0 -> 3.0
                else -> AutoEqEqualizer.MAX_GAIN_DB - 3.0 * log2(f / 4000.0) / log2(1.5)
            }
            minOf(eq[i] * (1 - k[i] * (1 - TREBLE_TRUST)), cap)
        }
    }

    /** The equalization curve on [AutoEqEqualizer.GRID] against [target] plus [bass] (dB on that grid), as AutoEq adds its bass boost. */
    fun equalization(raw: AutoEqCsv.Curve, target: AutoEqCsv.Curve, bass: DoubleArray? = null): DoubleArray {
        val g = AutoEqEqualizer.GRID
        val r = centred(g, interpolate(raw, g))
        val t = centred(g, interpolate(target, g))
        val error = DoubleArray(g.size) { r[it] - t[it] - (bass?.get(it) ?: 0.0) }
        var sum = 0.0
        var n = 0
        for (i in g.indices) {
            if (g[i] >= 100.0 && g[i] <= 10000.0) {
                sum += error[i]
                n++
            }
        }
        val mean = sum / n
        for (i in error.indices) error[i] -= mean
        return AutoEqEqualizer.equalize(g, error).curve
    }

    /** Linear in log-frequency, extrapolated from the end segments (AutoEq's first-order spline). */
    fun interpolate(c: AutoEqCsv.Curve, at: DoubleArray): DoubleArray {
        val lx = DoubleArray(c.freqs.size) { log10(c.freqs[it]) }
        var k = 0
        return DoubleArray(at.size) { i ->
            val x = log10(at[i])
            while (k < lx.size - 2 && x > lx[k + 1]) k++
            val t = (x - lx[k]) / (lx[k + 1] - lx[k])
            c.db[k] + t * (c.db[k + 1] - c.db[k])
        }
    }

    /** Shifted to 0 dB at 1 kHz, as AutoEq's center() does. */
    private fun centred(g: DoubleArray, db: DoubleArray): DoubleArray {
        val at1k = onGrid(g, db, 1000.0)
        return DoubleArray(db.size) { db[it] - at1k }
    }

    /** Value of a curve on [grid] at [freq], linear in log-frequency, held beyond the ends. */
    private fun onGrid(grid: DoubleArray, db: DoubleArray, freq: Double): Double {
        if (freq <= grid.first()) return db.first()
        if (freq >= grid.last()) return db.last()
        var k = 0
        while (freq > grid[k + 1]) k++
        val t = log10(freq / grid[k]) / log10(grid[k + 1] / grid[k])
        return db[k] + t * (db[k + 1] - db[k])
    }
}

/**
 * Parametric filters for a correction curve, for the audio engine, the hearing check and exports.
 * Like AutoEq's own parametric results: two shelves at its defaults, then peaks placed one at a
 * time on the largest feature still missing and refined together, at most [MAX_FILTERS] in all
 * (AutoEq uses ten; two more halve the worst error on busy curves). On real corrections this
 * stays within about 0.25 dB RMS and 0.65 dB at worst (30 Hz-16 kHz).
 */
object CorrectionFit {
    const val MAX_FILTERS = 12

    /** A feature smaller than this is left; the fit stops there. */
    private const val GOOD_ENOUGH_DB = 0.35

    /** A light penalty on gain that keeps neighbouring filters from fighting with large opposite gains. */
    private const val RIDGE = 0.01

    private val shelves = listOf(
        ParametricFit.Slot(FilterType.LOW_SHELF, 105.0, 0.7, -12.0, 12.0, minQ = 0.4, maxQ = 0.7),
        ParametricFit.Slot(FilterType.HIGH_SHELF, 10000.0, 0.7, -12.0, 12.0, minQ = 0.4, maxQ = 0.7),
    )

    fun filters(target: DoubleArray, maxFilters: Int = MAX_FILTERS, iterations: Int = 60): List<FilterSpec> {
        if (target.all { abs(it) < 0.05 }) return emptyList()
        var slots = shelves
        var filters = ParametricFit.refine(target, ParametricFit.fit(target, slots), slots)
        while (filters.size < maxFilters) {
            val resp = Response.of(filters, FreqGrid.hz)
            val bump = largestBump(DoubleArray(FreqGrid.SIZE) { target[it] - resp[it] }) ?: break
            // Shelves stay within half an octave of AutoEq's; each peak may keep moving half an octave from where it is now.
            slots = filters.mapIndexed { j, f -> if (slots[j].type == FilterType.PEAK) slots[j].copy(freq = f.freq, q = f.q.coerceAtMost(maxQ(f.freq)), maxQ = maxQ(f.freq)) else slots[j] } +
                ParametricFit.Slot(FilterType.PEAK, bump.freq, bump.q, -12.0, 12.0, minQ = 0.3, maxQ = maxQ(bump.freq))
            filters = ParametricFit.refine(target, filters + bump, slots, ridge = RIDGE, iterations = iterations)
        }
        val kept = filters.indices.filter { abs(filters[it].gainDb) >= 0.2 }
        if (kept.size < filters.size) filters = ParametricFit.refine(target, kept.map { filters[it] }, kept.map { slots[it] }, ridge = RIDGE, iterations = iterations)
        return filters.map { FilterSpec(it.type, round(it.freq), round(it.gainDb * 10) / 10, round(it.q * 100) / 100) }
            .filter { abs(it.gainDb) >= 0.1 }
    }

    /** Above 4 kHz no narrow peaks: ears differ there by more than such a peak would fix. */
    private fun maxQ(freq: Double) = if (freq > 4000.0) 2.0 else 6.0

    /**
     * A peak filter for the largest residual feature between 25 Hz and 10 kHz, as wide as the
     * feature at half its height. Above 10 kHz only the high shelf follows the trend, as in AutoEq.
     */
    private fun largestBump(r: DoubleArray): FilterSpec? {
        val from = FreqGrid.nearestIndex(25.0)
        val to = FreqGrid.nearestIndex(10000.0)
        var k = from
        for (i in from..to) if (abs(r[i]) > abs(r[k])) k = i
        val height = r[k]
        if (abs(height) < GOOD_ENOUGH_DB) return null
        var lo = k
        while (lo > 0 && r[lo - 1] * height > 0 && abs(r[lo - 1]) > abs(height) / 2) lo--
        var hi = k
        while (hi < r.size - 1 && r[hi + 1] * height > 0 && abs(r[hi + 1]) > abs(height) / 2) hi++
        val bw = ((hi - lo + 1) * FreqGrid.stepOctaves).coerceAtLeast(0.1)
        val q = (sqrt(2.0.pow(bw)) / (2.0.pow(bw) - 1)).coerceIn(0.3, maxQ(FreqGrid.hz[k]))
        return FilterSpec(FilterType.PEAK, FreqGrid.hz[k], height, q)
    }
}
