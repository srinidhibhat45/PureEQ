package app.pureeq.hearing

import app.pureeq.dsp.Curves
import app.pureeq.dsp.Ear
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Normal hearing threshold by frequency: ISO 226:2003 free-field values in dB SPL, with the
 * ISO 389-7 audiometric points at 3 and 6 kHz. These are binaural free-field values; only the
 * shape is used, so the constant monaural/binaural offset doesn't matter. A neutral
 * (diffuse-field or Harman-style) headphone reproduces this shape at the eardrum, so a measured
 * threshold minus this curve is "how much less well you hear this band through these
 * headphones": ear and earphone together, which is what an EQ can correct.
 */
object ReferenceThreshold {
    private val hz = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0,
        500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3000.0, 3150.0, 4000.0, 5000.0,
        6000.0, 6300.0, 8000.0, 10000.0, 12500.0,
    )
    private val db = doubleArrayOf(
        78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4, 11.4, 8.6, 6.2,
        4.4, 3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -5.8, -6.0, -5.4, -1.5,
        4.3, 6.0, 12.6, 13.9, 12.3,
    )
    private val logHz = DoubleArray(hz.size) { log2(hz[it]) }

    fun at(freq: Double): Double = Curves.pchip(logHz, db, log2(freq))
}

enum class ThresholdFlag { OK, NO_RESPONSE, BELOW_RANGE }

/** One answer: the level played, whether it was heard, and whether it came after a "no" (an ascending run). */
data class TrialResponse(val levelDb: Double, val heard: Boolean, val ascending: Boolean)

/**
 * The staircase for one ear at one frequency: modified Hughson-Westlake (ASHA 2005).
 * Starts clearly audible, steps 10 dB down after each "yes" and 5 dB up after each "no",
 * and stops once one level has been heard on two ascending runs.
 */
class Track(
    val ear: Ear,
    val freq: Double,
    startDb: Double,
    private val maxDb: Double,
    private val minDb: Double,
    private val stopSdDb: Double = 3.0,
) {
    var levelDb: Double = startDb.coerceIn(minDb, maxDb)
        private set
    val responses = mutableListOf<TrialResponse>()
    var done = false
        private set

    private var lastHeard: Boolean? = null
    private val ascendingHits = HashMap<Long, Int>()
    private var noAtMax = 0
    private var yesAtMin = 0

    fun record(heard: Boolean) {
        if (done) return
        val ascending = lastHeard == false
        responses += TrialResponse(levelDb, heard, ascending)
        lastHeard = heard
        if (heard) {
            if (ascending) {
                val key = (levelDb * 10).roundToLong()
                val hits = (ascendingHits[key] ?: 0) + 1
                ascendingHits[key] = hits
                if (hits >= 2) {
                    done = true
                    return
                }
            }
            if (levelDb <= minDb + 1e-9 && ++yesAtMin >= 2) {
                done = true
                return
            }
            levelDb = max(minDb, levelDb - 10.0)
        } else {
            if (levelDb >= maxDb - 1e-9 && ++noAtMax >= 2) {
                done = true
                return
            }
            levelDb = min(maxDb, levelDb + 5.0)
        }
        if (responses.size >= MAX_TRIALS) {
            done = true
            return
        }
        // Stop early once the answers pin the threshold down: at least one level heard coming up
        // from below, two misses, and a posterior spread under stopSdDb.
        if (responses.size >= 4 && responses.count { !it.heard } >= 2 && responses.any { it.ascending && it.heard }) {
            if (ThresholdEstimator.estimate(responses, 0.05, maxDb).sd <= stopSdDb) done = true
        }
    }

    fun estimate(falseAlarmRate: Double): ThresholdEstimate =
        ThresholdEstimator.estimate(responses, falseAlarmRate, maxDb)

    companion object {
        const val MAX_TRIALS = 16
    }
}

data class ThresholdEstimate(val db: Double, val sd: Double, val flag: ThresholdFlag)

/**
 * Maximum-likelihood threshold from every answer in a track, using a yes/no psychometric
 * function whose floor is the listener's false-alarm rate. Finer than the 5 dB staircase step.
 */
