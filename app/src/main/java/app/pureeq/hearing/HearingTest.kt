package app.pureeq.hearing

import app.pureeq.dsp.Ear
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

enum class ThresholdFlag { OK, NO_RESPONSE, BELOW_RANGE }

/** One answer to a real beep: where and how loud it was, and whether it was heard. */
/** One answered beep; [looks] is 2 when the listener played it again before answering. */
data class TrialRecord(val freq: Double, val ear: Ear, val levelDb: Double, val heard: Boolean, val looks: Int = 1)

/** Settings for one hearing test. Levels are peak dBFS. */
data class HearingTestConfig(
    val frequencies: List<Double> = BANDS,
    /** Level the listener set as "soft but clearly audible" at 1 kHz. */
    val calibrationDb: Double = -40.0,
    val headphones: HeadphoneType = HeadphoneType.IN_EAR,
    val maxDb: Double = -3.0,
    val minDb: Double = -110.0,
    val warble: Boolean = true,
    /** Silent catch trials measure guessing. */
    val catchTrials: Boolean = true,
    /** A band in one ear is finished once its threshold is known to about this many dB (posterior SD). */
    val stopSdDb: Double = 3.5,
    val maxPerNode: Int = 7,
    /** A known headphone correction at the test frequencies (dB), when the model was picked first. */
    val referenceShiftDb: Map<Double, Double> = emptyMap(),
) {
    companion object {
        /**
         * 500 Hz to 8 kHz. Lower bands aren't tested: room noise, body noise under sealed earphones
         * and earbud fit make bass thresholds unreliable, so bass comes from the target and your taste.
         */
        val BANDS = listOf(500.0, 1000.0, 2000.0, 3000.0, 4000.0, 6000.0, 8000.0)

        /** Octave bands first, so the in-between ones are mostly predicted and need few beeps. */
        val ORDER = listOf(1000.0, 2000.0, 4000.0, 8000.0, 500.0, 3000.0, 6000.0, 250.0, 125.0, 10000.0, 12000.0)
    }
}

/** Test lengths offered in the app: the same bands, measured to different precision. Times are simulated, Bluetooth included. */
enum class TestLength(val label: String, val detail: String, val stopSdDb: Double, val maxPerNode: Int, val tasteRounds: Int) {
    QUICK("Quick", "7 bands per ear · about 1½ min", 4.5, 5, 5),
    STANDARD("Standard", "7 bands per ear · about 2 min", 3.5, 7, 8),
    THOROUGH("Thorough", "7 bands, extra precise · about 3 min", 2.75, 8, 8),
    ;

    fun config(calibrationDb: Double, warble: Boolean, headphones: HeadphoneType, referenceShiftDb: Map<Double, Double> = emptyMap()) = HearingTestConfig(
        calibrationDb = calibrationDb,
        headphones = headphones,
        warble = warble,
        stopSdDb = stopSdDb,
        maxPerNode = maxPerNode,
        referenceShiftDb = referenceShiftDb,
    )
}

/** What the app should play next. A catch trial plays nothing, with the same timing as a real one. */
data class Presentation(val ear: Ear, val freq: Double, val levelDb: Double, val isCatch: Boolean)

data class ThresholdPoint(val freq: Double, val ear: Ear, val db: Double, val sd: Double, val flag: ThresholdFlag)

enum class Reliability { GOOD, FAIR, POOR }

data class HearingTestResult(
    val points: List<ThresholdPoint>,
    val catchTrials: Int,
    val falseAlarms: Int,
    /** 1 kHz re-check differences from PureEQ 1.0-1.1's Thorough test; null otherwise. */
    val retestDiffLeft: Double?,
    val retestDiffRight: Double?,
    val trials: Int,
    val durationSec: Int,
    val warble: Boolean,
    val calibrationDb: Double,
    /** Every answer to a real beep, in order. Empty for results saved before PureEQ 1.2. */
    val trialLog: List<TrialRecord> = emptyList(),
    /** The headphone type the result is measured against; null before PureEQ 1.2. */
    val headphones: HeadphoneType? = null,
    /** Answers far off the final estimate (a "yes" well below threshold or a "no" well above): slips or guesses. */
    val inconsistent: Int = 0,
) {
    val falseAlarmRate: Double get() = if (catchTrials == 0) 0.0 else falseAlarms.toDouble() / catchTrials

    fun pointsFor(ear: Ear): List<ThresholdPoint> = points.filter { it.ear == ear }.sortedBy { it.freq }

    val reliability: Reliability
        get() {
            val retest = listOfNotNull(retestDiffLeft, retestDiffRight).maxOfOrNull { abs(it) } ?: 0.0
            val fa = falseAlarmRate
            val odd = if (trialLog.isEmpty()) 0.0 else inconsistent.toDouble() / trialLog.size
            return when {
                (fa > 0.34 && falseAlarms >= 2) || retest > 15 || odd > 0.2 -> Reliability.POOR
                fa > 0.2 || retest > 10 || odd > 0.1 -> Reliability.FAIR
                else -> Reliability.GOOD
            }
        }
}

