package app.pureeq.profile

import app.pureeq.dsp.Curves
import app.pureeq.dsp.Ear
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.dsp.FreqGrid
import app.pureeq.hearing.HearingTestConfig
import app.pureeq.hearing.HearingTestResult
import app.pureeq.hearing.ReferenceThreshold
import app.pureeq.hearing.ThresholdFlag
import app.pureeq.hearing.ThresholdPoint
import app.pureeq.system.DpBands
import app.pureeq.taste.TasteSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class ExportersTest {

    private val profile: Profile = run {
        val pts = mutableListOf<ThresholdPoint>()
        for (ear in listOf(Ear.LEFT, Ear.RIGHT)) for (f in HearingTestConfig.STANDARD) {
            val loss = if (f >= 4000) 15.0 else 0.0
            pts += ThresholdPoint(f, ear, -60.0 + ReferenceThreshold.at(f) + loss + if (ear == Ear.LEFT) 3.0 else 0.0, 2.0, ThresholdFlag.OK)
        }
        Profile("x", "Buds Pro", 0L, hearing = HearingTestResult(pts, 8, 0, null, null, 100, 280, true, -40.0), taste = TasteSettings(bass = 4.0, treble = -1.5))
    }

    @Test
    fun parametricMatchesAutoEqSyntax() {
        val text = Exporters.parametric(listOf(FilterSpec(FilterType.LOW_SHELF, 105.0, 6.4, 0.7), FilterSpec(FilterType.PEAK, 8800.0, -5.06, 1.4234)), -6.1)
        val lines = text.trim().lines()
        assertEquals("Preamp: -6.1 dB", lines[0])
        assertEquals("Filter 1: ON LSC Fc 105 Hz Gain 6.4 dB Q 0.70", lines[1])
        assertEquals("Filter 2: ON PK Fc 8800 Hz Gain -5.1 dB Q 1.42", lines[2])
    }

    @Test
    fun graphicEqUsesWaveletsGridAndPeaksAtMinusPointTwo() {
        val eq = ProfileEq(profile)
        val g = Exporters.graphicEq(Exporters.linkedCurveWithPreamp(eq))
        assertTrue(g.startsWith("GraphicEQ: 20 "))
        val pairs = g.removePrefix("GraphicEQ: ").split("; ").map { it.split(" ") }
        assertEquals(127, pairs.size)
        assertEquals(Exporters.WAVELET_FREQS.toList(), pairs.map { it[0].toInt() })
        assertEquals(-0.2, pairs.maxOf { it[1].toDouble() }, 0.051)
        // Same as AutoEq's generator: int(20 * 1.0563^i).
        assertEquals(19871, Exporters.WAVELET_FREQS.last())
    }

    @Test
    fun perEarConfigHasBothChannels() {
        val t = Exporters.parametricPerEar(ProfileEq(profile))
        assertTrue(t.contains("\nChannel: L\n"))
        assertTrue(t.contains("\nChannel: R\n"))
        assertTrue(t.trim().endsWith("Channel: all"))
    }

    @Test
    fun bandValuesAreSteppedClampedAndCentred() {
        val eq = ProfileEq(profile)
        for (set in BandSet.entries) {
            val v = Exporters.bands(eq.linkedCurve, set)
            assertEquals(set.freqs.size, v.size)
            v.forEach {
                assertTrue(it.gainDb in set.minDb..set.maxDb)
                assertEquals(0.0, abs(it.gainDb / set.stepDb - (it.gainDb / set.stepDb).roundToInt()), 1e-6)
            }
        }
        // The treble boost from the hearing result shows up in the 10-band 8 kHz slider.
        val ten = Exporters.bands(eq.linkedCurve, BandSet.TEN)
        assertTrue(ten.first { it.freq == 8000.0 }.gainDb > ten.first { it.freq == 1000.0 }.gainDb)
    }

    @Test
    fun systemEqBandsReproduceTheCurveOnRealFftBins() {
        val edges = DpBands.edges
        assertTrue(edges.size <= 128)
        for (i in 1 until edges.size) assertTrue(edges[i] > edges[i - 1])
        assertEquals(48000.0, edges.last(), 0.0)
        val curve = Curves.add(ProfileEq(profile).leftCurve, DoubleArray(FreqGrid.SIZE))
        val gains = DpBands.gains(curve)
        for (rate in listOf(44100.0, 48000.0)) {
            // Mirror DPFrequency: bin stop = (int)(0.5 + cutoff * N / rate), bands are contiguous.
            val n = 2048
            val perBin = DoubleArray(n / 2 + 1) { Double.NaN }
            var start = 0
            for (b in edges.indices) {
                val stop = (0.5 + edges[b] * n / rate).toInt()
                for (k in start..minOf(stop, n / 2)) perBin[k] = gains[b].toDouble()
                start = stop + 1
            }
            assertTrue("every bin written at $rate", perBin.none { it.isNaN() })
            var worst = 0.0
            var worstHz = 0.0
            for (k in 2..n / 2) {
                val f = k * rate / n
                if (f > 20000) break
                val err = abs(perBin[k] - Curves.sample(curve, f))
                if (err > worst) {
                    worst = err
                    worstHz = f
                }
            }
            assertTrue("staircase error $worst dB at ${worstHz.roundToInt()} Hz, rate $rate", worst < 0.75)
        }
    }
}