object ThresholdEstimator {
    /** Logistic spread: about 10 % more "yes" per dB at threshold, typical for pulsed tones. */
    const val SPREAD_DB = 2.5
    const val LAPSE = 0.02

    fun estimate(responses: List<TrialResponse>, falseAlarmRate: Double, maxDb: Double): ThresholdEstimate {
        if (responses.isEmpty()) return ThresholdEstimate(maxDb, 20.0, ThresholdFlag.NO_RESPONSE)
        if (responses.none { it.heard }) return ThresholdEstimate(maxDb + 5.0, 10.0, ThresholdFlag.NO_RESPONSE)
        if (responses.none { !it.heard }) {
            return ThresholdEstimate(responses.minOf { it.levelDb } - 5.0, 10.0, ThresholdFlag.BELOW_RANGE)
        }
        val guess = falseAlarmRate.coerceIn(0.01, 0.2)
        val lowestYes = responses.filter { it.heard }.minOf { it.levelDb }
        val highestNo = responses.filter { !it.heard }.maxOf { it.levelDb }
        val priorMean = classic(responses) ?: ((lowestYes + highestNo) / 2)
        val priorSd = 15.0
        val lo = responses.minOf { it.levelDb } - 15.0
        val hi = responses.maxOf { it.levelDb } + 15.0
        val step = 0.25
        val n = ((hi - lo) / step).toInt() + 1
        val logPost = DoubleArray(n)
        var best = Double.NEGATIVE_INFINITY
        for (k in 0 until n) {
            val theta = lo + k * step
            var ll = -0.5 * ((theta - priorMean) / priorSd).let { it * it }
            for (r in responses) {
                val p = guess + (1 - guess - LAPSE) / (1 + exp(-(r.levelDb - theta) / SPREAD_DB))
                ll += if (r.heard) ln(p) else ln(1 - p)
            }
            logPost[k] = ll
            if (ll > best) best = ll
        }
        var sw = 0.0
        var mean = 0.0
        for (k in 0 until n) {
            val w = exp(logPost[k] - best)
            sw += w
            mean += w * (lo + k * step)
        }
        mean /= sw
        var variance = 0.0
        for (k in 0 until n) {
            val d = lo + k * step - mean
            variance += exp(logPost[k] - best) * d * d
        }
        return ThresholdEstimate(mean, sqrt(variance / sw), ThresholdFlag.OK)
    }

    /** Classic rule: the lowest level heard on at least two ascending presentations. */
    fun classic(responses: List<TrialResponse>): Double? {
        val hits = responses.filter { it.ascending && it.heard }.groupingBy { (it.levelDb * 10).roundToLong() }.eachCount()
        return hits.filter { it.value >= 2 }.keys.minOrNull()?.let { it / 10.0 }
    }
}

/** Settings for one hearing test. Levels are peak dBFS. */
data class HearingTestConfig(
    /** Frequencies to test, any order; the session orders them like a clinical audiogram. */
    val frequencies: List<Double>,
    /** Level the listener set as "soft but clearly audible" at 1 kHz. */
    val calibrationDb: Double = -40.0,
    val maxDb: Double = -3.0,
    val minDb: Double = -110.0,
    val retest1k: Boolean = false,
    val warble: Boolean = true,
    /** Silent catch trials, one every 7-12 trials, to measure guessing. */
    val catchTrials: Boolean = true,
    /** A staircase ends once its threshold is known to about this many dB (posterior SD). */
    val stopSdDb: Double = 3.0,
    /** How far above the predicted threshold each new frequency starts. */
    val startMarginDb: Double = 10.0,
) {
    companion object {
        val QUICK = listOf(250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)
        val STANDARD = listOf(250.0, 500.0, 1000.0, 2000.0, 3000.0, 4000.0, 6000.0, 8000.0)
        val THOROUGH = listOf(125.0, 250.0, 500.0, 1000.0, 2000.0, 3000.0, 4000.0, 6000.0, 8000.0, 10000.0, 12000.0)
    }
}

