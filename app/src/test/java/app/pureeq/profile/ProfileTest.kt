package app.pureeq.profile

import app.pureeq.audio.ListeningLevel
import app.pureeq.dsp.Curves
import app.pureeq.dsp.Ear
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.dsp.FreqGrid
import app.pureeq.dsp.ParametricFit
import app.pureeq.dsp.Response
import app.pureeq.headphones.HeadphoneModel
import app.pureeq.headphones.MeasuredCorrectionTest
import app.pureeq.hearing.HeadphoneType
import app.pureeq.hearing.HearingSession
import app.pureeq.hearing.HearingTestConfig
import app.pureeq.hearing.HearingTestResult
import app.pureeq.hearing.Psychometric
import app.pureeq.hearing.ReferenceCurve
import app.pureeq.hearing.TestLength
import app.pureeq.hearing.ThresholdFlag
import app.pureeq.hearing.ThresholdPoint
import app.pureeq.hearing.TrialRecord
import app.pureeq.taste.TasteSession
import app.pureeq.taste.TasteSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

class ProfileTest {

    /** A result given as point estimates (as PureEQ 1.0-1.1 saved them): -60 dBFS + reference + loss. */
    private fun result(freqs: List<Double> = HearingTestConfig.BANDS, sd: Double = 2.0, lossDb: (Ear, Double) -> Double): HearingTestResult {
        val points = mutableListOf<ThresholdPoint>()
        for (ear in listOf(Ear.LEFT, Ear.RIGHT)) {
            for (f in freqs) {
                points += ThresholdPoint(f, ear, -60.0 + ReferenceCurve.at(f, HeadphoneType.IN_EAR) + lossDb(ear, f), sd, ThresholdFlag.OK)
            }
        }
        return HearingTestResult(points, 10, 0, null, null, 120, 300, true, -40.0, headphones = HeadphoneType.IN_EAR)
    }

    /** A result from a full simulated test, with its trial log. */
    private fun testedResult(seed: Int, lossDb: (Ear, Double) -> Double): HearingTestResult {
        val rnd = Random(seed)
        val session = HearingSession(TestLength.STANDARD.config(-40.0, true, HeadphoneType.IN_EAR), Random(seed + 1))
        while (true) {
            val p = session.next() ?: break
            val t = -55.0 + ReferenceCurve.at(p.freq, HeadphoneType.IN_EAR) + lossDb(p.ear, p.freq)
            val prob = if (p.isCatch) 0.02 else 0.02 + 0.96 / (1 + exp(-(p.levelDb - t) / 2.0))
            session.answer(rnd.nextDouble() < prob)
        }
        return session.result()
    }

    private fun profile(h: HearingTestResult?, taste: TasteSettings = TasteSettings(), strength: Double = HearingCompensation.DEFAULT_STRENGTH) =
        Profile("id", "Test", 0L, hearing = h, hearingStrength = strength, taste = taste)

    private fun maxAbs(c: DoubleArray, from: Double = 20.0, to: Double = 20000.0) = Curves.max(DoubleArray(c.size) { abs(c[it]) }, from, to)

    @Test
    fun normalHearingThroughTargetHeadphonesGivesAFlatProfile() {
        val eq = ProfileEq(profile(result { _, _ -> 0.0 }))
        assertTrue(maxAbs(eq.leftCurve) < 0.3)
        assertTrue(maxAbs(eq.rightCurve) < 0.3)
        assertTrue(eq.preampDb > -0.5)
    }

    @Test
    fun aRealTestOfNormalHearingStaysNearlyFlatAndCentred() {
        // Noise in the answers must not turn into peaks or a left/right difference.
        var rms = 0.0
        val runs = 12
        repeat(runs) { seed ->
            val eq = ProfileEq(profile(testedResult(seed) { _, _ -> 0.0 }))
            val diff = DoubleArray(FreqGrid.SIZE) { eq.leftCurve[it] - eq.rightCurve[it] }
            assertTrue("seed $seed: shape ${maxAbs(eq.leftCurve)}", maxAbs(eq.leftCurve, 100.0, 16000.0) < 4.0)
            assertTrue("seed $seed: left-right ${maxAbs(diff)}", maxAbs(diff) < 2.5)
            assertTrue("seed $seed: preamp ${eq.preampDb}", eq.preampDb > -3.5)
            val band = FreqGrid.hz.indices.filter { FreqGrid.hz[it] in 500.0..8000.0 }
            rms += kotlin.math.sqrt(band.sumOf { eq.leftCurve[it] * eq.leftCurve[it] } / band.size)
        }
        println("normal listeners: mean EQ RMS %.2f dB".format(rms / runs))
        assertTrue(rms / runs < 1.2)
    }