/**
 * Bayesian adaptive hearing test. One Gaussian belief covers every band in both ears, linked
 * across frequency and between the ears ([ThresholdPrior]), so each answer sharpens the whole
 * picture. Bands are tested one at a time (the listener knows which pitch to listen for), ears
 * interleaved. The first beep of a band is clearly audible; after that each beep is played at
 * the level expected to narrow that threshold down the most. A band in one ear is finished
 * once its threshold is known to [HearingTestConfig.stopSdDb]. Silent catch trials measure
 * guessing, and the model accounts for it and for occasional slips.
 *
 * In simulation (200 listeners, realistic noise) this needs about 57 answers for 7 bands per
 * ear, where the staircase it replaces needed about 150, with a smoother, more accurate curve.
 */
class HearingSession(val config: HearingTestConfig, private val rnd: Random = Random.Default) {

    private val freqs: List<Double> = config.frequencies.distinct().let { f ->
        HearingTestConfig.ORDER.filter { it in f } + f.filter { it !in HearingTestConfig.ORDER }.sorted()
    }
    private val nodes: List<Node> = freqs.flatMap { listOf(Node(Ear.LEFT, it), Node(Ear.RIGHT, it)) }
    private val prior = ThresholdPrior(nodes, config.calibrationDb, config.headphones) { config.referenceShiftDb[it] ?: 0.0 }
    private var belief = ThresholdBelief(prior)
    private val log = mutableListOf<Pair<Int, TrialRecord>>()
    private val count = IntArray(nodes.size)
    private var block = 0
    private var current: Presentation? = null
    private var currentNode = -1
    private var lastNode = -1
    private var sameNodeRun = 0
    private var maxPresented = Double.NEGATIVE_INFINITY
    private var nextCatchAt = rnd.nextInt(4, 9)
    private val startedAt = System.currentTimeMillis()

    var catchTrials = 0
        private set
    var falseAlarms = 0
        private set
    var trials = 0
        private set

    val currentFrequency: Double? get() = freqs.getOrNull(block)
    val isComplete: Boolean get() = block >= freqs.size

    private fun guess() = Psychometric.guessRate(catchTrials, falseAlarms)

    /** The next presentation, or null when the test is complete. Call [answer] before calling again. */
    fun next(): Presentation? {
        current?.let { return it }
        while (block < freqs.size) {
            val f = freqs[block]
            val open = nodes.indices.filter { nodes[it].freq == f && !done(it) }
            if (open.isEmpty()) {
                block++
                continue
            }
            if (config.catchTrials && trials >= nextCatchAt) {
                // Once the listener has shown they don't guess, catch trials can be rarer.
                val relaxed = catchTrials >= 4 && falseAlarms == 0
                nextCatchAt = trials + if (relaxed) rnd.nextInt(12, 19) else rnd.nextInt(8, 13)
                currentNode = -1
                val ear = if (rnd.nextBoolean()) Ear.LEFT else Ear.RIGHT
                return Presentation(ear, f, belief.mean[open.first()], isCatch = true).also { current = it }
            }
            // At most two beeps in a row for the same ear and band.
            val candidates = if (open.size == 2 && lastNode in open && sameNodeRun >= 1) open.filter { it != lastNode } else open.shuffled(rnd)
            var best = -1
            var bestLevel = 0.0
            var bestScore = Double.NEGATIVE_INFINITY
            for (j in candidates) {
                val (level, score) = placement(j)
                if (score > bestScore) {
                    best = j
                    bestLevel = level
                    bestScore = score
                }
            }
            currentNode = best
            return Presentation(nodes[best].ear, f, bestLevel, isCatch = false).also { current = it }
        }
        return null
    }

