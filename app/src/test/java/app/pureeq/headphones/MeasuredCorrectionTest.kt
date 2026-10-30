package app.pureeq.headphones

import app.pureeq.dsp.Curves
import app.pureeq.dsp.FilterType
import app.pureeq.dsp.FreqGrid
import app.pureeq.dsp.Response
import app.pureeq.hearing.HeadphoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class MeasuredCorrectionTest {

    companion object {
        /** Published results (src/test/resources/autoeq), each with its index line and its rig's target without bass. */
        private val FIXTURES = listOf(
            Triple(
                "oratory1990 Sennheiser HD 650",
                "- [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990",
                "Harman over-ear 2018 without bass",
            ),
            Triple(
                "Rtings HMS II.3 Samsung Galaxy Buds2",
                "- [Samsung Galaxy Buds2](./Rtings/HMS%20II.3%20in-ear/Samsung%20Galaxy%20Buds2) by Rtings on HMS II.3",
                "HMS II.3 Harman in-ear 2019 without bass",
            ),
            Triple(
                "Rtings 5128 Sony WH-1000XM4",
                "- [Sony WH-1000XM4](./Rtings/Bruel%20&%20Kjaer%205128%20over-ear/Sony%20WH-1000XM4) by Rtings on Bruel & Kjaer 5128",
                "LMG 5128 0.6 without bass",
            ),
        )

        fun fixture(name: String): String = MeasuredCorrectionTest::class.java.getResourceAsStream("/autoeq/$name")!!.bufferedReader().readText()

        fun entry(i: Int) = AutoEqIndex.parse(FIXTURES[i].second).single()

        /** The model the app computes for fixture [i]: its measurement against its rig's target. */
        fun model(i: Int): HeadphoneModel {
            val (file, _, target) = FIXTURES[i]
            val e = entry(i)
            assertEquals(target, AutoEqTarget.of(e).file)
            return MeasuredCorrection.model(e, AutoEqTarget.of(e), AutoEqCsv.parse(fixture("$file.csv")), AutoEqCsv.parse(fixture("target $target.csv")))
        }
    }

    @Test
    fun publishedResultsComeOutOfTheAppsPipeline() {
        // Against the target each result was published with, the pipeline gives AutoEq's published curve.
        for ((file, _, _) in FIXTURES) {
            val text = fixture("$file.csv")
            val eq = MeasuredCorrection.equalization(AutoEqCsv.parse(text, "raw"), AutoEqCsv.parse(text, "target"))
            val published = AutoEqCsv.parse(text, "equalization").db
            var d = 0.0
            for (i in eq.indices) d = max(d, abs(eq[i] - published[i]))
            assertTrue("$file: $d", d <= 0.02)
        }
    }

    @Test
    fun eachRigsTargetGivesWhatAutoEqsOwnCodeGives() {
        // AutoEq's own code against each rig's target without bass (expected.csv) and the app's pipeline agree.
        for (i in FIXTURES.indices) {
            val (file, _, target) = FIXTURES[i]
            val eq = MeasuredCorrection.equalization(AutoEqCsv.parse(fixture("$file.csv")), AutoEqCsv.parse(fixture("target $target.csv")))
            val expected = AutoEqCsv.parse(fixture("$file expected.csv"), "equalization")
            val at = FreqGrid.hz.filter { it <= expected.freqs.last() }.toDoubleArray()
            val want = MeasuredCorrection.interpolate(expected, at)
            val got = MeasuredCorrection.interpolate(AutoEqCsv.Curve(AutoEqEqualizer.GRID, eq), at)
            var d = 0.0
            for (k in at.indices) d = max(d, abs(want[k] - got[k]))
            println("$file: within %.3f dB of AutoEq's code".format(d))
            assertTrue("$file: $d", d < 0.02)
        }
    }

    @Test
    fun theCorrectionKeepsAutoEqsCapAndTrustsTheTrebleLess() {
        for (i in FIXTURES.indices) {
            val (file, _, target) = FIXTURES[i]
            val m = model(i)
            val c = m.curveDb!!.toDoubleArray()
            // AutoEq's +6 dB cap on the correction itself; the target's bass comes as its own exact filter.
            assertTrue("${m.name}: ${c.max()}", c.max() <= AutoEqEqualizer.MAX_GAIN_DB + 0.05)
            // From 6 kHz boosts stop at +3 dB, and above 8 kHz half of what the fixture asks applies.
            for (k in FreqGrid.hz.indices) if (FreqGrid.hz[k] >= 6000.0) assertTrue("${m.name} ${FreqGrid.hz[k]}", c[k] <= 3.05)
            val pure = MeasuredCorrection.equalization(AutoEqCsv.parse(fixture("$file.csv")), AutoEqCsv.parse(fixture("target $target.csv")))
            val onGrid = MeasuredCorrection.interpolate(AutoEqCsv.Curve(AutoEqEqualizer.GRID, pure), FreqGrid.hz)
            for (f in listOf(10000.0, 12000.0)) {
                val k = FreqGrid.nearestIndex(f)
                if (onGrid[k] < 6.0) assertEquals("${m.name} $f", minOf(onGrid[k] * MeasuredCorrection.TREBLE_TRUST, 3.0), c[k], 0.15)
            }
        }
        // HD 650: the target's bass shelf on top of the correction reaches the full Harman bass (+10.5 dB
        // at 20 Hz) before the bass guard decides how much of it the whole EQ keeps.
        val m = model(0)
        assertEquals(10.5, Curves.sample(MeasuredCorrection.withBass(m.curveDb!!, m.bassDb!!), 20.0), 0.3)
    }

    @Test
    fun filtersReproduceTheCorrectionWithTheTargetsBass() {
        for (i in FIXTURES.indices) {
            val m = model(i)
            val t = AutoEqTarget.of(entry(i))
            val want = MeasuredCorrection.withBass(m.curveDb!!, t.bassDb)
            val got = Response.of(m.filters, FreqGrid.hz)
            val band = FreqGrid.hz.indices.filter { FreqGrid.hz[it] in 30.0..16000.0 }
            val rms = sqrt(band.sumOf { (got[it] - want[it]).let { e -> e * e } } / band.size)
            val worst = band.maxOf { abs(got[it] - want[it]) }
            println("${m.name}: ${m.filters.size} filters, error RMS %.2f dB, worst %.2f dB".format(rms, worst))
            val top = FreqGrid.hz.indices.filter { FreqGrid.hz[it] in 10000.0..16000.0 }.maxOf { abs(got[it] - want[it]) }
            val worstAt = band.maxBy { abs(got[it] - want[it]) }.let { FreqGrid.hz[it] }
            println("  worst at %.0f Hz; worst above 10 kHz %.2f dB".format(worstAt, top))
            assertTrue("${m.name}: rms $rms", rms < 0.35)
            // Above 4 kHz peaks stay broad (Q ≤ 2), so a fixture's narrow features there are followed only roughly.
            val upTo4k = band.filter { FreqGrid.hz[it] <= 4000.0 }.maxOf { abs(got[it] - want[it]) }
            assertTrue("${m.name}: worst up to 4 kHz $upTo4k", upTo4k < 1.0)
            assertTrue("${m.name}: worst $worst", worst < 1.5)
            // At most twelve for the correction, plus the target's bass shelf.
            assertTrue(m.filters.size <= CorrectionFit.MAX_FILTERS + 1)
            assertEquals(MeasuredCorrection.bassShelf(t.bassDb), m.filters.first())
            // The target's bass is in: at 30 Hz the filters add the shelf on top of the correction.
            assertEquals(Curves.sample(m.curveDb!!.toDoubleArray(), 30.0) + t.bassDb, Curves.sample(got, 30.0), 1.0)
        }
    }

    @Test
    fun theModelKnowsWhereItCameFrom() {
        val m = model(0)
        assertEquals("Sennheiser HD 650", m.name)
        assertEquals("oratory1990", m.source)
        assertEquals("oratory1990/over-ear/Sennheiser%20HD%20650", m.path)
        assertEquals("Harman over-ear 2018", m.target)
        assertEquals(6.0, m.bassDb!!, 0.0)
        assertEquals(HeadphoneType.OVER_EAR, m.type)
        assertEquals(FreqGrid.SIZE, m.curveDb!!.size)
        assertEquals("Headphone data: oratory1990 via AutoEq (Jaakko Pasanen, MIT)", m.attribution)
        assertEquals("the Harman over-ear 2018 target", m.targetLabel)
        // AutoEq's published corrections (crinacle, 1.3 profiles) say so.
        assertEquals("AutoEq's target for it", m.copy(target = null).targetLabel)
        // The target's bass is an exact shelf of its own among the filters.
        assertTrue(m.filters.any { it.type == FilterType.LOW_SHELF })
    }

    @Test
    fun eachRigGetsItsTarget() {
        fun t(line: String) = AutoEqTarget.of(AutoEqIndex.parse(line).single())
        assertEquals(AutoEqTarget.HARMAN_OVER_EAR_2018, t("- [A](./oratory1990/over-ear/A) by oratory1990"))
        assertEquals(AutoEqTarget.HARMAN_IN_EAR_2019, t("- [A](./oratory1990/in-ear/A) by oratory1990"))
        assertEquals(AutoEqTarget.HARMAN_IN_EAR_2019, t("- [A](./oratory1990/earbud/A) by oratory1990"))
        assertEquals(AutoEqTarget.HARMAN_IN_EAR_2019, t("- [A](./HypetheSonics/GRAS%20RA0045%20in-ear/A) by HypetheSonics on GRAS RA0045"))
        assertEquals(AutoEqTarget.HARMAN_IN_EAR_2019, t("- [A](./Super%20Review/in-ear/A) by Super Review"))
        assertEquals(AutoEqTarget.HARMAN_OVER_EAR_2018, t("- [A](./Super%20Review/over-ear/A) by Super Review"))
        assertEquals(AutoEqTarget.HMS_HARMAN_IN_EAR_2019, t("- [A](./Rtings/HMS%20II.3%20in-ear/A) by Rtings on HMS II.3"))
        assertEquals(AutoEqTarget.HMS_HARMAN_IN_EAR_2019, t("- [A](./Rtings/HMS%20II.3%20earbud/A) by Rtings on HMS II.3"))
        assertEquals(AutoEqTarget.HMS_HARMAN_OVER_EAR_2018, t("- [A](./Innerfidelity/over-ear/A) by Innerfidelity"))
        assertEquals(AutoEqTarget.HMS_HARMAN_IN_EAR_2019, t("- [A](./Headphone.com%20Legacy/in-ear/A) by Headphone.com Legacy"))
        assertEquals(AutoEqTarget.LMG_OVER_EAR_5128, t("- [A](./Rtings/Bruel%20&%20Kjaer%205128%20over-ear/A) by Rtings on Bruel & Kjaer 5128"))
        assertEquals(AutoEqTarget.LMG_OVER_EAR_5128, t("- [A](./HypetheSonics/over-ear/A) by HypetheSonics"))
        assertEquals(AutoEqTarget.LMG_OVER_EAR_5128, t("- [A](./HypetheSonics/earbud/A) by HypetheSonics"))
        assertEquals(AutoEqTarget.JM1_IN_EAR_5128, t("- [A](./Rtings/Bruel%20&%20Kjaer%205128%20in-ear/A) by Rtings on Bruel & Kjaer 5128"))
        assertEquals(AutoEqTarget.JM1_IN_EAR_5128, t("- [A](./HypetheSonics/Bruel%20&%20Kjaer%205128%20in-ear/A) by HypetheSonics on Bruel & Kjaer 5128"))
        assertEquals("targets/Harman in-ear 2019 without bass.csv", AutoEqTarget.HARMAN_IN_EAR_2019.csvPath)
        // Harman's own bass: in-ear 2019 = without bass + 9 dB, over-ear 2018 = + 6 dB.
        assertEquals(9.0, AutoEqTarget.HARMAN_IN_EAR_2019.bassDb, 0.0)
        assertEquals(6.0, AutoEqTarget.HARMAN_OVER_EAR_2018.bassDb, 0.0)
    }

    @Test
    fun crinacleHasOnlyAutoEqsOwnCorrections() {
        val e = AutoEqIndex.parse("- [Samsung Galaxy Buds2 Pro](./crinacle/711%20in-ear/Samsung%20Galaxy%20Buds2%20Pro) by crinacle on 711").single()
        assertFalse(AutoEqTarget.hasMeasurement(e))
        assertTrue(AutoEqTarget.hasMeasurement(entry(0)))
        assertEquals("in-ear", e.form)
        assertEquals("over-ear", entry(0).form)
    }

    @Test
    fun csvParsingSortsSkipsGapsAndRejectsJunk() {
        val rows = (0 until 60).map { 20.0 * Math.pow(1.13, it.toDouble()) }
        val text = "frequency,raw,error\n" + rows.reversed().joinToString("\n") { "%.2f,%.2f,NaN".format(it, it / 1000) } + "\n30.5,NaN,1\n"
        val c = AutoEqCsv.parse(text)
        assertEquals(60, c.freqs.size)
        assertTrue((1 until c.freqs.size).all { c.freqs[it] > c.freqs[it - 1] })
        assertTrue(runCatching { AutoEqCsv.parse("<html>Not found</html>") }.isFailure)
        assertTrue(runCatching { AutoEqCsv.parse("frequency,raw\n20,0\n30,1\n") }.isFailure)
    }
}
