package app.pureeq.hearing

import app.pureeq.dsp.Ear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

class HearingSessionTest {

    /** A listener with known thresholds who answers like a real person: probabilistic, sometimes guessing. */
    private class SimListener(
        val thresholds: Map<Pair<Ear, Double>, Double>,
        val falseAlarm: Double,
        val rnd: Random,
        val spread: Double = 2.0,
    ) {
        fun hears(p: Presentation): Boolean {
            if (p.isCatch) return rnd.nextDouble() < falseAlarm
            val t = thresholds.getValue(p.ear to p.freq)
            val prob = falseAlarm + (1 - falseAlarm - 0.01) / (1 + exp(-(p.levelDb - t) / spread))
            return rnd.nextDouble() < prob
        }
    }

    /**
     * Calibration puts 1 kHz near -55 dBFS. Each ear gets the normal-hearing shape, a smooth personal
     * audiogram (random walk across frequency) and ±3 dB of earphone ripple.
     */
    private fun thresholdsFor(freqs: List<Double>, rnd: Random): Map<Pair<Ear, Double>, Double> {
        val m = HashMap<Pair<Ear, Double>, Double>()
        for (ear in listOf(Ear.LEFT, Ear.RIGHT)) {
            var dev = rnd.nextDouble(-5.0, 5.0)
            for (f in freqs.sorted()) {
                dev += rnd.nextDouble(-4.0, 6.0)
                m[ear to f] = -55.0 + ReferenceThreshold.at(f) - ReferenceThreshold.at(1000.0) + dev + rnd.nextDouble(-3.0, 3.0)
            }
        }
        return m
    }

    @Test
    fun estimatesAreAccurateForARealisticListener() {
        val errors = mutableListOf<Double>()
        var totalTrials = 0
        repeat(40) { run ->
            val rnd = Random(run)
            val freqs = HearingTestConfig.STANDARD
            val truth = thresholdsFor(freqs, rnd)
            val listener = SimListener(truth, 0.03, rnd)
            val session = HearingSession(TestLength.STANDARD.config(-40.0, true), Random(run + 1000))
            while (true) {
                val p = session.next() ?: break
                session.answer(listener.hears(p))
            }
            val result = session.result()
            totalTrials += result.trials
            for (pt in result.points) errors += pt.db - truth.getValue(pt.ear to pt.freq)
        }
        val meanAbs = errors.map { abs(it) }.average()
        val within5 = errors.count { abs(it) <= 5.0 }.toDouble() / errors.size
        val bias = errors.average()
        println("mean |err| = %.2f dB, within ±5 dB = %.0f%%, bias = %.2f dB, trials/test = %d".format(meanAbs, within5 * 100, bias, totalTrials / 40))
        assertTrue("mean abs error $meanAbs", meanAbs < 2.5)
        assertTrue("within 5 dB $within5", within5 > 0.92)
        assertTrue("bias $bias", abs(bias) < 1.0)
        assertTrue("too many trials: ${totalTrials / 40}", totalTrials / 40 < 150)
    }

    @Test
    fun deterministicListenerLandsWithinHalfAStep() {
        val freqs = listOf(1000.0, 4000.0)
        val truth = mapOf(
            (Ear.LEFT to 1000.0) to -57.0, (Ear.RIGHT to 1000.0) to -52.0,
            (Ear.LEFT to 4000.0) to -63.0, (Ear.RIGHT to 4000.0) to -40.0,
        )
        val session = HearingSession(HearingTestConfig(freqs, catchTrials = false, stopSdDb = 0.0), Random(1))
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
    fun saysYesToEverythingIsFlaggedUnreliable() {
        val session = HearingSession(HearingTestConfig(HearingTestConfig.QUICK, catchTrials = true), Random(3))
        while (true) {
            session.next() ?: break
            session.answer(true)
        }
        val r = session.result()
        assertTrue(r.catchTrials > 0)
        assertEquals(1.0, r.falseAlarmRate, 1e-9)
        assertEquals(Reliability.POOR, r.reliability)
        assertTrue(r.points.all { it.flag == ThresholdFlag.BELOW_RANGE })
    }

    @Test
    fun hearsNothingIsFlaggedNoResponse() {
        val session = HearingSession(HearingTestConfig(listOf(8000.0), catchTrials = false), Random(4))
        while (true) {
            session.next() ?: break
            session.answer(false)
        }
        assertTrue(session.result().points.all { it.flag == ThresholdFlag.NO_RESPONSE })
    }

    @Test
    fun referenceCurveMatchesIsoAnchors() {
        assertEquals(2.4, ReferenceThreshold.at(1000.0), 1e-9)
        assertEquals(-5.4, ReferenceThreshold.at(4000.0), 1e-9)
        assertEquals(12.6, ReferenceThreshold.at(8000.0), 1e-9)
        assertEquals(-5.8, ReferenceThreshold.at(3000.0), 1e-9)
        assertEquals(4.3, ReferenceThreshold.at(6000.0), 1e-9)
    }
}