    @Test
    fun highFrequencyLossIsBoostedWithinTheTrebleCap() {
        val eq = ProfileEq(profile(result { _, f -> if (f >= 4000.0) 20.0 else 0.0 }))
        val at6k = Curves.sample(eq.leftCurve, 6000.0)
        assertTrue("6 kHz: $at6k", at6k in 4.0..HearingCompensation.maxBoost(6000.0) + 0.4)
        assertTrue(Curves.sample(eq.leftCurve, 1000.0) in -2.5..1.0)
        for (i in FreqGrid.hz.indices) assertTrue(eq.hearingTargetLeft[i] <= HearingCompensation.maxBoost(FreqGrid.hz[i]) + 1e-9)
        // Fitted filters reproduce the target closely.
        assertTrue(ParametricFit.maxError(eq.hearingTargetLeft, eq.hearingFiltersLeft) < 1.0)
        // A treble boost costs much less level than its size.
        assertTrue("preamp ${eq.preampDb}", eq.preampDb > -Curves.max(eq.leftCurve) + 1.5)
    }

    @Test
    fun bassIsLeftToTasteNotToThresholds() {
        val eq = ProfileEq(profile(result(listOf(125.0, 250.0) + HearingTestConfig.BANDS) { _, f -> if (f <= 250.0) 25.0 else 0.0 }))
        assertEquals(0.0, Curves.sample(eq.hearingTargetLeft, 100.0), 0.01)
        assertTrue(Curves.sample(eq.hearingTargetLeft, 250.0) <= 2.1)
    }

    @Test
    fun aLoneOddBandIsSmoothedNotChased() {
        val eq = ProfileEq(profile(result { ear, f -> if (ear == Ear.LEFT && f == 3000.0) 12.0 else 0.0 }))
        val left = Curves.sample(eq.hearingTargetLeft, 3000.0)
        val right = Curves.sample(eq.hearingTargetRight, 3000.0)
        assertTrue("left $left", left in 0.5..4.5)
        assertTrue("left-right ${left - right}", left - right < 3.0)
    }

    @Test
    fun worseLeftEarGetsMoreGain() {
        val eq = ProfileEq(profile(result { ear, _ -> if (ear == Ear.LEFT) 10.0 else 0.0 }))
        assertTrue("difference ${eq.earDifferenceDb}", eq.earDifferenceDb in 4.0..9.0)
        assertTrue(Curves.sample(eq.leftCurve, 1000.0) > Curves.sample(eq.rightCurve, 1000.0) + 3)
        // What both ears share is one filter set; the difference is a small mirrored set on top.
        assertEquals(eq.linkedFilters, eq.leftFilters.intersect(eq.rightFilters.toSet()).toList())
    }

    @Test
    fun largeLossesGetLessThanFullGain() {
        val mild = ProfileEq(profile(result { _, f -> if (f >= 3000.0) 8.0 else 0.0 }))
        val severe = ProfileEq(profile(result { _, f -> if (f >= 3000.0) 40.0 else 0.0 }))
        val m = Curves.sample(mild.hearingTargetLeft, 4000.0)
        val s = Curves.sample(severe.hearingTargetLeft, 4000.0)
        assertTrue(s > m)
        assertTrue(s <= HearingCompensation.maxBoost(4000.0) + 1e-9)
    }

    @Test
    fun tasteAddsOnTop() {
        val eq = ProfileEq(profile(null, TasteSettings(bass = 6.0)))
        assertEquals(6.0, Curves.sample(eq.leftCurve, 30.0), 0.5)
        assertEquals(0.0, Curves.sample(eq.leftCurve, 3000.0), 0.1)
        assertEquals(eq.leftCurve.toList(), eq.linkedCurve.toList())
    }

