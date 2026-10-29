package app.pureeq.hearing

import app.pureeq.dsp.Ear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

class HearingSessionTest {

    /** A listener with known thresholds who answers like a real person: a shallow slope, some guessing, the odd slip. */
    private class SimListener(val thresholds: Map<Pair<Ear, Double>, Double>, val rnd: Random) {
        val spread = rnd.nextDouble(1.5, 3.0)
        val falseAlarm = rnd.nextDouble().let { if (it < 0.7) 0.02 else if (it < 0.9) 0.06 else 0.15 }
        val lapse = rnd.nextDouble(0.01, 0.03)

        fun hears(p: Presentation): Boolean {
            if (p.isCatch) return rnd.nextDouble() < falseAlarm
            val t = thresholds.getValue(p.ear to p.freq)
            val prob = falseAlarm + (1 - falseAlarm - lapse) / (1 + exp(-(p.levelDb - t) / spread))
            return rnd.nextDouble() < prob
        }
    }

    /**
     * Calibration puts 1 kHz somewhere around -55 dBFS. Each ear gets the reference shape, a smooth
     * personal deviation (random walk across frequency) and ±3-4 dB of local earphone ripple.
     */
    private fun thresholdsFor(rnd: Random): Map<Pair<Ear, Double>, Double> {
        val m = HashMap<Pair<Ear, Double>, Double>()
        val offset = rnd.nextDouble(-8.0, 8.0)
        for (ear in listOf(Ear.LEFT, Ear.RIGHT)) {
            var dev = rnd.nextDouble(-3.0, 3.0)
            for (f in HearingTestConfig.BANDS.sorted()) {
                dev += rnd.nextDouble(-3.0, 4.0)
                val ripple = if (f <= 4000) rnd.nextDouble(-3.0, 3.0) else rnd.nextDouble(-4.0, 4.0)
                m[ear to f] = -55.0 + offset + ReferenceCurve.at(f, HeadphoneType.IN_EAR) - ReferenceCurve.at(1000.0, HeadphoneType.IN_EAR) + dev + ripple
            }
        }
        return m
    }

    private fun run(length: TestLength, seed: Int): Triple<HearingTestResult, Map<Pair<Ear, Double>, Double>, Double> {
        val rnd = Random(seed)
        val truth = thresholdsFor(rnd)
        val listener = SimListener(truth, rnd)
        val session = HearingSession(length.config(-40.0, true, HeadphoneType.IN_EAR), Random(seed + 1000))
        var seconds = 0.0
        while (true) {
            val p = session.next() ?: break
            val heard = listener.hears(p)
            // Gap, two pulses, then a "yes" during the beep or a "no" once it unlocks (Bluetooth).
            seconds += rnd.nextDouble(0.35, 0.6) + if (heard) rnd.nextDouble(0.45, 1.2) + 0.1 else 1.05 + rnd.nextDouble(0.3, 0.8)
            session.answer(heard)
        }
        return Triple(session.result(), truth, seconds)
    }

    @Test
    fun standardTestIsAccurateAndShort() {
        val errors = mutableListOf<Double>()
        var trials = 0
        var minutes = 0.0
        val runs = 40
        repeat(runs) { i ->
            val (result, truth, seconds) = run(TestLength.STANDARD, i)
            trials += result.trials
            minutes += seconds / 60
            for (pt in result.points) errors += pt.db - truth.getValue(pt.ear to pt.freq)
        }
        val meanAbs = errors.map { abs(it) }.average()
        val within5 = errors.count { abs(it) <= 5.0 }.toDouble() / errors.size
        val bias = errors.average()
        println("standard: mean |err| = %.2f dB, within ±5 dB = %.0f%%, bias = %.2f dB, trials/test = %d, %.2f min".format(meanAbs, within5 * 100, bias, trials / runs, minutes / runs))
        assertTrue("mean abs error $meanAbs", meanAbs < 2.6)
        assertTrue("within 5 dB $within5", within5 > 0.9)
        assertTrue("bias $bias", abs(bias) < 1.0)
        assertTrue("too many trials: ${trials / runs}", trials / runs < 75)
        assertTrue("too long: ${minutes / runs} min", minutes / runs < 2.3)
    }

    @Test
    fun quickIsShorterAndThoroughIsMorePrecise() {
        fun stats(l: TestLength): Pair<Double, Double> {
            var trials = 0
            val errors = mutableListOf<Double>()
            repeat(25) { i ->
                val (result, truth, _) = run(l, 500 + i)
                trials += result.trials
                for (pt in result.points) errors += pt.db - truth.getValue(pt.ear to pt.freq)
            }
            return trials / 25.0 to errors.map { abs(it) }.average()
        }
        val quick = stats(TestLength.QUICK)
        val thorough = stats(TestLength.THOROUGH)
        println("quick: %.0f trials, %.2f dB; thorough: %.0f trials, %.2f dB".format(quick.first, quick.second, thorough.first, thorough.second))
        assertTrue(quick.first < 50)
        assertTrue(thorough.first > quick.first + 25)
        assertTrue(thorough.second < quick.second)
    }

    @Test
    fun beliefUpdateMatchesExactPosterior() {
        // One node, one answer: the quadrature update must match brute-force integration.
        val prior = ThresholdPrior(listOf(Node(Ear.LEFT, 1000.0)), -40.0, HeadphoneType.IN_EAR)
        val b = ThresholdBelief(prior)
        val m0 = b.mean[0]
        val v0 = b.cov[0][0]
        b.update(0, -50.0, heard = true, guess = 0.05)
        var w0 = 0.0
        var w1 = 0.0
        var w2 = 0.0
        var th = m0 - 10 * sqrt(v0)
        while (th <= m0 + 10 * sqrt(v0)) {
            val w = exp(-0.5 * (th - m0) * (th - m0) / v0) * Psychometric.pYes(th, -50.0, 0.05)
            w0 += w
            w1 += w * th
            w2 += w * th * th
            th += 0.01
        }
        val mean = w1 / w0
        assertEquals(mean, b.mean[0], 0.05)
        assertEquals(w2 / w0 - mean * mean, b.cov[0][0], 0.1)
    }