/** Test lengths offered in the app. Times assume about 2 s per answer, as simulated. */
enum class TestLength(val label: String, val detail: String, val frequencies: List<Double>, val retest1k: Boolean, val stopSdDb: Double) {
    QUICK("Quick", "6 bands per ear · about 4 min", HearingTestConfig.QUICK, false, 3.5),
    STANDARD("Standard", "8 bands per ear · about 5 min", HearingTestConfig.STANDARD, false, 3.0),
    THOROUGH("Thorough", "11 bands, 1 kHz re-checked · about 8 min", HearingTestConfig.THOROUGH, true, 2.5),
    ;

    fun config(calibrationDb: Double, warble: Boolean) = HearingTestConfig(
        frequencies = frequencies,
        calibrationDb = calibrationDb,
        retest1k = retest1k,
        warble = warble,
        stopSdDb = stopSdDb,
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
    val retestDiffLeft: Double?,
    val retestDiffRight: Double?,
    val trials: Int,
    val durationSec: Int,
    val warble: Boolean,
    val calibrationDb: Double,
) {
    val falseAlarmRate: Double get() = if (catchTrials == 0) 0.0 else falseAlarms.toDouble() / catchTrials

    fun pointsFor(ear: Ear): List<ThresholdPoint> = points.filter { it.ear == ear }.sortedBy { it.freq }

    val reliability: Reliability
        get() {
            val retest = listOfNotNull(retestDiffLeft, retestDiffRight).maxOfOrNull { abs(it) } ?: 0.0
            val fa = falseAlarmRate
            return when {
                (fa > 0.34 && falseAlarms >= 2) || retest > 15 -> Reliability.POOR
                fa > 0.2 || retest > 10 -> Reliability.FAIR
                else -> Reliability.GOOD
            }
        }
}

/**
 * Runs a full test: both ears at each frequency, ears interleaved at random so the listener
 * can't predict the side, a silent catch trial every 7-12 trials, and (optionally) 1 kHz
 * retested at the end. Order follows clinical practice: 1 kHz upwards, then below 1 kHz downwards.
 * Finally any band that disagrees with both neighbours by [OUTLIER_DB] or more is re-checked,
 * because real hearing curves are smooth and a single odd point is usually a lucky or missed
 * answer. All answers for a band are then pooled into one estimate.
 */
class HearingSession(val config: HearingTestConfig, private val rnd: Random = Random.Default) {

    private data class Block(val freq: Double, val retest: Boolean, val onlyEar: Ear? = null)

    private val blocks: MutableList<Block> = buildList {
        val f = config.frequencies.distinct()
        f.filter { it >= 1000.0 }.sorted().forEach { add(Block(it, false)) }
        f.filter { it < 1000.0 }.sortedDescending().forEach { add(Block(it, false)) }
        if (config.retest1k && 1000.0 in f) add(Block(1000.0, true))
    }.toMutableList()
    private val plannedBlocks = blocks.size
    private var consistencyChecked = false
    private val finished = mutableListOf<Pair<Block, Track>>()
    private var blockIndex = 0
    private var blockTracks: List<Track> = emptyList()
    private var current: Presentation? = null
    private var currentTrack: Track? = null
    private var lastEar: Ear? = null
    private var sameEarRun = 0
    private var nextCatchAt = 3 + rnd.nextInt(CATCH_EVERY_MIN, CATCH_EVERY_MAX + 1)
    private val startedAt = System.currentTimeMillis()

    var catchTrials = 0
        private set
    var falseAlarms = 0
        private set
    var trials = 0
        private set

    val currentFrequency: Double? get() = blocks.getOrNull(blockIndex)?.freq
    val blockNumber: Int get() = (blockIndex + 1).coerceAtMost(blocks.size)
    val blockCount: Int get() = blocks.size
    val isRetest: Boolean get() = blocks.getOrNull(blockIndex)?.retest == true

