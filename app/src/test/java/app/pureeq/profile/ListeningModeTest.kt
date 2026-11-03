package app.pureeq.profile

import android.media.AudioDeviceInfo
import app.pureeq.audio.ListeningLevel
import app.pureeq.audio.OutputDevice
import app.pureeq.dsp.Curves
import app.pureeq.dsp.Ear
import app.pureeq.dsp.FreqGrid
import app.pureeq.headphones.MeasuredCorrectionTest
import app.pureeq.hearing.HeadphoneType
import app.pureeq.hearing.HearingTestResult
import app.pureeq.hearing.ReferenceCurve
import app.pureeq.hearing.ThresholdFlag
import app.pureeq.hearing.ThresholdPoint
import app.pureeq.hearing.HearingTestConfig
import app.pureeq.taste.TasteSettings
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Listening modes: the same personal tuning, reshaped for what the headphones are mostly used for. */
class ListeningModeTest {

    /** A result given as point estimates: -60 dBFS + reference + [loss] (dB, by ear and band). */
    private fun hearing(loss: (Ear, Double) -> Double): HearingTestResult {
        val points = mutableListOf<ThresholdPoint>()
        for (ear in listOf(Ear.LEFT, Ear.RIGHT)) {
            for (f in HearingTestConfig.BANDS) {
                points += ThresholdPoint(f, ear, -60.0 + ReferenceCurve.at(f, HeadphoneType.IN_EAR) + loss(ear, f), 2.0, ThresholdFlag.OK)
            }
        }
        return HearingTestResult(points, 10, 0, null, null, 120, 300, true, -40.0, headphones = HeadphoneType.IN_EAR)
    }

    /** A listener whose treble is 10 dB down in the left ear and 4 dB in the right, from 2 kHz up. */
    private val result: HearingTestResult = hearing { ear, f -> if (f >= 2000.0) (if (ear == Ear.LEFT) 10.0 else 4.0) else 0.0 }

    private val bt = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    private val tuned = Profile(
        "a", "Buds", 1L, deviceName = "Galaxy Buds2 Pro", deviceType = bt, hearing = result,
        taste = TasteSettings(bass = 6.0, treble = -1.5), recommendedTaste = TasteSettings(bass = 6.0, treble = -1.5),
        headphoneModel = MeasuredCorrectionTest.model(1),
        tasteLevel = ListeningLevel(9, 15, -17.0, bt),
    )

    private fun at(c: DoubleArray, f: Double) = Curves.sample(c, f)

    @Test
    fun musicIsTheProfileAsTunedAndProfilesFromBeforeModesPlayAsMusic() {
        val before = ProfileEq(tuned)
        assertNull(tuned.mode)
        assertEquals(ListeningMode.MUSIC, tuned.playMode)
        val music = ProfileEq(tuned.copy(mode = ListeningMode.MUSIC))
        assertArrayEquals(before.leftCurve, music.leftCurve, 0.0)
        assertArrayEquals(before.rightCurve, music.rightCurve, 0.0)
        assertEquals(before.preampDb, music.preampDb, 0.0)
        assertTrue(ModeTuning.MUSIC.filters.isEmpty())
        assertNull(ModeTuning.MUSIC.leveller)
    }

    @Test
    fun theModeIsSavedWithTheProfile() {
        ListeningMode.entries.forEach { m ->
            assertEquals(m, Profile.fromJson(tuned.copy(mode = m).toJson()).mode)
        }
        // Before 1.7 there was no mode; an unknown one (from a newer PureEQ) reads as not picked.
        assertNull(Profile.fromJson(tuned.toJson()).mode)
        assertNull(Profile.fromJson(JSONObject(tuned.toJson().toString()).put("mode", "KARAOKE")).mode)
        // Reset puts adjustments back; it doesn't change what the headphones are for.
        assertEquals(ListeningMode.GAMING, tuned.copy(mode = ListeningMode.GAMING, balanceDb = 2.0).recommended().mode)
    }

    @Test
    fun theTasteTestHearsNoModeFilters() {
        // The taste test compares on music, so its pairs never carry a mode's own reshaping.
        val taste = TasteSettings(bass = 3.0).filters()
        ListeningMode.entries.forEach { m ->
            val eq = ProfileEq(tuned.copy(mode = m))
            val (l, r) = eq.withTaste(taste)
            assertEquals(m.name, eq.baseFiltersLeft + taste, l.take(eq.baseFiltersLeft.size + taste.size))
            assertEquals(m.name, eq.baseFiltersRight + taste, r.take(eq.baseFiltersRight.size + taste.size))
            assertTrue(m.name, l.drop(eq.baseFiltersLeft.size + taste.size).all { it.type == app.pureeq.dsp.FilterType.LOW_SHELF && it.gainDb < 0 })
        }
    }

    /** Left minus right (dB) on [FreqGrid]. */
    private fun difference(e: ProfileEq) = DoubleArray(FreqGrid.SIZE) { e.leftCurve[it] - e.rightCurve[it] }

