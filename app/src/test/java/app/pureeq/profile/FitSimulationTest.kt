package app.pureeq.profile

import app.pureeq.dsp.Curves
import app.pureeq.dsp.Ear
import app.pureeq.dsp.FreqGrid
import app.pureeq.hearing.HeadphoneType
import app.pureeq.hearing.HearingSession
import app.pureeq.hearing.HearingTestConfig
import app.pureeq.hearing.HearingTestResult
import app.pureeq.hearing.Presentation
import app.pureeq.hearing.ReferenceCurve
import app.pureeq.hearing.TestLength
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Whole-pipeline simulations: a simulated listener takes the full Bayesian listening check, and
 * the fitted EQ is compared with what their ears call for. These pin the fitting rule's behaviour
 * on the cases the research flagged: spurious colouration for normal listeners, the tilt a sloping
 * loss gets, and differences between the ears.
 */
class FitSimulationTest {

    /** Answers like [app.pureeq.hearing.HearingSessionTest]'s listener: shallow slope, some guessing, the odd slip. */
    private class SimListener(val thresholds: Map<Pair<Ear, Double>, Double>, val rnd: Random) {
        val spread = rnd.nextDouble(1.5, 3.0)
        val falseAlarm = rnd.nextDouble().let { if (it < 0.7) 0.02 else if (it < 0.9) 0.06 else 0.15 }
        val lapse = rnd.nextDouble(0.01, 0.03)

        fun hears(p: Presentation): Boolean {
            if (p.isCatch) return rnd.nextDouble() < falseAlarm
            val t = thresholds.getValue(p.ear to p.freq)
            return rnd.nextDouble() < falseAlarm + (1 - falseAlarm - lapse) / (1 + exp(-(p.levelDb - t) / spread))
        }
    }

    private val type = HeadphoneType.IN_EAR

    /** Thresholds = reference + [dev] (dB, positive = needs more level) + small local ripple, around −55 dBFS at 1 kHz. */
    private fun test(seed: Int, ripple: Double = 2.0, dev: (Ear, Double) -> Double): HearingTestResult {
        val rnd = Random(seed)
        val offset = rnd.nextDouble(-8.0, 8.0)
        val truth = HashMap<Pair<Ear, Double>, Double>()
        for (ear in listOf(Ear.LEFT, Ear.RIGHT)) for (f in HearingTestConfig.BANDS) {
            truth[ear to f] = -55.0 + offset + ReferenceCurve.at(f, type) - ReferenceCurve.at(1000.0, type) + dev(ear, f) + rnd.nextDouble(-ripple, ripple)
        }
        val listener = SimListener(truth, rnd)
        val session = HearingSession(TestLength.STANDARD.config(-40.0, true, type), Random(seed + 1000))
        while (true) session.answer(listener.hears(session.next() ?: break))
        return session.result()
    }

    private fun rms(c: DoubleArray, from: Double = 300.0, to: Double = 12000.0): Double {
        val idx = FreqGrid.hz.indices.filter { FreqGrid.hz[it] in from..to }
        return sqrt(idx.sumOf { c[it] * c[it] } / idx.size)
    }

    private fun at(c: DoubleArray, f: Double) = Curves.sample(c, f)

    /** RMS after removing the mean: changes in tonal balance, not overall level (the volume takes that). */
    private fun tonal(c: DoubleArray, from: Double = 300.0, to: Double = 12000.0): Double {
        val idx = FreqGrid.hz.indices.filter { FreqGrid.hz[it] in from..to }
        val m = idx.sumOf { c[it] } / idx.size
        return sqrt(idx.sumOf { (c[it] - m) * (c[it] - m) } / idx.size)
    }

    private val strength = HearingCompensation.DEFAULT_STRENGTH
    private val runs = 30

    @Test
    fun normalListenersGetLittleColouration() {
        var common = 0.0
        var shape = 0.0
        var lr = 0.0
        repeat(runs) { i ->
            val c = HearingCompensation.curves(test(100 + i) { _, _ -> 0.0 }, type, strength)
            common += rms(Curves.average(c.left, c.right))
            shape += tonal(Curves.average(c.left, c.right))
            lr += rms(DoubleArray(FreqGrid.SIZE) { (c.left[it] - c.right[it]) / 2 })
        }
        println("normal: common RMS %.2f dB (tonal %.2f), left/right half-difference RMS %.2f dB".format(common / runs, shape / runs, lr / runs))
        // 1.4's fit: tonal 0.42 dB, left/right 0.39 dB.
        assertTrue("tonal ${shape / runs}", shape / runs < 0.5)
        assertTrue("left/right ${lr / runs}", lr / runs < 0.2)
    }