    /** The next presentation, or null when the test is complete. Call [answer] before calling again. */
    fun next(): Presentation? {
        current?.let { return it }
        while (true) {
            if (blockIndex >= blocks.size) {
                if (consistencyChecked) return null
                consistencyChecked = true
                blocks += consistencyRetests()
                if (blockIndex >= blocks.size) return null
            }
            if (blockTracks.isEmpty()) blockTracks = startBlock(blocks[blockIndex])
            val active = blockTracks.filter { !it.done }
            if (active.isNotEmpty()) {
                val block = blocks[blockIndex]
                if (config.catchTrials && trials >= nextCatchAt) {
                    nextCatchAt = trials + 1 + rnd.nextInt(CATCH_EVERY_MIN, CATCH_EVERY_MAX + 1)
                    val ear = if (rnd.nextBoolean()) Ear.LEFT else Ear.RIGHT
                    currentTrack = null
                    return Presentation(ear, block.freq, active.first().levelDb, true).also { current = it }
                }
                val track = pickTrack(active)
                currentTrack = track
                return Presentation(track.ear, track.freq, track.levelDb, false).also { current = it }
            }
            blockTracks.forEach { finished += blocks[blockIndex] to it }
            blockTracks = emptyList()
            blockIndex++
        }
    }

    fun answer(heard: Boolean) {
        val p = current ?: return
        current = null
        trials++
        if (p.isCatch) {
            catchTrials++
            if (heard) falseAlarms++
            return
        }
        currentTrack?.record(heard)
        if (p.ear == lastEar) sameEarRun++ else sameEarRun = 1
        lastEar = p.ear
    }

    /** 0..1. The planned bands fill 95 %; any consistency re-checks fill the rest. */
    val progress: Float
        get() {
            val partial = blockTracks.sumOf { if (it.done) 1.0 else min(it.responses.size / 7.0, 0.9) }
            val plannedTracks = blocks.take(plannedBlocks).sumOf { tracksIn(it) }
            if (plannedTracks == 0) return 1f
            return if (blockIndex < plannedBlocks) {
                (0.95 * (finished.size + partial) / plannedTracks).toFloat()
            } else {
                val extra = blocks.drop(plannedBlocks).sumOf { tracksIn(it) }.coerceAtLeast(1)
                (0.95 + 0.05 * (finished.size - plannedTracks + partial) / extra).toFloat().coerceIn(0f, 1f)
            }
        }

    val isComplete: Boolean get() = consistencyChecked && blockIndex >= blocks.size

    private fun tracksIn(b: Block) = if (b.onlyEar != null) 1 else 2

    /** Every answer for one ear and frequency pooled into one maximum-likelihood estimate. */
    private fun pooled(ear: Ear, freq: Double, fa: Double): ThresholdEstimate? {
        val tracks = finished.filter { it.second.ear == ear && it.first.freq == freq }.map { it.second }
        if (tracks.isEmpty()) return null
        return ThresholdEstimator.estimate(tracks.flatMap { it.responses }, fa, config.maxDb)
    }

    /**
     * Bands whose relative level (threshold minus the normal-hearing curve) is [OUTLIER_DB] or more
     * away from the line between their two neighbours. Edge bands are left alone: high frequencies
     * often fall away steeply for real.
     */
    private fun consistencyRetests(): List<Block> {
        val fa = falseAlarmRate()
        val out = mutableListOf<Pair<Double, Block>>()
        for (ear in listOf(Ear.LEFT, Ear.RIGHT)) {
            val freqs = config.frequencies.distinct().sorted()
            val rel = freqs.map { f -> pooled(ear, f, fa)?.takeIf { it.flag == ThresholdFlag.OK }?.let { it.db - ReferenceThreshold.at(f) } }
            for (i in 1 until freqs.size - 1) {
                val a = rel[i - 1] ?: continue
                val b = rel[i] ?: continue
                val c = rel[i + 1] ?: continue
                val t = log2(freqs[i] / freqs[i - 1]) / log2(freqs[i + 1] / freqs[i - 1])
                val dev = abs(b - (a + (c - a) * t))
                if (dev >= OUTLIER_DB) out += dev to Block(freqs[i], retest = true, onlyEar = ear)
            }
        }
        return out.sortedByDescending { it.first }.take(MAX_CONSISTENCY_RETESTS).map { it.second }
    }

