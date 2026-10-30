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
    fun theCapCoversTheTargetsBassAsInAutoEqsResults() {
        // The target's bass counts towards the +6 dB cap, as with AutoEq's bass boost.
        for (i in FIXTURES.indices) {
            val m = model(i)
            val total = MeasuredCorrection.withBass(m.curveDb!!, m.bassDb!!)
            assertTrue("${m.name}: ${total.max()}", total.max() <= AutoEqEqualizer.MAX_GAIN_DB + 0.05)
        }
        // oratory1990's HD 650 was published against the full Harman over-ear 2018 target, which the
        // target without bass plus its shelf rebuilds: the app's correction is AutoEq's published one.
        val published = AutoEqCsv.parse(fixture("oratory1990 Sennheiser HD 650.csv"), "equalization")
        val m = model(0)
        val total = MeasuredCorrection.withBass(m.curveDb!!, m.bassDb!!)
        val want = MeasuredCorrection.interpolate(published, FreqGrid.hz.filter { it <= published.freqs.last() }.toDoubleArray())
        var d = 0.0
        for (k in want.indices) d = max(d, abs(want[k] - total[k]))
        println("HD 650 with the target's bass: within %.3f dB of AutoEq's published correction".format(d))
        // Published values are rounded to 0.01 dB.
        assertTrue("HD 650: $d", d <= 0.02)
        // +6 dB at 20 and 30 Hz, where the full target would ask for about +10.5 and +7.7.
        assertEquals(6.0, Curves.sample(total, 20.0), 0.1)
        assertEquals(6.0, Curves.sample(total, 30.0), 0.1)
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
            assertTrue("${m.name}: rms $rms", rms < 0.35)
            assertTrue("${m.name}: worst $worst", worst < 1.0)
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