    private fun maxAbs(c: DoubleArray, from: Double, to: Double = FreqGrid.MAX_HZ) =
        FreqGrid.hz.indices.filter { FreqGrid.hz[it] in from..to }.maxOf { abs(c[it]) }

    @Test
    fun modesShapeBothEarsAlike() {
        // A mode is a shared reshaping: the difference between the ears is the ear tuning's. This
        // listener's ears differ by less than Gaming's treble limit, so Gaming leaves it as is too.
        val music = ProfileEq(tuned.copy(mode = ListeningMode.MUSIC))
        assertTrue(maxAbs(difference(music), HearingCompensation.DIRECTION_HZ) < ModeTuning.GAMING.maxEarDifferenceDb!!)
        ListeningMode.entries.filter { it.tuning.earTuning == 1.0 }.forEach { m ->
            val eq = ProfileEq(tuned.copy(mode = m))
            for (i in FreqGrid.hz.indices) {
                val d = (eq.leftCurve[i] - eq.rightCurve[i]) - (music.leftCurve[i] - music.rightCurve[i])
                assertTrue("${m.name} at ${FreqGrid.hz[i]} Hz", abs(d) < 1e-9)
            }
        }
    }

    private fun eq(m: ListeningMode, p: Profile = tuned) = ProfileEq(p.copy(mode = m))

    @Test
    fun gamingLimitsHowDifferentlyTheEarsAreTunedInTheTreble() {
        // Only Gaming limits the ear difference; the other modes play the ear tuning as fitted.
        assertEquals(listOf(ListeningMode.GAMING), ListeningMode.entries.filter { it.tuning.maxEarDifferenceDb != null })
        val limit = ModeTuning.GAMING.maxEarDifferenceDb!!
        assertEquals(3.0, limit, 0.0)
        // One ear's treble 20 dB down from 4 kHz (10 at 3 kHz): Music tunes the ears well over 3 dB apart up there.
        for (worse in listOf(Ear.LEFT, Ear.RIGHT)) {
            val sign = if (worse == Ear.LEFT) 1.0 else -1.0
            val p = tuned.copy(hearing = hearing { ear, f -> if (ear == worse) (if (f >= 4000.0) 20.0 else if (f >= 3000.0) 10.0 else 0.0) else 0.0 })
            val music = eq(ListeningMode.MUSIC, p)
            val gaming = eq(ListeningMode.GAMING, p)
            val dm = difference(music)
            val dg = difference(gaming)
            assertTrue("$worse: Music ${maxAbs(dm, HearingCompensation.DIRECTION_HZ)}", maxAbs(dm, HearingCompensation.DIRECTION_HZ) > limit + 1.5)
            // Gaming holds it to 3 dB from 4 kHz up, still favouring the worse ear.
            for (i in FreqGrid.hz.indices) {
                if (FreqGrid.hz[i] >= HearingCompensation.DIRECTION_HZ) assertTrue("$worse at ${FreqGrid.hz[i]} Hz: ${dg[i]}", abs(dg[i]) <= limit + 0.1)
            }
            assertTrue("$worse at 6 kHz: ${at(dg, 6000.0)}", sign * at(dg, 6000.0) > 2.0)
            // Below 1.5 kHz the difference stays within half a dB: only the filters' lower skirts move.
            assertTrue("$worse below 1.5 kHz", maxAbs(DoubleArray(FreqGrid.SIZE) { dg[it] - dm[it] }, 20.0, 1500.0) < 0.5)
            // Both ears meet at their average, so what they share is untouched...
            val shared = { e: ProfileEq -> Curves.average(app.pureeq.dsp.Response.of(e.hearingFiltersLeft, FreqGrid.hz), app.pureeq.dsp.Response.of(e.hearingFiltersRight, FreqGrid.hz)) }
            assertArrayEquals(shared(music), shared(gaming), 1e-6)
            assertArrayEquals(Curves.average(music.hearingTargetLeft, music.hearingTargetRight), Curves.average(gaming.hearingTargetLeft, gaming.hearingTargetRight), 1e-9)
            // ...and Gaming still plays as loud as Music.
            fun loudness(e: ProfileEq) = e.preampDb + app.pureeq.dsp.Loudness.offsetDb(Curves.average(e.leftCurve, e.rightCurve))
            assertEquals(loudness(music), loudness(gaming), 0.15)
            assertTrue(gaming.preampDb <= ProfileEq.preampFor(gaming.leftCurve, gaming.rightCurve))
        }
    }

