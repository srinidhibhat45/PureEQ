package app.pureeq.hearing

import app.pureeq.dsp.Ear
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** The kind of headphones a test is taken with. It picks the target curve the result is measured against. */
enum class HeadphoneType(val label: String) {
    IN_EAR("In-ear"),
    OVER_EAR("Over-ear"),
    ;

    companion object {
        private val inEarWords = listOf("bud", "pod", "wf-", "earbud", "in-ear", "iem", "ear (", "nothing ear", "freebuds", "enco", "liberty", "elite", "tws")
        private val overEarWords = listOf("wh-", "headphone", "over-ear", "on-ear", "quietcomfort", "qc45", "qc35", "momentum", "studio", "px7", "px8", "ath-m", "hd ", "dt ", "solo", "max")

        /** A guess from the device name: "Galaxy Buds2 Pro" is in-ear, "WH-1000XM5" over-ear. In-ear when unsure, as most phone headphones are. */
        fun guess(name: String?): HeadphoneType {
            val n = name?.lowercase() ?: return IN_EAR
            if ("airpods max" in n) return OVER_EAR
            if (inEarWords.any { it in n }) return IN_EAR
            if (overEarWords.any { it in n }) return OVER_EAR
            return IN_EAR
        }
    }
}

/**
 * Expected normal-hearing threshold through headphones tuned to the Harman target, in dB re 1 kHz:
 * the ISO 389-7:2019 diffuse-field threshold minus the target's difference from diffuse field
 * (Harman over-ear 2018 or in-ear 2019, as published with AutoEq). A measured threshold minus
 * this curve is how much less well a band is heard through these headphones than through
 * target-tuned ones by a young normal-hearing listener: the headphones' deviation from the target
 * plus any hearing loss, which together are what an EQ can correct. The old free-field curve
 * implied a bass-light target that listeners find thin and harsh.
 */
object ReferenceCurve {
    private val hz = doubleArrayOf(
        20.0, 31.5, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 750.0, 800.0,
        1000.0, 1250.0, 1500.0, 1600.0, 2000.0, 2500.0, 3000.0, 3150.0, 4000.0, 5000.0, 6000.0, 6300.0, 8000.0,
        9000.0, 10000.0, 11200.0, 12000.0, 12500.0, 14000.0, 16000.0,
    )
    private val inEar = doubleArrayOf(
        65.8, 47.1, 32.7, 27.3, 22.7, 19.1, 16.0, 13.0, 10.8, 8.6, 6.0, 3.9, 2.7, 1.4, 0.7, 0.6,
        0.0, 1.3, 0.5, 0.5, -0.2, -1.2, -2.9, -3.2, -4.1, -3.4, -0.7, 0.4, 7.8,
        11.1, 12.9, 15.0, 18.9, 20.4, 29.1, 50.4,
    )
    private val overEar = doubleArrayOf(
        69.6, 50.9, 36.0, 30.1, 24.9, 20.8, 17.5, 14.2, 11.7, 9.0, 5.9, 3.5, 2.1, 0.8, 0.2, 0.1,
        0.0, 1.6, 0.9, 1.0, 0.3, -0.2, -1.9, -2.2, -3.4, -2.1, 0.9, 2.1, 9.0,
        11.6, 12.9, 14.7, 18.5, 20.1, 28.6, 49.4,
    )
    private val logHz = DoubleArray(hz.size) { log2(hz[it]) }

    /** Linear in log-frequency between table points, held beyond the ends. */
    fun at(freq: Double, type: HeadphoneType): Double {
        val db = if (type == HeadphoneType.IN_EAR) inEar else overEar
        val x = log2(freq)
        if (x <= logHz.first()) return db.first()
        if (x >= logHz.last()) return db.last()
        var k = 0
        while (x > logHz[k + 1]) k++
        val t = (x - logHz[k]) / (logHz[k + 1] - logHz[k])
        return db[k] * (1 - t) + db[k + 1] * t
    }
}

/** One ear at one test frequency. */
data class Node(val ear: Ear, val freq: Double)