    @Test
    fun anAnswerInformsTheOtherEarAndNeighbouringBands() {
        val nodes = listOf(Node(Ear.LEFT, 1000.0), Node(Ear.RIGHT, 1000.0), Node(Ear.LEFT, 2000.0), Node(Ear.LEFT, 8000.0))
        val b = ThresholdBelief(ThresholdPrior(nodes, -40.0, HeadphoneType.IN_EAR))
        val before = DoubleArray(nodes.size) { b.sd(it) }
        repeat(4) { b.update(0, b.mean[0], heard = it % 2 == 0, guess = 0.05) }
        assertTrue(b.sd(1) < before[1] - 2)
        assertTrue(b.sd(2) < before[2] - 2)
        // The far band learns less than the near one.
        assertTrue(before[3] - b.sd(3) < before[2] - b.sd(2))
    }

    @Test
    fun deterministicListenerIsPinnedDown() {
        val truth = mapOf(
            (Ear.LEFT to 1000.0) to -57.0, (Ear.RIGHT to 1000.0) to -52.0,
            (Ear.LEFT to 4000.0) to -63.0, (Ear.RIGHT to 4000.0) to -40.0,
        )
        val session = HearingSession(HearingTestConfig(listOf(1000.0, 4000.0), catchTrials = false, stopSdDb = 1.5, maxPerNode = 12), Random(1))
        while (true) {
            val p = session.next() ?: break
            session.answer(p.levelDb >= truth.getValue(p.ear to p.freq))
        }
        for (pt in session.result().points) {
            assertEquals("${pt.ear} ${pt.freq}", truth.getValue(pt.ear to pt.freq), pt.db, 3.0)
            assertEquals(ThresholdFlag.OK, pt.flag)
        }
    }

    @Test
    fun neverJumpsMoreThan10dBAboveWhatWasPlayed() {
        val session = HearingSession(TestLength.STANDARD.config(-40.0, true, HeadphoneType.IN_EAR), Random(7))
        var loudest = -30.0
        while (true) {
            val p = session.next() ?: break
            if (!p.isCatch) {
                assertTrue("${p.levelDb} after $loudest", p.levelDb <= loudest + 10.0 + 1e-9)
                loudest = maxOf(loudest, p.levelDb)
            }
            session.answer(false)
        }
    }

    @Test
    fun saysYesToEverythingIsFlaggedUnreliable() {
        val session = HearingSession(HearingTestConfig(catchTrials = true), Random(3))
        while (true) {
            session.next() ?: break
            session.answer(true)
        }
        val r = session.result()
        assertTrue(r.catchTrials > 0)
        assertEquals(1.0, r.falseAlarmRate, 1e-9)
        assertEquals(Reliability.POOR, r.reliability)
    }

    @Test
    fun hearsNothingIsFlaggedNoResponse() {
        val session = HearingSession(HearingTestConfig(listOf(8000.0), catchTrials = false), Random(4))
        while (true) {
            session.next() ?: break
            session.answer(false)
        }
        val r = session.result()
        assertTrue(r.points.all { it.flag == ThresholdFlag.NO_RESPONSE })
        // The estimate sits above the loudest level played.
        assertTrue(r.points.all { it.db > -3.0 })
    }

    @Test
    fun replayingTheLogReproducesTheResult() {
        val (result, _, _) = run(TestLength.STANDARD, 11)
        val b = ThresholdBelief.of(result, HeadphoneType.IN_EAR)
        for (pt in result.points) {
            val i = b.prior.nodes.indexOf(Node(pt.ear, pt.freq))
            assertEquals(pt.db, b.mean[i], 1e-6)
            assertEquals(pt.sd, b.sd(i), 1e-6)
        }
    }

    @Test
    fun referenceCurveMatchesTheTables() {
        assertEquals(0.0, ReferenceCurve.at(1000.0, HeadphoneType.IN_EAR), 1e-9)
        assertEquals(-4.1, ReferenceCurve.at(4000.0, HeadphoneType.IN_EAR), 1e-9)
        assertEquals(9.0, ReferenceCurve.at(8000.0, HeadphoneType.OVER_EAR), 1e-9)
        assertEquals(8.6, ReferenceCurve.at(250.0, HeadphoneType.IN_EAR), 1e-9)
        // Linear in log frequency between table points (2 and 2.5 kHz), held beyond the ends.
        assertEquals((-0.2 + -1.2) / 2, ReferenceCurve.at(sqrt(2000.0 * 2500.0), HeadphoneType.IN_EAR), 1e-9)
        assertEquals(50.4, ReferenceCurve.at(20000.0, HeadphoneType.IN_EAR), 1e-9)
    }

    @Test
    fun headphoneTypeGuesses() {
        assertEquals(HeadphoneType.IN_EAR, HeadphoneType.guess("Galaxy Buds2 Pro"))
        assertEquals(HeadphoneType.IN_EAR, HeadphoneType.guess("My AirPods Pro"))
        assertEquals(HeadphoneType.OVER_EAR, HeadphoneType.guess("WH-1000XM5"))
        assertEquals(HeadphoneType.OVER_EAR, HeadphoneType.guess("AirPods Max"))
        assertEquals(HeadphoneType.IN_EAR, HeadphoneType.guess("WF-1000XM5"))
        assertEquals(HeadphoneType.IN_EAR, HeadphoneType.guess(null))
    }
}