    @Test
    fun preampIsSizedForMusic() {
        // Bass carries a master's peaks, so a bass boost needs nearly its full size...
        val bass = ProfileEq(profile(null, TasteSettings(bass = 6.0)))
        assertTrue("bass ${bass.preampDb}", bass.preampDb <= -5.4)
        // ...while a treble lift barely raises them.
        val treble = ProfileEq(profile(null, TasteSettings(treble = 6.0)))
        assertTrue("treble ${treble.preampDb}", treble.preampDb in -3.5..-1.0)
        // Bass and treble boosts together cost about the bass boost, not their sum.
        val both = ProfileEq(profile(null, TasteSettings(bass = 4.5, treble = 4.0)))
        assertTrue("both ${both.preampDb}", both.preampDb in -5.0..-3.5)
        // A boost across the whole range needs its full size; cuts need nothing.
        assertEquals(-3.2, ProfileEq.preampFor(DoubleArray(FreqGrid.SIZE) { 3.0 }), 1e-9)
        assertEquals(0.0, ProfileEq.preampFor(DoubleArray(FreqGrid.SIZE) { -2.0 }), 0.0)
    }

    @Test
    fun legacyResultsStillGiveASaneCurve() {
        // 1.0-1.1 results: 250 Hz and 12 kHz bands, an unheard 8 kHz band stored at maxDb + 5.
        val freqs = listOf(125.0, 250.0, 500.0, 1000.0, 2000.0, 3000.0, 4000.0, 6000.0, 8000.0, 10000.0, 12000.0)
        val pts = mutableListOf<ThresholdPoint>()
        for (ear in listOf(Ear.LEFT, Ear.RIGHT)) for (f in freqs) {
            pts += if (f == 8000.0) ThresholdPoint(f, ear, 2.0, 10.0, ThresholdFlag.NO_RESPONSE)
            else ThresholdPoint(f, ear, -60.0 + ReferenceCurve.at(f, HeadphoneType.IN_EAR), 2.5, ThresholdFlag.OK)
        }
        val eq = ProfileEq(profile(HearingTestResult(pts, 12, 1, 1.0, -2.0, 150, 360, true, -40.0)))
        assertTrue(eq.leftCurve.all { it.isFinite() })
        for (i in FreqGrid.hz.indices) assertTrue(eq.hearingTargetLeft[i] <= HearingCompensation.maxBoost(FreqGrid.hz[i]) + 1e-9)
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val p = profile(testedResult(3) { _, f -> f / 1000.0 }, TasteSettings(1.5, -2.0, 0.5, 3.0), 0.6).copy(
            deviceName = "Buds", deviceType = 8, balanceDb = 1.2, recommendedTaste = TasteSettings(4.5, 0.0, 0.0, -2.5),
        )
        assertTrue(p.hearing!!.trialLog.isNotEmpty())
        val back = Profile.fromJson(p.toJson())
        assertEquals(p, back)
        val a = ProfileEq(p)
        val b = ProfileEq(back)
        for (i in a.leftCurve.indices) assertTrue(abs(a.leftCurve[i] - b.leftCurve[i]) < 1e-9)
    }