/**
 * Gaussian prior over the thresholds (dBFS) at every node, both ears together:
 *
 *   threshold = calibration offset + reference curve + shape (common to both ears, ± a smooth
 *   left/right difference, + each ear's own level offset) + a local part per node.
 *
 * The shape is smooth across frequency (squared-exponential in octaves), so each answer also
 * informs neighbouring frequencies and the other ear: that is what lets the test stop early.
 * The local part soaks up narrow ear-canal and earphone resonances that a smooth EQ shouldn't chase.
 * The offset is wide because "soft but clear" sits 5-25 dB above threshold, depending on the listener.
 * A known headphone correction shifts the reference, so the shape is what remains after it.
 */
class ThresholdPrior(
    val nodes: List<Node>,
    val calibrationDb: Double,
    val type: HeadphoneType,
    /** Known headphone deviation from the target (dB to add, by frequency), e.g. from an AutoEq measurement. */
    shift: (Double) -> Double = { 0.0 },
) {
    val mean = DoubleArray(nodes.size) {
        val f = nodes[it].freq
        calibrationDb + START_OFFSET_DB + ReferenceCurve.at(f, type) + shift(f) - ReferenceCurve.at(1000.0, type) - shift(1000.0)
    }
    val cov: Array<DoubleArray> = Array(nodes.size) { i ->
        DoubleArray(nodes.size) { j ->
            OFFSET_SD * OFFSET_SD + shapeCov(nodes[i].ear, nodes[i].freq, nodes[j].ear, nodes[j].freq) + if (i == j) localSd(nodes[i].freq).let { it * it } else 0.0
        }
    }

    private val covInverse: Array<DoubleArray> by lazy { Linear.inverseSpd(cov) }

    /**
     * Mean and standard deviation of the smooth shape of [ear] at any [freq], given the belief over
     * the nodes. The shape relates to the nodes only through the prior, so with k = cov(shape, nodes)
     * and K the prior covariance: mean = kᵀK⁻¹(m − m₀), variance = k(f,f) − kᵀK⁻¹k + kᵀK⁻¹ Σ K⁻¹k.
     */
    fun shape(ear: Ear, freq: Double, belief: ThresholdBelief): Pair<Double, Double> =
        if (ear == Ear.LEFT) combination(freq, 1.0, 0.0, belief) else combination(freq, 0.0, 1.0, belief)

    /**
     * Mean and standard deviation of wLeft × the left shape + wRight × the right shape at [freq]:
     * (½, ½) is what both ears share, (½, −½) half their difference, each with its own uncertainty.
     */
    fun combination(freq: Double, wLeft: Double, wRight: Double, belief: ThresholdBelief): Pair<Double, Double> {
        val n = nodes.size
        val k = DoubleArray(n) { wLeft * shapeCov(Ear.LEFT, freq, nodes[it].ear, nodes[it].freq) + wRight * shapeCov(Ear.RIGHT, freq, nodes[it].ear, nodes[it].freq) }
        val prior = wLeft * wLeft * shapeCov(Ear.LEFT, freq, Ear.LEFT, freq) + wRight * wRight * shapeCov(Ear.RIGHT, freq, Ear.RIGHT, freq) +
            2 * wLeft * wRight * shapeCov(Ear.LEFT, freq, Ear.RIGHT, freq)
        val a = DoubleArray(n) { i -> var s = 0.0; for (j in 0 until n) s += covInverse[i][j] * k[j]; s }
        var m = 0.0
        var explained = 0.0
        var left = 0.0
        for (i in 0 until n) {
            m += a[i] * (belief.mean[i] - mean[i])
            explained += a[i] * k[i]
            var row = 0.0
            for (j in 0 until n) row += belief.cov[i][j] * a[j]
            left += a[i] * row
        }
        val variance = prior - explained + left
        return m to sqrt(max(variance, 0.0))
    }

    companion object {
        /** Threshold sits about this far below the "soft but clear" calibration beep. */
        const val START_OFFSET_DB = -15.0
        const val OFFSET_SD = 12.0
        const val COMMON_LENGTH_OCT = 1.0
        const val DIFFERENCE_LENGTH_OCT = 1.2
        const val DIFFERENCE_SD = 4.0

        /**
         * Each ear's own level, on top of the smooth difference: fit, a blocked ear, a weaker ear.
         * With only the smooth difference, a 15 dB difference between the ears came out 6 dB short.
         */
        const val EAR_OFFSET_SD = 3.0

        /** Shape spread: 4.5 dB up to 2 kHz, widening to 8 dB at 8 kHz, where hearing varies most with age. */
        fun commonSd(freq: Double): Double {
            val x = (log2(freq / 2000.0) / 2).coerceIn(0.0, 1.0)
            return 4.5 + 3.5 * x
        }

        fun localSd(freq: Double): Double = if (freq <= 4000.0) 3.0 else 4.0

        fun shapeCov(e1: Ear, f1: Double, e2: Ear, f2: Double): Double {
            val d = log2(f1 / f2)
            val common = commonSd(f1) * commonSd(f2) * exp(-0.5 * (d / COMMON_LENGTH_OCT) * (d / COMMON_LENGTH_OCT))
            val diff = DIFFERENCE_SD * DIFFERENCE_SD * exp(-0.5 * (d / DIFFERENCE_LENGTH_OCT) * (d / DIFFERENCE_LENGTH_OCT))
            return common + if (e1 == e2) diff + EAR_OFFSET_SD * EAR_OFFSET_SD else -diff
        }
    }
}

