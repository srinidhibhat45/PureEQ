package app.pureeq.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class DspTest {
    private val fs = 48000.0

    private fun db(spec: FilterSpec, f: Double) = Biquad.design(spec, fs).magnitudeDb(f, fs)

    @Test
    fun peakFilterHitsItsGainAtCentreAndIsFlatFarAway() {
        val pk = FilterSpec(FilterType.PEAK, 1000.0, 6.0, 1.41)
        assertEquals(6.0, db(pk, 1000.0), 0.01)
        assertEquals(0.0, db(pk, 20.0), 0.05)
        assertEquals(0.0, db(pk, 19000.0), 0.1)
        // Q 1.41 spans about one octave, so an octave below gets a fraction of the boost.
        assertTrue(db(pk, 500.0) in 1.0..3.5)
    }

    @Test
    fun shelvesReachFullGainOnOneSideAndHalfAtTheMidpoint() {
        val ls = FilterSpec(FilterType.LOW_SHELF, 105.0, 6.0, 0.7)
        assertEquals(6.0, db(ls, 20.0), 0.3)
        assertEquals(3.0, db(ls, 105.0), 0.05)
        assertEquals(0.0, db(ls, 5000.0), 0.05)
        val hs = FilterSpec(FilterType.HIGH_SHELF, 6000.0, -4.0, 0.7)
        assertEquals(-4.0, db(hs, 19000.0), 0.4)
        assertEquals(-2.0, db(hs, 6000.0), 0.05)
        assertEquals(0.0, db(hs, 200.0), 0.05)
    }

    @Test
    fun filterChainMatchesTheAnalyticResponse() {
        val specs = listOf(FilterSpec(FilterType.PEAK, 1000.0, 6.0, 1.41), FilterSpec(FilterType.LOW_SHELF, 105.0, -3.0, 0.7))
        val chain = FilterChain(specs, fs, 1.0)
        var peak = 0.0
        for (n in 0 until 48000) {
            val y = chain.process(sin(2 * PI * 1000.0 * n / fs))
            if (n > 24000) peak = maxOf(peak, abs(y))
        }
        val expected = Response.of(specs, doubleArrayOf(1000.0))[0]
        assertEquals(expected, 20 * log10(peak), 0.05)
    }

    @Test
    fun pchipNeverOvershoots() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0)
        val ys = doubleArrayOf(0.0, 0.0, 10.0, 10.0)
        var x = 0.0
        while (x <= 3.0) {
            val v = Curves.pchip(xs, ys, x)
            assertTrue("overshoot at $x: $v", v in -1e-9..10.0 + 1e-9)
            x += 0.01
        }
        assertEquals(5.0, Curves.pchip(xs, ys, 1.5), 1e-9)
    }

    @Test
    fun smoothingKeepsAConstantCurve() {
        val flat = DoubleArray(FreqGrid.SIZE) { 2.5 }
        Curves.smooth(flat, 1.0 / 3.0).forEach { assertEquals(2.5, it, 1e-9) }
    }

    @Test
    fun loudnessOfFlatAndUniformCurves() {
        assertEquals(0.0, Loudness.offsetDb(DoubleArray(FreqGrid.SIZE)), 1e-9)
        assertEquals(3.0, Loudness.offsetDb(DoubleArray(FreqGrid.SIZE) { 3.0 }), 1e-9)
        val bass = Response.of(listOf(FilterSpec(FilterType.LOW_SHELF, 105.0, 6.0, 0.7)), FreqGrid.hz)
        val l = Loudness.offsetDb(bass)
        assertTrue("bass shelf loudness $l", l in 0.5..4.0)
    }

    @Test
    fun parametricFitRecoversKnownGains() {
        val truth = listOf(
            FilterSpec(FilterType.PEAK, 1000.0, 5.0, 1.41),
            FilterSpec(FilterType.PEAK, 4000.0, -3.0, 2.0),
            FilterSpec(FilterType.LOW_SHELF, 120.0, 2.0, 0.7),
        )
        val target = Response.of(truth, FreqGrid.hz)
        val slots = truth.map { ParametricFit.Slot(it.type, it.freq, it.q) }
        val fit = ParametricFit.fit(target, slots, ridge = 0.0)
        for (i in truth.indices) assertEquals(truth[i].gainDb, fit[i].gainDb, 0.05)
        assertTrue(ParametricFit.maxError(target, fit) < 0.05)
    }

    @Test
    fun toneHasExactLevelRampsAndSilentOtherEar() {
        val gen = ToneGenerator(fs)
        val stim = Stimulus(1000.0, Ear.LEFT, -20.0, warble = false)
        gen.start(stim)
        val frames = (stim.durationMs / 1000 * fs).toInt() + 4800
        val out = FloatArray(frames * 2)
        gen.render(out, frames)
        var peakL = 0.0
        var peakR = 0.0
        var last = -1
        for (n in 0 until frames) {
            peakL = maxOf(peakL, abs(out[2 * n].toDouble()))
            peakR = maxOf(peakR, abs(out[2 * n + 1].toDouble()))
            if (out[2 * n] != 0f) last = n
        }
        assertEquals(0.1, peakL, 0.001)
        assertEquals(0.0, peakR, 0.0)
        // The first sample starts from silence and the beep ends where its timing says.
        assertEquals(0f, out[0])
        assertEquals((stim.durationMs / 1000 * fs), last.toDouble(), fs * 0.002)
        assertTrue(!gen.isActive)
    }
}