    @Test
    fun aSlopingLossGetsAModerateTilt() {
        val loss = mapOf(500.0 to 10.0, 1000.0 to 10.0, 2000.0 to 20.0, 3000.0 to 30.0, 4000.0 to 35.0, 6000.0 to 40.0, 8000.0 to 45.0)
        var tilt = 0.0
        var low = 0.0
        repeat(runs) { i ->
            val c = HearingCompensation.curves(test(200 + i) { _, f -> loss.getValue(f) }, type, strength)
            val avg = Curves.average(c.left, c.right)
            tilt += at(avg, 4000.0) - at(avg, 1000.0)
            low += at(avg, 700.0)
        }
        println("sloping 10→35 dB (1→4 kHz): EQ 1→4 kHz tilt %.1f dB, gain at 700 Hz %.1f dB".format(tilt / runs, low / runs))
        // 1.4's fit: an 11.3 dB tilt and −4.2 dB at 700 Hz, where this listener hears fine.
        assertTrue("tilt ${tilt / runs}", tilt / runs in 6.0..9.5)
        assertTrue("700 Hz ${low / runs}", low / runs > -1.5)
    }

    @Test
    fun oneSidedTrebleLossReachesThatEar() {
        val left = mapOf(500.0 to 0.0, 1000.0 to 0.0, 2000.0 to 0.0, 3000.0 to 10.0, 4000.0 to 20.0, 6000.0 to 20.0, 8000.0 to 20.0)
        val bias = DoubleArray(3)
        var eqDiff = 0.0
        repeat(runs) { i ->
            val r = test(300 + i) { ear, f -> if (ear == Ear.LEFT) left.getValue(f) else 0.0 }
            val d = HearingCompensation.deviations(r, type)
            listOf(4000.0, 6000.0, 8000.0).forEachIndexed { k, f -> bias[k] += (at(d.left, f) - at(d.right, f)) - 20.0 }
            val c = HearingCompensation.curves(r, type, strength)
            eqDiff += at(c.left, 6000.0) - at(c.right, 6000.0)
        }
        println("left treble +20 dB: smooth L−R bias at 4/6/8 kHz %.1f / %.1f / %.1f dB, EQ L−R at 6 kHz %.1f dB".format(bias[0] / runs, bias[1] / runs, bias[2] / runs, eqDiff / runs))
        // 1.4's fit: −10.5 / −8.6 / −9.4 dB, EQ 5.6 dB (the treble boost cap limits the EQ either way).
        assertTrue("6 kHz bias ${bias[1] / runs}", bias[1] / runs > -7.0)
        assertTrue("EQ ${eqDiff / runs}", eqDiff / runs > 5.0)
    }

    @Test
    fun aFlatDifferenceBetweenTheEarsIsSeen() {
        var bias = 0.0
        var eqDiff = 0.0
        repeat(runs) { i ->
            val r = test(400 + i) { ear, _ -> if (ear == Ear.LEFT) 15.0 else 0.0 }
            val d = HearingCompensation.deviations(r, type)
            bias += listOf(1000.0, 2000.0, 4000.0).map { (at(d.left, it) - at(d.right, it)) - 15.0 }.average()
            val c = HearingCompensation.curves(r, type, strength)
            eqDiff += listOf(1000.0, 2000.0, 4000.0).map { at(c.left, it) - at(c.right, it) }.average()
        }
        println("left +15 dB everywhere: smooth L−R bias %.1f dB, EQ L−R %.1f dB".format(bias / runs, eqDiff / runs))
        // 1.4's fit: −6.0 dB, EQ 6.7 dB.
        assertTrue("bias ${bias / runs}", bias / runs > -4.0)
        assertTrue("EQ ${eqDiff / runs}", eqDiff / runs > 7.5)
    }

    @Test
    fun aNotchIsMeasuredButNotChased() {
        var nodeErr = 0.0
        var eq = 0.0
        repeat(runs) { i ->
            val r = test(500 + i, ripple = 1.0) { _, f -> if (f == 4000.0) 25.0 else 0.0 }
            val mid = r.points.filter { it.freq in 500.0..1000.0 }.map { it.db - ReferenceCurve.at(it.freq, type) }.average()
            nodeErr += r.points.filter { it.freq == 4000.0 }.map { (it.db - ReferenceCurve.at(4000.0, type) - mid) - 25.0 }.average()
            val c = HearingCompensation.curves(r, type, strength)
            eq += at(Curves.average(c.left, c.right), 4000.0)
        }
        println("25 dB notch at 4 kHz: node estimate error %.1f dB, EQ at 4 kHz %.1f dB".format(nodeErr / runs, eq / runs))
        // The EQ follows the smooth trend, not the notch. The band's own estimate reads it about 8 dB
        // shallow: the local term (3 dB) is narrow; a heavier-tailed one is an open improvement.
        assertTrue("EQ ${eq / runs}", eq / runs < 8.0)
        assertTrue("node ${nodeErr / runs}", nodeErr / runs > -9.0)
    }

}