    @Test
    fun resetReturnsToTheTestsRecommendation() {
        val tested = TasteSettings(4.5, 0.0, 0.0, -2.5)
        val p = profile(result { _, _ -> 0.0 }, TasteSettings(6.0, 0.0, 2.0, -2.0), 0.4).copy(
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
    fun olderProfilesMoveToTheNewDefaultUnlessTheyChoseTheirOwn() {
        // 1.0 didn't store the taste test's result, and 1.0-1.1 defaulted to 50 % correction.
        val old = profile(result { _, _ -> 0.0 }, TasteSettings(bass = 3.0), 0.5).toJson().apply {
            remove("recommendedTaste")
            remove("fit")
        }
        val p = Profile.fromJson(old)
        assertNull(p.recommendedTaste)
        assertEquals(TasteSettings(bass = 3.0), p.recommended().taste)
        assertEquals(HearingCompensation.DEFAULT_STRENGTH, p.hearingStrength, 0.0)
        val chosen = Profile.fromJson(profile(result { _, _ -> 0.0 }, strength = 0.7).toJson().apply { remove("fit") })
        assertEquals(0.7, chosen.hearingStrength, 0.0)
        // A 1.2 profile at 50 % stays at 50 %.
        assertEquals(0.5, Profile.fromJson(profile(result { _, _ -> 0.0 }, strength = 0.5).toJson()).hearingStrength, 0.0)
    }

    /** A measured in-ear model: Harman bass shelf, a 3 kHz dip to fill and a treble peak to tame. */
    private val model = HeadphoneModel(
        "Test Buds", "oratory1990", null, HeadphoneType.IN_EAR,
        listOf(
            FilterSpec(FilterType.LOW_SHELF, 105.0, 5.0, 0.7),
            FilterSpec(FilterType.PEAK, 3000.0, 5.0, 1.0),
            FilterSpec(FilterType.PEAK, 6500.0, -4.0, 2.0),
        ),
    )

    @Test
    fun aKnownHeadphoneIsNotCorrectedTwice() {
        // A normal listener on these buds: every threshold is off by exactly what the model's correction fixes.
        val shift = HearingCompensation.correctionShift(model.filters)
        val h = result { _, f -> shift(f) - shift(1000.0) }
        val withModel = ProfileEq(profile(h).copy(headphoneModel = model))
        // The hearing part is left with (almost) nothing to do...
        assertTrue("hearing part ${maxAbs(withModel.hearingTargetLeft)}", maxAbs(withModel.hearingTargetLeft, 300.0, 12000.0) < 1.0)
        // ...and the total is the model's correction.
        val correction = Response.of(model.filters, FreqGrid.hz)
        for (f in listOf(1000.0, 3000.0, 6500.0)) {
            assertEquals("$f Hz", Curves.sample(correction, f), Curves.sample(withModel.leftCurve, f), 1.0)
        }
        // Without the model the smooth hearing fit sees only part of such a narrow dip (one band wide);
        // the measured correction is what fills it.
        val without = ProfileEq(profile(h))
        val at3k = Curves.sample(without.hearingTargetLeft, 3000.0)
        assertTrue("without $at3k", at3k in 0.2..2.0)
        // Switched off, the correction leaves the curve and the hearing part takes over again.
        val off = ProfileEq(profile(h).copy(headphoneModel = model, headphoneCorrection = false))
        assertTrue("off filters", off.correctionFilters.isEmpty())
        assertEquals(Curves.sample(without.leftCurve, 3000.0), Curves.sample(off.leftCurve, 3000.0), 1e-9)
    }

    @Test
    fun theModelsCorrectionReachesTheTasteBaseAndTheExports() {
        val eq = ProfileEq(profile(null, TasteSettings(bass = 2.0)).copy(headphoneModel = model))
        assertEquals(model.filters, eq.baseFiltersLeft.take(3))
        assertEquals(model.filters, eq.linkedFilters.take(3))
        assertEquals(eq.leftFilters, eq.baseFiltersLeft + eq.tasteFilters)
        assertEquals(HeadphoneType.IN_EAR, eq.headphones)
    }

    @Test
    fun modelAndSwitchSurviveJsonAndReset() {
        val p = profile(result { _, _ -> 0.0 }).copy(headphoneModel = model, headphoneCorrection = false, recommendedTaste = TasteSettings())
        val back = Profile.fromJson(p.toJson())
        assertEquals(p, back)
        assertFalse(back.isRecommended)
        assertTrue(back.recommended().headphoneCorrection)
    }

    @Test
    fun tasteOnlyProfileIgnoresTheHiddenCorrectionSlider() {
        val p = profile(null, strength = 0.8).copy(recommendedTaste = TasteSettings())
        assertTrue(p.isRecommended)
    }

    /** Sennheiser HD 650 (oratory1990) corrected from its measurement to Harman over-ear 2018, as the app does. */
    private val hd650 by lazy { MeasuredCorrectionTest.model(0) }

    @Test
    fun overEarSubBassStaysNearThe50HzLevelUnlessAskedFor() {
        val p = profile(null, taste = TasteSettings(bass = 5.0)).copy(headphoneModel = hd650)
        val eq = ProfileEq(p)
        val c = eq.leftCurve
        val at50 = Curves.sample(c, 50.0)
        for (f in listOf(20.0, 25.0, 30.0, 40.0)) assertTrue("$f Hz", Curves.sample(c, f) <= at50 + BassGuard.CORNER_ALLOWANCE_DB + 0.15)
        // From about 60 Hz up the bass is as set: the cut sits below it.
        val unguarded = Response.of(eq.baseFiltersLeft + eq.tasteFilters, FreqGrid.hz)
        for (f in listOf(60.0, 100.0, 200.0)) assertEquals("$f Hz", Curves.sample(unguarded, f), Curves.sample(c, f), 1.0)
        // Full sub-bass: no corner, a price in volume the editor can state, still within the ceiling.
        // (Without taste bass, so the ceiling doesn't limit both alike.)
        val plain = ProfileEq(profile(null).copy(headphoneModel = hd650))
        val full = ProfileEq(profile(null).copy(headphoneModel = hd650, fullSubBass = true))
        assertTrue("full ${Curves.sample(full.leftCurve, 20.0)} vs ${Curves.sample(plain.leftCurve, 20.0)}", Curves.sample(full.leftCurve, 20.0) > Curves.sample(plain.leftCurve, 20.0) + 2.0)
        assertTrue("cost ${plain.subBassCostDb}", plain.subBassCostDb > 1.0)
        assertTrue("preamp ${full.preampDb} vs ${plain.preampDb}", full.preampDb < plain.preampDb)
        val ceiling = Curves.max(full.leftCurve, 20.0, 200.0) - Curves.sample(full.leftCurve, 1000.0)
        assertTrue("ceiling $ceiling", ceiling <= BassGuard.ceilingDb(HeadphoneType.OVER_EAR) + 0.15)
        // Both ears get the same cut, and the exports carry it.
        assertEquals(eq.leftFilters.takeLast(eq.guardFilters.size), eq.rightFilters.takeLast(eq.guardFilters.size))
        assertTrue("guard ${eq.guardFilters}", eq.guardFilters.isNotEmpty() && eq.linkedFilters.containsAll(eq.guardFilters))
    }

    @Test
    fun inEarsKeepTheirSubBassUnderTheCeiling() {
        // A bass-light in-ear: the target's +9 dB shelf plus 3 dB more, then a bass-loving taste.
        val buds = HeadphoneModel("Test buds", "test", null, HeadphoneType.IN_EAR, listOf(FilterSpec(FilterType.LOW_SHELF, 105.0, 12.0, 0.7)))
        val eq = ProfileEq(profile(null, taste = TasteSettings(bass = 8.0)).copy(headphoneModel = buds))
        val c = eq.leftCurve
        assertEquals(0.0, eq.subBassCostDb, 0.0)
        // No 50 Hz corner for sealed in-ears: 20 Hz stays level with 50 Hz.
        assertEquals(Curves.sample(c, 50.0), Curves.sample(c, 20.0), 1.0)
        val ceiling = Curves.max(c, 20.0, 200.0) - Curves.sample(c, 1000.0)
        assertTrue("ceiling $ceiling", ceiling <= BassGuard.ceilingDb(HeadphoneType.IN_EAR) + 0.15)
        assertTrue(ceiling > BassGuard.ceilingDb(HeadphoneType.IN_EAR) - 1.0)
    }

    @Test
    fun aMeasuredModelIsNotCorrectedTwice() {
        // A normal listener on HD 650s: every threshold carries exactly what the correction fixes.
        val shift = HearingCompensation.correctionShift(hd650.filters)
        val h = result { _, f -> shift(f) - shift(1000.0) }
        val with = maxAbs(ProfileEq(profile(h).copy(headphoneModel = hd650)).hearingTargetLeft, 300.0, 12000.0)
        val without = maxAbs(ProfileEq(profile(h)).hearingTargetLeft, 300.0, 12000.0)
        println("hearing part through HD 650: %.2f dB with its model, %.2f dB without".format(with, without))
        // Since 1.5 the treble of a fixture's correction applies at half strength, so the difference is smaller but clear.
        assertTrue("with $with", with < 0.35)
        assertTrue("without $without", without > 0.5 && without > 2.5 * with)
        // The whole EQ is then the model's correction, Harman bass included from the corner up; below
        // 50 Hz the bass guard holds an over-ear near its 50 Hz level instead of the full +10.5 dB.
        val eq = ProfileEq(profile(h).copy(headphoneModel = hd650))
        val correction = Response.of(hd650.filters, FreqGrid.hz)
        for (f in listOf(60.0, 105.0, 1000.0, 3000.0, 9000.0)) assertEquals("$f Hz", Curves.sample(correction, f), Curves.sample(eq.leftCurve, f), 0.6)
        assertTrue(Curves.sample(eq.leftCurve, 20.0) <= Curves.sample(eq.leftCurve, 50.0) + BassGuard.CORNER_ALLOWANCE_DB + 0.15)
        // Room for about +7 dB of bass: close to AutoEq's own −6.1 dB, not the −11 dB of the full sub-bass.
        assertTrue("preamp ${eq.preampDb}", eq.preampDb in -7.5..-5.5)
    }

    @Test
    fun tasteFromBefore15KeepsItsTrebleShelf() {
        val chosen = TasteSettings(bass = 2.0, treble = 3.0)
        val p = profile(null, taste = chosen).copy(recommendedTaste = chosen)
        val json = p.toJson().toString()
        fun trebleHz(t: TasteSettings) = t.filters().first { it.type == FilterType.HIGH_SHELF }.freq
        // Saved by 1.5: the broad shelf from 2.5 kHz.
        assertEquals(2500.0, trebleHz(Profile.fromJson(JSONObject(json)).taste), 0.0)
        // Saved before 1.5 (no "shelf"): the 8 kHz shelf it was chosen with, for Reset too.
        val old = Profile.fromJson(JSONObject(json).apply { getJSONObject("taste").remove("shelf"); getJSONObject("recommendedTaste").remove("shelf") })
        assertTrue(old.taste.legacyTreble)
        assertEquals(8000.0, trebleHz(old.taste), 0.0)
        assertTrue(old.isRecommended)
        // A new taste test starts from today's shelf.
        assertFalse(TasteSession(start = old.taste).result().legacyTreble)
    }

    @Test
    fun aBeepPlayedTwiceCountsAsTwoListens() {
        val r = result { _, _ -> 0.0 }.copy(trialLog = listOf(TrialRecord(1000.0, Ear.LEFT, -50.0, true, looks = 2)))
        val back = Profile.fromJson(JSONObject(profile(r).toJson().toString())).hearing!!
        assertEquals(2, back.trialLog.single().looks)
        // Heard on either listen is likelier than on one: at threshold 0.5 → about 0.75.
        val one = Psychometric.pYes(-50.0, -50.0, 0.0)
        assertEquals(1 - (1 - one) * (1 - one), Psychometric.pYes(-50.0, -50.0, 0.0, 2), 1e-12)
    }

    @Test
    fun listeningLevelsAreKeptAndOlderProfilesLoadWithout() {
        val p = profile(result { _, _ -> 0.0 }).copy(
            checkLevel = ListeningLevel(9, 15, -17.5, 8),
            tasteLevel = ListeningLevel(11, 15, null, 8),
        )
        assertEquals(p, Profile.fromJson(JSONObject(p.toJson().toString())))
        val old = Profile.fromJson(p.toJson().apply { remove("levels") })
        assertNull(old.checkLevel)
        assertNull(old.tasteLevel)
        // The levels are what happened, not a setting: Reset leaves them alone.
        assertEquals(p.tasteLevel, p.recommended().tasteLevel)
    }

    @Test
    fun measuredModelsKeepTheirSourceAndOlderOnesStillLoad() {
        val p = profile(result { _, _ -> 0.0 }).copy(headphoneModel = hd650, recommendedTaste = TasteSettings())
        val back = Profile.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p, back)
        assertEquals("oratory1990/over-ear/Sennheiser%20HD%20650", back.headphoneModel?.path)
        assertEquals(hd650.curveDb, back.headphoneModel?.curveDb)
        // The per-device memory stores the same JSON.
        assertEquals(hd650, Profile.modelFrom(JSONObject(Profile.modelJson(hd650).toString())))
        // A 1.3 model (AutoEq's published filters only) loads as it was.
        val old = p.toJson().apply {
            getJSONObject("headphoneModel").apply { remove("path"); remove("target"); remove("curve"); remove("bassDb") }
        }
        val m = Profile.fromJson(old).headphoneModel!!
        assertNull(m.path)
        assertNull(m.curveDb)
        assertEquals(hd650.filters, m.filters)
        // A curve on another grid is dropped, the filters stay.
        val odd = p.toJson().apply { getJSONObject("headphoneModel").put("curve", org.json.JSONArray(listOf(1.0, 2.0))) }
        assertNull(Profile.fromJson(odd).headphoneModel!!.curveDb)
    }
}