/** How a listener answers: P(yes) = guess + (1 − guess − lapse) · logistic((level − threshold) / spread). */
object Psychometric {
    /** About 10 % more "yes" per dB at threshold, typical for pulsed tones. */
    const val SPREAD_DB = 2.5
    const val LAPSE = 0.03

    fun pYes(threshold: Double, levelDb: Double, guess: Double): Double =
        guess + (1 - guess - LAPSE) / (1 + exp(-(levelDb - threshold) / SPREAD_DB))

    /**
     * With the beep played again before answering, "yes" means heard in either listen: about as
     * likely as one listen 2 dB louder at threshold. Counting it so keeps a replay from reading low.
     */
    fun pYes(threshold: Double, levelDb: Double, guess: Double, looks: Int): Double {
        val one = pYes(threshold, levelDb, guess)
        return if (looks <= 1) one else 1 - (1 - one).pow(looks)
    }

    /** Guessing rate from silent catch trials: Beta(1, 19) prior (5 %), kept between 1 % and 30 %. */
    fun guessRate(catchTrials: Int, falseAlarms: Int): Double = ((1.0 + falseAlarms) / (20.0 + catchTrials)).coerceIn(0.01, 0.3)
}

/**
 * Gaussian belief over every node's threshold, updated one answer at a time by assumed-density
 * filtering: the answer's exact effect on that node is found by quadrature, then spread to all
 * other nodes through their prior correlation (a rank-one update).
 */
class ThresholdBelief(val prior: ThresholdPrior) {
    val mean: DoubleArray = prior.mean.copyOf()
    val cov: Array<DoubleArray> = Array(prior.cov.size) { prior.cov[it].copyOf() }

    fun sd(node: Int): Double = sqrt(cov[node][node])

    fun update(node: Int, levelDb: Double, heard: Boolean, guess: Double, looks: Int = 1) {
        val m = mean[node]
        val v = cov[node][node]
        val (mm, vv) = moments(m, v, levelDb, heard, guess, looks)
        observe(node, mm, vv)
    }

    /** A Gaussian measurement of one node (used for results saved before the trial log existed). */
    fun updateGaussian(node: Int, valueDb: Double, sdDb: Double) {
        val m = mean[node]
        val v = cov[node][node]
        val r = sdDb * sdDb
        observe(node, m + v / (v + r) * (valueDb - m), v * r / (v + r))
    }

    /** Moves the node's marginal to (newMean, newVar) and every other node along with it. */
    private fun observe(node: Int, newMean: Double, newVar: Double) {
        val m = mean[node]
        val v = cov[node][node]
        if (v <= 1e-9) return
        val col = DoubleArray(mean.size) { cov[it][node] }
        val shift = (newMean - m) / v
        val shrink = (v - newVar) / (v * v)
        for (i in mean.indices) {
            mean[i] += col[i] * shift
            for (j in mean.indices) cov[i][j] -= col[i] * col[j] * shrink
        }
    }

