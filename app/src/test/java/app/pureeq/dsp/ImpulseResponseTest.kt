package app.pureeq.dsp

import app.pureeq.headphones.MeasuredCorrectionTest
import app.pureeq.profile.Exporters
import app.pureeq.profile.Profile
import app.pureeq.profile.ProfileEq
import app.pureeq.taste.TasteSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max

class ImpulseResponseTest {

    /** Magnitude (dB) of [taps] at the FFT bins, zero-padded to the design size. */
    private fun magnitude(taps: DoubleArray): DoubleArray {
        val n = ImpulseResponse.FFT_SIZE
        val re = DoubleArray(n) { if (it < taps.size) taps[it] else 0.0 }
        val im = DoubleArray(n)
        Fft.transform(re, im)
        return DoubleArray(n / 2 + 1) { 20 * log10(max(hypot(re[it], im[it]), 1e-12)) }
    }

    private fun worstError(filters: List<FilterSpec>, gainDb: Double, rate: Int): Double {
        val taps = ImpulseResponse.of(filters, gainDb, rate)
        val got = magnitude(taps)
        var worst = 0.0
        for (k in got.indices) {
            val f = k * rate.toDouble() / ImpulseResponse.FFT_SIZE
            if (f < 25.0 || f > 18000.0) continue
            val want = Response.of(filters, doubleArrayOf(f), gainDb, rate.toDouble())[0]
            worst = max(worst, abs(got[k] - want))
        }
        return worst
    }

    @Test
    fun aFlatChainIsAPlainImpulse() {
        val taps = ImpulseResponse.of(emptyList(), 0.0, 48000)
        assertEquals(1.0, taps[0], 1e-6)
        assertTrue(taps.drop(1).all { abs(it) < 1e-6 })
    }

    @Test
    fun theTapsReproduceTheFiltersExactly() {
        val taste = TasteSettings(bass = 6.0, treble = -3.0).filters() + FilterSpec(FilterType.PEAK, 3000.0, -4.0, 2.0)
        for (rate in listOf(44100, 48000)) {
            val e = worstError(taste, -2.0, rate)
            println("taste chain at $rate Hz: worst error %.3f dB (25 Hz-18 kHz)".format(e))
            assertTrue("$rate: $e", e < 0.2)
        }
        // A real measured correction: twelve filters with narrow peaks.
        val hd650 = MeasuredCorrectionTest.model(0).filters
        val e = worstError(hd650, -6.0, 48000)
        println("HD 650 correction: worst error %.3f dB".format(e))
        assertTrue("$e", e < 0.2)
    }

    @Test
    fun itIsMinimumPhaseAndShort() {
        val taps = ImpulseResponse.of(TasteSettings(bass = 8.0).filters() + FilterSpec(FilterType.PEAK, 6000.0, 5.0, 4.0), -8.0, 48000)
        val total = taps.sumOf { it * it }
        // Minimum phase puts the energy first: nearly all of it within 20 ms, none before the start.
        val early = taps.take(960).sumOf { it * it }
        assertTrue("early ${early / total}", early / total > 0.999)
        // No infrasonic boost: the DC gain stays at the 20 Hz level.
        val dc = 20 * log10(abs(taps.sum()))
        assertTrue("dc $dc", dc <= Response.of(TasteSettings(bass = 8.0).filters(), doubleArrayOf(20.0), -8.0, 48000.0)[0] + 0.1)
    }

    @Test
    fun theExportIsAStereoFloatWavAtTheSystemEqsLevel() {
        val p = Profile("id", "Test", 0L, taste = TasteSettings(bass = 4.0), balanceDb = 2.0)
        val eq = ProfileEq(p)
        val bytes = Exporters.convolution(eq, 48000)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(3, b.getShort(20).toInt()) // IEEE float
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(48000, b.getInt(24))
        assertEquals(32, b.getShort(34).toInt())
        assertEquals(ImpulseResponse.TAPS * 8, b.getInt(40))
        assertEquals(44 + ImpulseResponse.TAPS * 8, bytes.size)
        // Each channel carries its ear: balance turns the left down by 2 dB, at the system EQ's level.
        val left = DoubleArray(ImpulseResponse.TAPS) { b.getFloat(44 + it * 8).toDouble() }
        val right = DoubleArray(ImpulseResponse.TAPS) { b.getFloat(48 + it * 8).toDouble() }
        val l1k = magnitude(left)[(1000.0 / 48000 * ImpulseResponse.FFT_SIZE).toInt()]
        val r1k = magnitude(right)[(1000.0 / 48000 * ImpulseResponse.FFT_SIZE).toInt()]
        assertEquals(-2.0, l1k - r1k, 0.05)
        assertEquals(Curves.sample(eq.rightCurve, 1000.0) + eq.preampDb, r1k, 0.1)
    }
}
