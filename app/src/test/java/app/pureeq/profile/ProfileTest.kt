package app.pureeq.profile

import app.pureeq.dsp.Curves
import app.pureeq.dsp.Ear
import app.pureeq.dsp.FreqGrid
import app.pureeq.dsp.ParametricFit
import app.pureeq.hearing.HearingTestConfig
import app.pureeq.hearing.HearingTestResult
import app.pureeq.hearing.ReferenceThreshold
import app.pureeq.hearing.ThresholdFlag
import app.pureeq.hearing.ThresholdPoint
import app.pureeq.taste.TasteSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ProfileTest {

    private fun result(lossDb: (Ear, Double) -> Double): HearingTestResult {
        val points = mutableListOf<ThresholdPoint>()
        for (ear in listOf(Ear.LEFT, Ear.RIGHT)) {
            for (f in HearingTestConfig.STANDARD) {
                points += ThresholdPoint(f, ear, -60.0 + ReferenceThreshold.at(f) + lossDb(ear, f), 2.0, ThresholdFlag.OK)
            }
        }
        return HearingTestResult(points, 10, 0, 1.0, -2.0, 120, 300, true, -40.0)
    }

    private fun profile(h: HearingTestResult?, taste: TasteSettings = TasteSettings(), strength: Double = 0.5) =
        Profile("id", "Test", 0L, hearing = h, hearingStrength = strength, taste = taste)

    @Test
    fun normalHearingGivesAFlatProfile() {
        val eq = ProfileEq(profile(result { _, _ -> 0.0 }))
        for (i in FreqGrid.hz.indices) {
            assertEquals(0.0, eq.leftCurve[i], 0.3)
            assertEquals(0.0, eq.rightCurve[i], 0.3)
        }
        assertEquals(0.0, eq.preampDb, 0.31)
    }

    @Test
    fun highFrequencyLossIsBoostedByHalfAndFitsTheTarget() {
        val h = result { _, f -> if (f >= 4000.0) 20.0 else 0.0 }
        val eq = ProfileEq(profile(h))
        val at6k = Curves.sample(eq.leftCurve, 6000.0)
        val at500 = Curves.sample(eq.leftCurve, 500.0)
        assertEquals(10.0, at6k, 1.5)
        assertEquals(0.0, at500, 0.7)
        // Fitted filters reproduce the smoothed target closely.
        assertTrue(ParametricFit.maxError(eq.hearingTargetLeft, eq.hearingFiltersLeft) < 1.0)
        // Preamp keeps the highest boost at 0 dBFS.
        assertEquals(-Curves.max(eq.leftCurve), eq.preampDb, 0.11)
    }

    @Test
    fun deepBassBoostIsCapped() {
        val eq = ProfileEq(profile(result { _, f -> if (f <= 250.0) 30.0 else 0.0 }, strength = 1.0))
        assertTrue(Curves.sample(eq.hearingTargetLeft, 100.0) <= HearingCompensation.MAX_BASS_BOOST_DB + 1e-6)
    }

    @Test
    fun worseLeftEarGetsMoreGain() {
        val eq = ProfileEq(profile(result { ear, _ -> if (ear == Ear.LEFT) 10.0 else 0.0 }))
        assertTrue(eq.earDifferenceDb in 4.0..6.0)
        assertTrue(Curves.sample(eq.leftCurve, 1000.0) > Curves.sample(eq.rightCurve, 1000.0) + 4)
    }

    @Test
    fun tasteAddsOnTop() {
        val eq = ProfileEq(profile(null, TasteSettings(bass = 6.0)))
        assertEquals(6.0, Curves.sample(eq.leftCurve, 30.0), 0.5)
        assertEquals(0.0, Curves.sample(eq.leftCurve, 3000.0), 0.1)
        assertEquals(eq.leftCurve.toList(), eq.linkedCurve.toList())
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val p = profile(result { _, f -> f / 1000.0 }, TasteSettings(1.5, -2.0, 0.5, 3.0), 0.7).copy(
            deviceName = "Buds", deviceType = 8, balanceDb = 1.2, recommendedTaste = TasteSettings(4.5, -1.0, 2.0, -2.25),
        )
        val back = Profile.fromJson(p.toJson())
        assertEquals(p, back)
        val a = ProfileEq(p)
        val b = ProfileEq(back)
        for (i in a.leftCurve.indices) assertTrue(abs(a.leftCurve[i] - b.leftCurve[i]) < 1e-9)
    }

    @Test
    fun resetReturnsToTheTestsRecommendation() {
        val tested = TasteSettings(4.5, -1.0, 2.0, -2.25)
        val p = profile(result { _, _ -> 0.0 }, TasteSettings(6.0, 0.0, 2.0, -2.0), 0.8).copy(
            name = "Mine", balanceDb = -1.5, recommendedTaste = tested,
        )
        assertFalse(p.isRecommended)
        val r = p.recommended()
        assertEquals(tested, r.taste)
        assertEquals(HearingCompensation.DEFAULT_STRENGTH, r.hearingStrength, 0.0)
        assertEquals(0.0, r.balanceDb, 0.0)
        assertEquals("Mine", r.name)
        assertEquals(p.hearing, r.hearing)
        assertTrue(r.isRecommended)
    }

    @Test
    fun profilesFromVersion1KeepTheirTasteOnReset() {
        // 1.0 didn't store the taste test's result; Reset can only restore correction and balance.
        val old = profile(result { _, _ -> 0.0 }, TasteSettings(bass = 3.0), 0.7).toJson().apply { remove("recommendedTaste") }
        val p = Profile.fromJson(old)
        assertNull(p.recommendedTaste)
        assertEquals(TasteSettings(bass = 3.0), p.recommended().taste)
        assertEquals(0.5, p.recommended().hearingStrength, 0.0)
    }

    @Test
    fun tasteOnlyProfileIgnoresTheHiddenCorrectionSlider() {
        val p = profile(null, strength = 0.8).copy(recommendedTaste = TasteSettings())
        assertTrue(p.isRecommended)
    }
}
