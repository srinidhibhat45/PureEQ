package app.pureeq.taste

import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Tone controls on top of the ear tuning. The taste test tunes bass and treble, where listeners'
 * preferences really differ (Harman's headphone studies); warmth, vocals and air stay at 0 for
 * manual fine-tuning. Bass is the 105 Hz shelf of the Harman target research. Treble is the broad
 * shelf from about 2.5 kHz that Harman's listeners adjusted (Olive 2022); PureEQ 1.0-1.4 used an
 * 8 kHz shelf, which no population data backs. Air (10 kHz) is the sparkle above that.
 */
enum class TasteBand(
    val label: String,
    val hint: String,
    val type: FilterType,
    val freq: Double,
    val q: Double,
    val minDb: Double,
    val maxDb: Double,
) {
    BASS("Bass", "Kick drum and bass guitar weight", FilterType.LOW_SHELF, 105.0, 0.7, -6.0, 12.0),
    WARMTH("Warmth", "Body of voices and instruments", FilterType.PEAK, 250.0, 0.8, -6.0, 6.0),
    VOCALS("Vocals", "Clarity and presence of voices", FilterType.PEAK, 2000.0, 0.8, -6.0, 6.0),
    TREBLE("Treble", "Brightness: the edge of voices, cymbals and detail", FilterType.HIGH_SHELF, 2500.0, 0.45, -6.0, 6.0),
    AIR("Air", "Sparkle and space at the very top", FilterType.HIGH_SHELF, 10000.0, 0.7, -6.0, 6.0),
    ;

    fun filter(gainDb: Double) = FilterSpec(type, freq, gainDb, q)
}

data class TasteSettings(
    val bass: Double = 0.0,
    val warmth: Double = 0.0,
    val vocals: Double = 0.0,
    val treble: Double = 0.0,
    val air: Double = 0.0,
    /**
     * Treble is PureEQ 1.0-1.4's 8 kHz shelf: profiles tuned before 1.5 keep the sound they were
     * chosen with until the taste test is redone.
     */
    val legacyTreble: Boolean = false,
) {
    operator fun get(band: TasteBand): Double = when (band) {
        TasteBand.BASS -> bass
        TasteBand.WARMTH -> warmth
        TasteBand.VOCALS -> vocals
        TasteBand.TREBLE -> treble
        TasteBand.AIR -> air
    }

    fun with(band: TasteBand, value: Double): TasteSettings {
        val v = value.coerceIn(band.minDb, band.maxDb)
        return when (band) {
            TasteBand.BASS -> copy(bass = v)
            TasteBand.WARMTH -> copy(warmth = v)
            TasteBand.VOCALS -> copy(vocals = v)
            TasteBand.TREBLE -> copy(treble = v)
            TasteBand.AIR -> copy(air = v)
        }
    }

    /** Filters in a fixed order; bands at 0 dB are left out. */
    fun filters(): List<FilterSpec> = TasteBand.entries.filter { this[it] != 0.0 }
        .map { if (it == TasteBand.TREBLE && legacyTreble) LEGACY_TREBLE.copy(gainDb = treble) else it.filter(this[it]) }

    companion object {
        /** The treble shelf of PureEQ 1.0-1.4. */
        val LEGACY_TREBLE = FilterSpec(FilterType.HIGH_SHELF, 8000.0, 0.0, 0.7)
    }
}

/** One A/B question: two settings, in random order. */
data class Comparison(val round: Int, val a: TasteSettings, val b: TasteSettings)

enum class Choice { A, B, SAME }

/**
 * Finds the listener's preferred bass and treble with a few blind, loudness-matched A/B rounds.
 *
 * The listener is modelled as having an ideal setting; the closer an option is to it, the more
 * they like it (a quadratic utility: missing by 3 dB of bass or treble costs one unit). The
 * belief over that ideal starts from the population: Harman found 64 % of listeners like the
 * target, 15 % want 4-6 dB more bass and 21 % about 2.5 dB less with a little more treble (Olive,
 * Welti & Khonsaripour 2019). Each answer, "Same" included,
 * updates it (an ordered-choice model with occasional slips). Every round plays the pair whose
 * answer is expected to shrink the remaining error most. The session stops once the estimate is
 * settled (usually 5-7 rounds); "Finish now" returns the best estimate so far.
 *
 * In simulation this lands within about 0.5 dB of the listener's ideal bass and 0.7 dB of treble,
 * against 1.2-1.4 dB for the 12-round halving test it replaces.
 */
class TasteSession(
    start: TasteSettings = TasteSettings(),
    val maxRounds: Int = MAX_ROUNDS,
    private val rnd: Random = Random.Default,
    /** A measured correction brings the headphones to the target, so the population prior applies as is. */
    corrected: Boolean = false,
) {
    private val start = start.copy(legacyTreble = false)
    private val w = DoubleArray(GRID_BASS.size) { prior(GRID_BASS[it], GRID_TREBLE[it], if (corrected) 1.0 else WIDEN) }.also { normalise(it) }
    private val startLoss = stats(w).loss
    private var pending: Comparison? = null

    val history = mutableListOf<Pair<Comparison, Choice>>()
    val answered: Int get() = history.size
    val total: Int get() = maxRounds

    /** 0..1: how settled the estimate is. */
    val certainty: Float
        get() = ((startLoss - stats(w).loss) / (startLoss - STOP_LOSS)).coerceIn(0.0, 1.0).toFloat()

    val isDone: Boolean
        get() = answered >= maxRounds || (answered >= MIN_ROUNDS && stats(w).loss < STOP_LOSS)

    fun current(): Comparison? {
        pending?.let { return it }
        if (isDone) return null
        val (x, y) = bestPair()
        val swap = rnd.nextBoolean()
        val a = if (swap) y else x
        val b = if (swap) x else y
        return Comparison(answered, start.copy(bass = a.first, treble = a.second), start.copy(bass = b.first, treble = b.second)).also { pending = it }
    }

    fun answer(choice: Choice) {
        val c = pending ?: return
        pending = null
        val lapse = if (answered < 2) EARLY_LAPSE else LAPSE
        history += c to choice
        val d = Delta(c.a.bass, c.a.treble, c.b.bass, c.b.treble)
        for (i in w.indices) w[i] *= probability(d.at(GRID_BASS[i], GRID_TREBLE[i]), choice, lapse)
        normalise(w)
    }

    /** Settings for the side the listener would be choosing, to preview while deciding. */
    fun candidate(cmp: Comparison, choice: Choice): TasteSettings = when (choice) {
        Choice.A -> cmp.a
        Choice.B -> cmp.b
        Choice.SAME -> result()
    }

    /** Best estimate so far (the posterior mean, to 0.5 dB); warmth and vocals stay as they started. */
    fun result(): TasteSettings {
        val s = stats(w)
        return start.copy(bass = half(s.bass), treble = half(s.treble))
    }

    private class Stats(val bass: Double, val treble: Double, val loss: Double)

    /** Posterior mean and expected loss (expected utility lost by using the mean). */
    private fun stats(p: DoubleArray): Stats {
        var s0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var t1 = 0.0
        var t2 = 0.0
        for (i in p.indices) {
            val x = p[i]
            s0 += x
            b1 += x * GRID_BASS[i]
            b2 += x * GRID_BASS[i] * GRID_BASS[i]
            t1 += x * GRID_TREBLE[i]
            t2 += x * GRID_TREBLE[i] * GRID_TREBLE[i]
        }
        val mb = b1 / s0
        val mt = t1 / s0
        val loss = max(b2 / s0 - mb * mb, 0.0) / (BASS_UNIT * BASS_UNIT) + max(t2 / s0 - mt * mt, 0.0) / (TREBLE_UNIT * TREBLE_UNIT)
        return Stats(mb, mt, loss)
    }

    /** Utility of A minus utility of B as a function of the ideal (mb, mt); linear in both. */
    private class Delta(ab: Double, at: Double, bb: Double, bt: Double) {
        private val c0 = -(ab * ab - bb * bb) / (BASS_UNIT * BASS_UNIT) - (at * at - bt * bt) / (TREBLE_UNIT * TREBLE_UNIT)
        private val cb = 2 * (ab - bb) / (BASS_UNIT * BASS_UNIT)
        private val ct = 2 * (at - bt) / (TREBLE_UNIT * TREBLE_UNIT)
        fun at(mb: Double, mt: Double) = c0 + cb * mb + ct * mt
    }

    /** The candidate pair whose answer is expected to leave the least loss. */
    private fun bestPair(): Pair<Pair<Double, Double>, Pair<Double, Double>> {
        val s = stats(w)
        val dirs = mutableListOf(1.0 to 0.0, 0.0 to 1.0, DIAG to DIAG, DIAG to -DIAG)
        principalAxis()?.let { (x, y) -> dirs += x to y; dirs += -y to x }
        val pairs = mutableListOf<Pair<Pair<Double, Double>, Pair<Double, Double>>>()
        val centre = clip(s.bass, s.treble)
        for ((vx, vy) in dirs) {
            for (d in STEPS) {
                pairs += clip(s.bass + d * vx, s.treble + d * vy) to clip(s.bass - d * vx, s.treble - d * vy)
                pairs += centre to clip(s.bass + 2 * d * vx, s.treble + 2 * d * vy)
                pairs += centre to clip(s.bass - 2 * d * vx, s.treble - 2 * d * vy)
            }
        }
        val lapse = if (answered < 2) EARLY_LAPSE else LAPSE
        var best = pairs.first()
        var bestLoss = Double.POSITIVE_INFINITY
        val post = DoubleArray(w.size)
        for (pair in pairs.distinct()) {
            val (a, b) = pair
            if (abs(a.first - b.first) + abs(a.second - b.second) < 1.0) continue
            val d = Delta(a.first, a.second, b.first, b.second)
            var expected = 0.0
            for (choice in Choice.entries) {
                var p = 0.0
                for (i in w.indices) {
                    post[i] = w[i] * probability(d.at(GRID_BASS[i], GRID_TREBLE[i]), choice, lapse)
                    p += post[i]
                }
                expected += p * stats(post).loss
            }
            if (expected < bestLoss) {
                bestLoss = expected
                best = pair
            }
        }
        return best
    }

    /** Unit vector along which the posterior is widest, if it is clearly tilted. */
    private fun principalAxis(): Pair<Double, Double>? {
        val s = stats(w)
        var vbb = 0.0
        var vtt = 0.0
        var vbt = 0.0
        for (i in w.indices) {
            val db = GRID_BASS[i] - s.bass
            val dt = GRID_TREBLE[i] - s.treble
            vbb += w[i] * db * db
            vtt += w[i] * dt * dt
            vbt += w[i] * db * dt
        }
        if (abs(vbt) < 1e-6) return null
        val tr = vbb + vtt
        val det = vbb * vtt - vbt * vbt
        val l1 = tr / 2 + sqrt(max(tr * tr / 4 - det, 0.0))
        val x = vbt
        val y = l1 - vbb
        val n = sqrt(x * x + y * y)
        return if (n < 1e-9) null else (x / n) to (y / n)
    }

    companion object {
        const val MAX_ROUNDS = 8
        const val MIN_ROUNDS = 4
        const val BASS_MIN = -6.0
        const val BASS_MAX = 10.0
        const val TREBLE_MIN = -6.0
        const val TREBLE_MAX = 6.0

        /** dB of bass or treble away from the ideal that cost one unit of preference. */
        private const val BASS_UNIT = 3.0
        private const val TREBLE_UNIT = 3.0

        /** Utility differences within ±0.25 tend to be heard as "the same"; answer noise 0.6 units. */
        private const val SAME_ZONE = 0.25
        private val NOISE = sqrt(2.0) * 0.6
        private const val LAPSE = 0.05
        private const val EARLY_LAPSE = 0.10
        private const val STOP_LOSS = 0.12
        private val STEPS = doubleArrayOf(1.5, 2.5, 3.5, 5.0)
        private val DIAG = sqrt(0.5)

        private val GRID_BASS: DoubleArray
        private val GRID_TREBLE: DoubleArray

        init {
            val b = generateSequence(BASS_MIN) { it + 0.5 }.takeWhile { it <= BASS_MAX + 1e-9 }.toList()
            val t = generateSequence(TREBLE_MIN) { it + 0.5 }.takeWhile { it <= TREBLE_MAX + 1e-9 }.toList()
            GRID_BASS = DoubleArray(b.size * t.size) { b[it / t.size] }
            GRID_TREBLE = DoubleArray(b.size * t.size) { t[it % t.size] }
        }

        /** The most extreme setting a session can play from [start], for fixing one loudness headroom up front. */
        fun extreme(start: TasteSettings) = start.copy(bass = BASS_MAX, treble = TREBLE_MAX, legacyTreble = false)

        /** Without a measured correction the headphones' own sound isn't at the target: the groups widen by half. */
        private const val WIDEN = 1.5

        /**
         * Population prior over the ideal (bass, treble), relative to the corrected headphones:
         * Harman's three groups, each 1.5 dB wide in bass and 2 dB in treble (inferred: the
         * published spread is mostly in bass), times [widen].
         */
        private fun prior(bass: Double, treble: Double, widen: Double): Double {
            val sb = 1.5 * widen
            val st = 2.0 * widen
            return 0.64 * normal(bass, 0.0, sb) * normal(treble, 0.0, st) +
                0.15 * normal(bass, 4.5, sb) * normal(treble, 0.5, st) +
                0.21 * normal(bass, -2.5, sb) * normal(treble, 1.5, st)
        }

        private fun normal(x: Double, m: Double, s: Double) = exp(-0.5 * (x - m) * (x - m) / (s * s)) / s

        private fun logistic(z: Double) = 1 / (1 + exp(-1.702 * z))

        /** P(answer | utility difference A − B): ordered choice with a "same" zone, plus slips. */
        private fun probability(delta: Double, choice: Choice, lapse: Double): Double {
            val pa = logistic((delta - SAME_ZONE) / NOISE)
            val pb = logistic((-delta - SAME_ZONE) / NOISE)
            val p = when (choice) {
                Choice.A -> pa
                Choice.B -> pb
                Choice.SAME -> 1 - pa - pb
            }
            return (1 - lapse) * p + lapse / 3
        }

        private fun normalise(p: DoubleArray) {
            val s = p.sum()
            for (i in p.indices) p[i] /= s
        }

        private fun half(x: Double) = round(x * 2) / 2

        private fun clip(b: Double, t: Double) = half(b.coerceIn(BASS_MIN, BASS_MAX)) to half(t.coerceIn(TREBLE_MIN, TREBLE_MAX))
    }
}
