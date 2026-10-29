package app.pureeq.dsp

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * Fits fixed-shape parametric filters to a target curve: only gains are solved,
 * by damped Gauss-Newton (Levenberg-Marquardt) on the dB error.
 */
object ParametricFit {

    /** A filter whose type, frequency and Q are fixed and whose gain the fit chooses. */
    data class Slot(
        val type: FilterType,
        val freq: Double,
        val q: Double,
        val minGain: Double = -12.0,
        val maxGain: Double = 12.0,
        /** Q range [refine] may use. */
        val minQ: Double = 0.4,
        val maxQ: Double = 4.0,
    )

    /** Grid used during fitting: every 4th point of [FreqGrid] (1/12 octave) is plenty for smooth targets. */
    private val fitIdx = (0 until FreqGrid.SIZE step 4).toList().toIntArray()
    private val fitHz = DoubleArray(fitIdx.size) { FreqGrid.hz[fitIdx[it]] }

    /** Full weight 30 Hz to 16 kHz, tapering to 0.1 at the ends of the audible band. */
    private val fitWeight = DoubleArray(fitHz.size) { i ->
        val f = fitHz[i]
        when {
            f < 30.0 -> 0.1 + 0.9 * (log2(f / 20.0) / log2(30.0 / 20.0))
            f > 16000.0 -> 0.1 + 0.9 * (log2(20000.0 / f) / log2(20000.0 / 16000.0))
            else -> 1.0
        }.coerceIn(0.1, 1.0)
    }

    /**
     * Returns filters for [slots] whose summed response best matches [targetDb] (sampled on [FreqGrid]).
     * [ridge] penalises large gains a little so overlapping filters don't fight each other.
     */
    fun fit(targetDb: DoubleArray, slots: List<Slot>, ridge: Double = 0.02, iterations: Int = 40): List<FilterSpec> {
        val m = slots.size
        if (m == 0) return emptyList()
        val target = DoubleArray(fitIdx.size) { targetDb[fitIdx[it]] }
        val g = DoubleArray(m)
        var mu = 1e-2
        var cost = cost(target, slots, g, ridge)
        repeat(iterations) {
            val jac = Array(m) { DoubleArray(fitHz.size) }
            val model = DoubleArray(fitHz.size)
            for (j in 0 until m) {
                val base = filterDb(slots[j], g[j])
                val up = filterDb(slots[j], g[j] + H)
                val down = filterDb(slots[j], g[j] - H)
                for (i in fitHz.indices) {
                    model[i] += base[i]
                    jac[j][i] = (up[i] - down[i]) / (2 * H)
                }
            }
            // Normal equations (JᵀWJ + ridge·I + mu·diag) Δ = JᵀW r - ridge·g
            val a = Array(m) { DoubleArray(m) }
            val b = DoubleArray(m)
            for (p in 0 until m) {
                for (q in p until m) {
                    var s = 0.0
                    for (i in fitHz.indices) s += fitWeight[i] * jac[p][i] * jac[q][i]
                    a[p][q] = s
                    a[q][p] = s
                }
                var s = 0.0
                for (i in fitHz.indices) s += fitWeight[i] * jac[p][i] * (target[i] - model[i])
                b[p] = s - ridge * g[p]
            }
            for (p in 0 until m) a[p][p] += ridge + mu * (a[p][p] + 1e-9)
            val delta = solve(a, b) ?: return@repeat
            val trial = DoubleArray(m) { (g[it] + delta[it]).coerceIn(slots[it].minGain, slots[it].maxGain) }
            val trialCost = cost(target, slots, trial, ridge)
            if (trialCost < cost) {
                val step = (0 until m).maxOf { abs(trial[it] - g[it]) }
                trial.copyInto(g)
                cost = trialCost
                mu = (mu / 3).coerceAtLeast(1e-6)
                if (step < 1e-4) return slots.mapIndexed { j, s -> FilterSpec(s.type, s.freq, g[j], s.q) }
            } else {
                mu *= 4
            }
        }
        return slots.mapIndexed { j, s -> FilterSpec(s.type, s.freq, g[j], s.q) }
    }