    /** Records the answer to the current beep; [looks] = 2 when it was played again first. */
    fun answer(heard: Boolean, looks: Int = 1) {
        val p = current ?: return
        current = null
        trials++
        if (p.isCatch) {
            catchTrials++
            if (heard) falseAlarms++
        } else {
            val j = currentNode
            log += j to TrialRecord(p.freq, p.ear, p.levelDb, heard, looks)
            count[j]++
            maxPresented = max(maxPresented, p.levelDb)
            sameNodeRun = if (j == lastNode) sameNodeRun + 1 else 0
            lastNode = j
        }
        // Replaying every answer with the current guessing rate keeps the belief exact as catch trials come in.
        belief = ThresholdBelief(prior).also { b ->
            val g = guess()
            for ((j, t) in log) b.update(j, t.levelDb, t.heard, g, t.looks)
        }
    }

    /** 0..1: finished bands count fully, the current ones in part. */
    val progress: Float
        get() {
            if (nodes.isEmpty()) return 1f
            var s = 0.0
            for (j in nodes.indices) s += if (done(j)) 1.0 else min(count[j] / 4.0, 0.8)
            return (s / nodes.size).toFloat()
        }

    /** Level for the next beep at node [j] and how much it should teach (variance reduction). */
    private fun placement(j: Int): Pair<Double, Double> {
        val m = belief.mean[j]
        val sd = belief.sd(j)
        val top = ceiling()
        // A new band starts clearly audible, so the listener knows which pitch to listen for.
        if (count[j] == 0) return (m + max(FAMILIARIZE_DB, sd)).roundToInt().toDouble().coerceIn(config.minDb, top) to Double.POSITIVE_INFINITY
        val levels = (floor(m - SEARCH_DB).toInt()..ceil(m + SEARCH_DB).toInt())
            .map { it.toDouble().coerceIn(config.minDb, top) }.distinct().toDoubleArray()
        val expected = belief.expectedVariance(j, levels, guess())
        var k = 0
        for (i in expected.indices) if (expected[i] < expected[k]) k = i
        return levels[k] to (sd * sd - expected[k])
    }

    /** Never more than 10 dB above anything played so far (or the calibration beep), and never above [HearingTestConfig.maxDb]. */
    private fun ceiling(): Double = min(config.maxDb, max(config.calibrationDb + 10, maxPresented + 10))

    private fun answersAt(j: Int): List<TrialRecord> = log.filter { it.first == j }.map { it.second }

    private fun done(j: Int): Boolean {
        val n = count[j]
        if (n >= config.maxPerNode) return true
        val mine = answersAt(j)
        if (n >= 2) {
            val last = mine.takeLast(2)
            if (last.all { !it.heard && it.levelDb >= config.maxDb - 0.5 }) return true
            if (last.all { it.heard && it.levelDb <= config.minDb + 0.5 }) return true
        }
        if (n < MIN_PER_NODE || mine.none { it.heard }) return false
        return belief.sd(j) <= config.stopSdDb
    }

    fun result(): HearingTestResult {
        val g = guess()
        val points = nodes.indices.map { j ->
            val mine = answersAt(j)
            val flag = when {
                mine.isNotEmpty() && mine.none { it.heard } && mine.maxOf { it.levelDb } >= config.maxDb - 0.5 -> ThresholdFlag.NO_RESPONSE
                mine.isNotEmpty() && mine.all { it.heard } && mine.minOf { it.levelDb } <= config.minDb + 0.5 -> ThresholdFlag.BELOW_RANGE
                else -> ThresholdFlag.OK
            }
            ThresholdPoint(nodes[j].freq, nodes[j].ear, belief.mean[j], belief.sd(j), flag)
        }
        val inconsistent = log.count { (j, t) ->
            val p = Psychometric.pYes(belief.mean[j], t.levelDb, g, t.looks)
            if (t.heard) p < 0.1 else p > 0.9
        }
        return HearingTestResult(
            points = points.sortedWith(compareBy({ it.ear }, { it.freq })),
            catchTrials = catchTrials,
            falseAlarms = falseAlarms,
            retestDiffLeft = null,
            retestDiffRight = null,
            trials = trials,
            durationSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt(),
            warble = config.warble,
            calibrationDb = config.calibrationDb,
            trialLog = log.map { it.second },
            headphones = config.headphones,
            inconsistent = inconsistent,
        )
    }

    private companion object {
        const val MIN_PER_NODE = 2
        const val FAMILIARIZE_DB = 4.0
        const val SEARCH_DB = 12.0
    }
}
