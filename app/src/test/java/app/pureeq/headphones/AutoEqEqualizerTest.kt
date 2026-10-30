package app.pureeq.headphones

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * The equalize() port against AutoEq itself: published results (src/test/resources/autoeq, three
 * models on three rigs, from AutoEq commit 7ae0f56d) and a synthetic curve whose expected values
 * come from AutoEq's own code run with scipy (it exercises the slope limits, protected dips and the
 * +6 dB cap far more than real headphones do).
 */
class AutoEqEqualizerTest {

    private fun table(name: String): Map<String, DoubleArray> {
        val text = javaClass.getResourceAsStream("/autoeq/$name")!!.bufferedReader().readText()
        val lines = text.lines().filter { it.isNotBlank() }
        val head = lines[0].split(',')
        val rows = lines.drop(1).map { l -> l.split(',').map { it.toDouble() } }
        return head.withIndex().associate { (i, h) -> h to DoubleArray(rows.size) { rows[it][i] } }
    }

    private fun maxDiff(a: DoubleArray, b: DoubleArray): Double {
        var m = 0.0
        for (i in a.indices) m = max(m, abs(a[i] - b[i]))
        return m
    }

    @Test
    fun gridAndWindowsAreAutoEqs() {
        val g = AutoEqEqualizer.GRID
        assertEquals(695, g.size)
        assertEquals(20.0, g.first(), 0.0)
        assertEquals(19955.54, g.last(), 0.01)
        assertEquals(7, AutoEqEqualizer.windowSize(g, 1.0 / 12))
        assertEquals(139, AutoEqEqualizer.windowSize(g, 2.0))
        assertEquals(15, AutoEqEqualizer.windowSize(g, 1.0 / 5))
        val t = table("synthetic.csv")
        assertTrue(maxDiff(g, t.getValue("frequency")) < 1e-9)
    }

    @Test
    fun savitzkyGolayMatchesScipy() {
        val t = table("synthetic.csv")
        val x = t.getValue("error")
        assertTrue(maxDiff(AutoEqEqualizer.savgol(x, 7), t.getValue("savgol7")) < 2e-6)
        assertTrue(maxDiff(AutoEqEqualizer.savgol(x, 139), t.getValue("savgol139")) < 2e-6)
    }

    @Test
    fun smoothingBlendsToTwoOctavesInTheTreble() {
        val t = table("synthetic.csv")
        val s = AutoEqEqualizer.smoothen(AutoEqEqualizer.GRID, t.getValue("error"), AutoEqEqualizer.WINDOW_OCT, AutoEqEqualizer.TREBLE_WINDOW_OCT)
        assertTrue(maxDiff(s, t.getValue("smoothed")) < 2e-6)
    }

    @Test
    fun stressCurveMatchesAutoEq() {
        val t = table("synthetic.csv")
        val r = AutoEqEqualizer.equalize(AutoEqEqualizer.GRID, t.getValue("error"))
        val d = maxDiff(r.curve, t.getValue("equalization"))
        println("synthetic stress curve: max |port − AutoEq| = %.6f dB".format(d))
        assertTrue("max diff $d", d < 1e-4)
    }

    @Test
    fun publishedResultsAreReproduced() {
        for (name in listOf("oratory1990 Sennheiser HD 650.csv", "Rtings HMS II.3 Samsung Galaxy Buds2.csv", "Rtings 5128 Sony WH-1000XM4.csv")) {
            val t = table(name)
            val raw = t.getValue("raw")
            val target = t.getValue("target")
            // The published target already carries AutoEq's mean shift, so error = raw − target as AutoEq wrote it.
            val error = DoubleArray(raw.size) { raw[it] - target[it] }
            val r = AutoEqEqualizer.equalize(AutoEqEqualizer.GRID, error)
            val published = t.getValue("equalization")
            val d = maxDiff(r.curve, published)
            println("$name: max |port − published| = %.3f dB".format(d))
            // Published values are rounded to 0.01 dB, and so are the raw and target they come from.
            assertTrue("$name: $d", d <= 0.02)
            assertEquals(published.max(), r.curve.max(), 0.02)
        }
    }

    @Test
    fun peaksFollowScipy() {
        // Plateau: the peak is its middle sample.
        assertArrayEquals(intArrayOf(2), AutoEqEqualizer.findPeaks(doubleArrayOf(0.0, 2.0, 2.0, 2.0, 0.0), 1.0))
        // The first and last samples are never peaks.
        assertArrayEquals(intArrayOf(), AutoEqEqualizer.findPeaks(doubleArrayOf(5.0, 1.0, 0.0, 1.0, 5.0), 0.0))
        // Prominence: the lower of the two side minima before higher ground.
        val x = doubleArrayOf(0.0, 2.0, 1.0, 3.0, 0.0)
        assertEquals(1.0, AutoEqEqualizer.prominence(x, 1), 1e-12)
        assertEquals(3.0, AutoEqEqualizer.prominence(x, 3), 1e-12)
        assertArrayEquals(intArrayOf(1, 3), AutoEqEqualizer.findPeaks(x, 1.0))
        assertArrayEquals(intArrayOf(3), AutoEqEqualizer.findPeaks(x, 1.5))
    }

    @Test
    fun flatErrorIsInvertedAsIs() {
        // No peak or dip with 1 dB prominence: AutoEq returns the inverted smoothed error.
        val g = AutoEqEqualizer.GRID
        val error = DoubleArray(g.size) { 0.4 }
        val r = AutoEqEqualizer.equalize(g, error)
        assertTrue(r.curve.all { abs(it + 0.4) < 1e-9 })
    }
}