    /**
     * Refines gain, centre frequency (within half an octave of the slot) and Q (within the slot's range) of every
     * filter together, starting from [start] (one filter per slot). This is what lets ten filters
     * follow a steep, real-world hearing curve without ripple.
     */
    fun refine(targetDb: DoubleArray, start: List<FilterSpec>, slots: List<Slot>, ridge: Double = 0.02, iterations: Int = 80): List<FilterSpec> {
        val m = start.size
        if (m == 0) return start
        val np = 3 * m
        val target = DoubleArray(fitIdx.size) { targetDb[fitIdx[it]] }
        val lower = DoubleArray(np)
        val upper = DoubleArray(np)
        val p = DoubleArray(np)
        for (j in 0 until m) {
            val c = log2(slots[j].freq)
            lower[3 * j] = slots[j].minGain
            upper[3 * j] = slots[j].maxGain
            lower[3 * j + 1] = maxOf(c - 0.5, log2(FreqGrid.MIN_HZ))
            upper[3 * j + 1] = minOf(c + 0.5, log2(16000.0))
            lower[3 * j + 2] = kotlin.math.ln(slots[j].minQ)
            upper[3 * j + 2] = kotlin.math.ln(slots[j].maxQ)
            p[3 * j] = start[j].gainDb
            p[3 * j + 1] = log2(start[j].freq).coerceIn(lower[3 * j + 1], upper[3 * j + 1])
            p[3 * j + 2] = kotlin.math.ln(start[j].q).coerceIn(lower[3 * j + 2], upper[3 * j + 2])
        }
        fun spec(j: Int, q: DoubleArray) =
            FilterSpec(slots[j].type, 2.0.pow(q[3 * j + 1]), q[3 * j], kotlin.math.exp(q[3 * j + 2]))
        fun model(q: DoubleArray): DoubleArray {
            val out = DoubleArray(fitHz.size)
            for (j in 0 until m) {
                val r = specDb(spec(j, q))
                for (i in out.indices) out[i] += r[i]
            }
            return out
        }
        fun costOf(q: DoubleArray): Double {
            val mod = model(q)
            var c = 0.0
            for (i in mod.indices) {
                val e = target[i] - mod[i]
                c += fitWeight[i] * e * e
            }
            for (j in 0 until m) c += ridge * q[3 * j] * q[3 * j]
            return c
        }
        val steps = DoubleArray(np) { if (it % 3 == 0) 0.05 else 0.005 }
        var cost = costOf(p)
        var mu = 1e-2
        repeat(iterations) {
            val base = model(p)
            val jac = Array(np) { DoubleArray(fitHz.size) }
            for (k in 0 until np) {
                val j = k / 3
                val up = p.copyOf().also { it[k] += steps[k] }
                val dn = p.copyOf().also { it[k] -= steps[k] }
                val ru = specDb(spec(j, up))
                val rd = specDb(spec(j, dn))
                for (i in fitHz.indices) jac[k][i] = (ru[i] - rd[i]) / (2 * steps[k])
            }
            val a = Array(np) { DoubleArray(np) }
            val b = DoubleArray(np)
            for (r in 0 until np) {
                for (c in r until np) {
                    var sum = 0.0
                    for (i in fitHz.indices) sum += fitWeight[i] * jac[r][i] * jac[c][i]
                    a[r][c] = sum
                    a[c][r] = sum
                }
                var sum = 0.0
                for (i in fitHz.indices) sum += fitWeight[i] * jac[r][i] * (target[i] - base[i])
                b[r] = sum - if (r % 3 == 0) ridge * p[r] else 0.0
            }
            for (r in 0 until np) a[r][r] += (if (r % 3 == 0) ridge else 0.0) + mu * (a[r][r] + 1e-6)
            val delta = solve(a, b)
            if (delta == null) {
                mu *= 4
                return@repeat
            }
            val trial = DoubleArray(np) { (p[it] + delta[it]).coerceIn(lower[it], upper[it]) }
            val trialCost = costOf(trial)
            if (trialCost < cost) {
                val gain = cost - trialCost
                trial.copyInto(p)
                cost = trialCost
                mu = (mu / 3).coerceAtLeast(1e-7)
                if (gain < 1e-7) return (0 until m).map { spec(it, p) }
            } else {
                mu *= 4
                if (mu > 1e8) return (0 until m).map { spec(it, p) }
            }
        }
        return (0 until m).map { spec(it, p) }
    }

    private fun specDb(spec: FilterSpec): DoubleArray {
        if (spec.gainDb == 0.0) return DoubleArray(fitHz.size)
        val bq = Biquad.design(spec, Response.REFERENCE_RATE)
        return DoubleArray(fitHz.size) { bq.magnitudeDb(fitHz[it], Response.REFERENCE_RATE) }
    }

    /** Largest absolute error (dB) between [filters] and [targetDb] between 30 Hz and 16 kHz. */
    fun maxError(targetDb: DoubleArray, filters: List<FilterSpec>): Double {
        val resp = Response.of(filters, FreqGrid.hz)
        var e = 0.0
        for (i in resp.indices) {
            val f = FreqGrid.hz[i]
            if (f in 30.0..16000.0) e = maxOf(e, abs(resp[i] - targetDb[i]))
        }
        return e
    }

    private const val H = 0.05

    private fun filterDb(slot: Slot, gain: Double): DoubleArray {
        if (gain == 0.0) return DoubleArray(fitHz.size)
        val bq = Biquad.design(FilterSpec(slot.type, slot.freq, gain, slot.q), Response.REFERENCE_RATE)
        return DoubleArray(fitHz.size) { bq.magnitudeDb(fitHz[it], Response.REFERENCE_RATE) }
    }

    private fun cost(target: DoubleArray, slots: List<Slot>, g: DoubleArray, ridge: Double): Double {
        val model = DoubleArray(fitHz.size)
        for (j in slots.indices) {
            val r = filterDb(slots[j], g[j])
            for (i in model.indices) model[i] += r[i]
        }
        var c = 0.0
        for (i in model.indices) {
            val e = target[i] - model[i]
            c += fitWeight[i] * e * e
        }
        for (v in g) c += ridge * v * v
        return c
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    internal fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val v = b.copyOf()
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            if (piv != col) {
                val t = m[piv]; m[piv] = m[col]; m[col] = t
                val tv = v[piv]; v[piv] = v[col]; v[col] = tv
            }
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                if (f == 0.0) continue
                for (c in col until n) m[r][c] -= f * m[col][c]
                v[r] -= f * v[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = v[r]
            for (c in r + 1 until n) s -= m[r][c] * x[c]
            x[r] = s / m[r][r]
        }
        return x
    }
}