    private fun falseAlarmRate() = if (catchTrials == 0) 0.0 else falseAlarms.toDouble() / catchTrials

    fun result(): HearingTestResult {
        val fa = falseAlarmRate()
        val points = mutableListOf<ThresholdPoint>()
        var retestL: Double? = null
        var retestR: Double? = null
        for (ear in listOf(Ear.LEFT, Ear.RIGHT)) {
            for (f in config.frequencies.distinct().sorted()) {
                val est = pooled(ear, f, fa) ?: continue
                points += ThresholdPoint(f, ear, est.db, est.sd, est.flag)
            }
            // The planned 1 kHz re-check doubles as a test-retest reliability measure.
            if (config.retest1k) {
                val runs = finished.filter { it.second.ear == ear && it.first.freq == 1000.0 && it.first.onlyEar == null }
                    .map { it.second.estimate(fa) }
                if (runs.size == 2 && runs.all { it.flag == ThresholdFlag.OK }) {
                    val diff = runs[1].db - runs[0].db
                    if (ear == Ear.LEFT) retestL = diff else retestR = diff
                }
            }
        }
        return HearingTestResult(
            points = points.sortedWith(compareBy({ it.ear }, { it.freq })),
            catchTrials = catchTrials,
            falseAlarms = falseAlarms,
            retestDiffLeft = retestL,
            retestDiffRight = retestR,
            trials = trials,
            durationSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt(),
            warble = config.warble,
            calibrationDb = config.calibrationDb,
        )
    }

    private fun startBlock(block: Block): List<Track> = (block.onlyEar?.let { listOf(it) } ?: listOf(Ear.LEFT, Ear.RIGHT)).map { ear ->
        Track(ear, block.freq, startLevel(ear, block), config.maxDb, config.minDb, config.stopSdDb)
    }

    /**
     * Start about 15 dB above where this ear's threshold should be, predicted from the nearest
     * frequency already measured plus the normal-hearing curve; the first frequency starts at
     * the listener's "soft but clear" calibration level.
     */
    private fun startLevel(ear: Ear, block: Block): Double {
        val fa = falseAlarmRate()
        val measured = finished.filter { it.second.ear == ear && !it.first.retest }
        if (block.retest) {
            val first = measured.firstOrNull { it.first.freq == block.freq }?.second?.estimate(fa)
            if (first != null && first.flag == ThresholdFlag.OK) {
                // A consistency re-check starts clearly above both the odd result and what its neighbours predict.
                val neighbours = measured.filter { it.first.freq != block.freq }
                    .sortedBy { abs(log2(it.first.freq / block.freq)) }.take(2)
                    .map { it.second.estimate(fa).db - ReferenceThreshold.at(it.first.freq) + ReferenceThreshold.at(block.freq) }
                val predicted = if (block.onlyEar != null && neighbours.isNotEmpty()) neighbours.average() else first.db
                return clampStart(maxOf(first.db, predicted) + 10)
            }
        }
        val nearest = measured.minByOrNull { abs(log2(it.first.freq / block.freq)) }
        if (nearest != null) {
            val est = nearest.second.estimate(fa)
            val predicted = est.db + ReferenceThreshold.at(block.freq) - ReferenceThreshold.at(nearest.first.freq)
            return clampStart(predicted + config.startMarginDb)
        }
        return clampStart(config.calibrationDb + ReferenceThreshold.at(block.freq) - ReferenceThreshold.at(1000.0))
    }

    /** Whole-dB start levels keep every later 5/10 dB step exact. */
    private fun clampStart(db: Double) = db.coerceIn(config.minDb + 20, config.maxDb).roundToInt().toDouble()

    private fun pickTrack(active: List<Track>): Track {
        if (active.size == 1) return active[0]
        if (sameEarRun >= 2) active.firstOrNull { it.ear != lastEar }?.let { return it }
        return active[rnd.nextInt(active.size)]
    }

    private companion object {
        const val CATCH_EVERY_MIN = 7
        const val CATCH_EVERY_MAX = 12
        const val OUTLIER_DB = 9.0
        const val MAX_CONSISTENCY_RETESTS = 4
    }
}