    @Test
    fun gamingHoldsBassDownKeepsTrebleAndBringsFootstepsForward() {
        val music = eq(ListeningMode.MUSIC).leftCurve
        val gaming = eq(ListeningMode.GAMING).leftCurve
        // A +6 dB taste for bass plays at −3 dB: 9 dB less in the deep bass.
        assertEquals(-9.0, at(gaming, 30.0) - at(music, 30.0), 0.5)
        assertTrue(at(gaming, 100.0) < at(music, 100.0) - 4.0)
        // +2 dB at 2 kHz, and the taste's −1.5 dB treble isn't applied: direction cues stay.
        assertTrue(at(gaming, 2000.0) - at(music, 2000.0) > 2.0)
        for (f in listOf(4000.0, 6000.0, 8000.0, 12000.0)) assertTrue("$f Hz", at(gaming, f) >= at(music, f))
        // A taste for less bass keeps its own; one for less treble plays at 0.
        val lean = ModeTuning.GAMING.taste(TasteSettings(bass = -5.0, treble = -2.0, warmth = 2.0))
        assertEquals(-5.0, lean.bass, 0.0)
        assertEquals(0.0, lean.treble, 0.0)
        assertEquals(0.0, lean.warmth, 0.0)
        assertEquals(3.0, ModeTuning.GAMING.taste(TasteSettings(treble = 5.0)).treble, 0.0)
        // Quicker: 20 ms frames, a 1024-point FFT, about 21 ms of delay.
        assertEquals(1024, app.pureeq.system.DpBands.fftSize(ModeTuning.GAMING.frameMs))
        assertNull(ModeTuning.GAMING.leveller)
    }

    @Test
    fun voiceCutsBoomAddsClarityAndTunesTheEarsFully() {
        val music = eq(ListeningMode.MUSIC)
        val voice = eq(ListeningMode.VOICE)
        assertTrue(at(voice.leftCurve, 60.0) < at(music.leftCurve, 60.0) - 12.0)
        // The low cut stays below the voice: little left of it by 300 Hz besides the −2 dB there.
        assertTrue(at(voice.leftCurve, 500.0) - at(music.leftCurve, 500.0) > -1.0)
        assertTrue(at(voice.leftCurve, 2000.0) - at(music.leftCurve, 2000.0) > 2.0)
        // The ear tuning at full strength: more of the left ear's treble difference is corrected.
        assertEquals(1.0, voice.earTuningStrength, 1e-9)
        assertTrue(at(voice.hearingTargetLeft, 4000.0) > at(music.hearingTargetLeft, 4000.0) + 1.0)
        // No boosts above the voice: treble and air taste only cut.
        val t = ModeTuning.VOICE.taste(TasteSettings(bass = 4.0, treble = 3.0, air = 2.0, vocals = 1.0))
        assertEquals(TasteSettings(bass = 0.0, treble = 0.0, air = 0.0, vocals = 1.0), t)
    }

    @Test
    fun generalAddsATouchOfClarityAndKeepsTheTaste() {
        val music = eq(ListeningMode.MUSIC).leftCurve
        val general = eq(ListeningMode.GENERAL).leftCurve
        assertEquals(1.5, at(general, 2500.0) - at(music, 2500.0), 0.05)
        assertEquals(0.0, at(general, 100.0) - at(music, 100.0), 0.05)
        assertEquals(tuned.taste, ModeTuning.GENERAL.taste(tuned.taste))
    }

    @Test
    fun switchingModesChangesTheBalanceNotTheVolume() {
        // Bass +5 on bass-light over-ears: Music needs a deep preamp for its bass boost. Voice and
        // Gaming take that bass away, and would play several dB louder on their own preamp.
        val p = tuned.copy(taste = TasteSettings(bass = 5.0, treble = 2.0), headphoneModel = MeasuredCorrectionTest.model(0))
        val music = eq(ListeningMode.MUSIC, p)
        fun loudness(e: ProfileEq) = e.preampDb + app.pureeq.dsp.Loudness.offsetDb(Curves.average(e.leftCurve, e.rightCurve))
        ListeningMode.entries.forEach { m ->
            val e = eq(m, p)
            assertEquals(m.name, loudness(music), loudness(e), 0.15)
            // Never above what its own curves allow without clipping.
            assertTrue(m.name, e.preampDb <= ProfileEq.preampFor(e.leftCurve, e.rightCurve))
        }
    }

    @Test
    fun levellersOnlyTurnDownGentlyAndSlowly() {
        ListeningMode.entries.mapNotNull { it.tuning.leveller }.forEach { l ->
            // Low ratios and releases over 200 ms sound least pumped (Souza; Moore & Sęk).
            assertTrue(l.ratio in 1.5f..3f)
            assertTrue(l.releaseMs >= 300f)
            assertTrue(l.thresholdDb <= -18f)
        }
        assertNull(ModeTuning.MUSIC.leveller)
        // Placed where the effect sees the music: after a −5 dB preamp, −24 becomes −29.
        assertEquals(-29f, ModeTuning.GENERAL.leveller!!.shifted(-5.0).thresholdDb, 0f)
    }

    @Test
    fun volumeLinkedBassFollowsTheMode() {
        val buds = OutputDevice("Galaxy Buds2 Pro", bt)
        val quieter = ListeningLevel(5, 15, -33.0, bt)
        ListeningMode.entries.forEach { m ->
            val s = VolumeBass.shelves(tuned.copy(mode = m), buds, quieter)
            assertEquals(m.name, VolumeBass.forDrop(16.0, m.tuning.volumeBass, m.tuning.volumeTreble), s)
        }
    }
}