    /** Expected variance of [node] after one answer at each of [levels]; lower means more informative. */
    fun expectedVariance(node: Int, levels: DoubleArray, guess: Double): DoubleArray {
        val m = mean[node]
        val s = sqrt(cov[node][node])
        return DoubleArray(levels.size) { k ->
            var total = 0.0
            for (heard in BOOLS) {
                var w0 = 0.0
                var w1 = 0.0
                var w2 = 0.0
                for (q in Z.indices) {
                    val th = m + s * Z[q]
                    val p = Psychometric.pYes(th, levels[k], guess)
                    val w = PHI[q] * if (heard) p else 1 - p
                    w0 += w
                    w1 += w * th
                    w2 += w * th * th
                }
                val mu = w1 / w0
                total += (w0 / PHI_SUM) * max(w2 / w0 - mu * mu, 0.0)
            }
            total
        }
    }

    companion object {
        private val BOOLS = booleanArrayOf(true, false)
        private val Z = DoubleArray(161) { -6.0 + 12.0 * it / 160 }
        private val PHI = DoubleArray(Z.size) { exp(-0.5 * Z[it] * Z[it]) }
        private val PHI_SUM = PHI.sum()

        /** Mean and variance of N(m, v) × P(answer | threshold), by quadrature over ±6 SD. */
        fun moments(m: Double, v: Double, levelDb: Double, heard: Boolean, guess: Double, looks: Int = 1): Pair<Double, Double> {
            val s = sqrt(v)
            var w0 = 0.0
            var w1 = 0.0
            var w2 = 0.0
            for (q in Z.indices) {
                val th = m + s * Z[q]
                val p = Psychometric.pYes(th, levelDb, guess, looks)
                val w = PHI[q] * if (heard) p else 1 - p
                w0 += w
                w1 += w * th
                w2 += w * th * th
            }
            val mu = w1 / w0
            return mu to max(w2 / w0 - mu * mu, 1e-6)
        }

        /**
         * The belief behind a finished test. Results with a trial log replay every answer with the
         * final guessing rate; older results (point estimates only) enter as Gaussian measurements.
         */
        fun of(result: HearingTestResult, type: HeadphoneType, shift: (Double) -> Double = { 0.0 }): ThresholdBelief {
            val nodes = result.points.map { Node(it.ear, it.freq) }.distinct()
                .ifEmpty { result.trialLog.map { Node(it.ear, it.freq) }.distinct() }
                .sortedWith(compareBy({ it.freq }, { it.ear }))
            val belief = ThresholdBelief(ThresholdPrior(nodes, result.calibrationDb, type, shift))
            val index = nodes.withIndex().associate { it.value to it.index }
            if (result.trialLog.isNotEmpty()) {
                val guess = Psychometric.guessRate(result.catchTrials, result.falseAlarms)
                for (t in result.trialLog) belief.update(index.getValue(Node(t.ear, t.freq)), t.levelDb, t.heard, guess, t.looks)
            } else {
                for (p in result.points) {
                    val sd = if (p.flag == ThresholdFlag.OK) max(p.sd, 2.0) else 8.0
                    belief.updateGaussian(index.getValue(Node(p.ear, p.freq)), p.db, sd)
                }
            }
            return belief
        }
    }
}

/** Small dense linear algebra for symmetric positive-definite matrices (up to a few dozen unknowns). */
internal object Linear {
    /** Inverse by Cholesky, one column at a time. The prior covariance is positive definite by construction. */
    fun inverseSpd(a: Array<DoubleArray>): Array<DoubleArray> {
        val l = cholesky(a) ?: error("covariance not positive definite")
        val n = a.size
        val inv = Array(n) { DoubleArray(n) }
        for (c in 0 until n) {
            val x = solve(l, DoubleArray(n) { if (it == c) 1.0 else 0.0 })
            for (r in 0 until n) inv[r][c] = x[r]
        }
        return inv
    }

    private fun cholesky(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                var s = a[i][j]
                for (k in 0 until j) s -= l[i][k] * l[j][k]
                if (i == j) {
                    if (s <= 0) return null
                    l[i][i] = sqrt(s)
                } else {
                    l[i][j] = s / l[j][j]
                }
            }
        }
        return l
    }

    /** Solves L·Lᵀ·x = b for a Cholesky factor L. */
    private fun solve(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var s = b[i]
            for (k in 0 until i) s -= l[i][k] * y[k]
            y[i] = s / l[i][i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1 until n) s -= l[k][i] * x[k]
            x[i] = s / l[i][i]
        }
        return x
    }
}
