package app.pureeq.profile

import android.media.AudioAttributes.CONTENT_TYPE_MOVIE
import android.media.AudioAttributes.CONTENT_TYPE_MUSIC
import android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION
import android.media.AudioAttributes.CONTENT_TYPE_SPEECH
import android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN
import android.media.AudioAttributes.USAGE_ALARM
import android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
import android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
import android.media.AudioAttributes.USAGE_ASSISTANT
import android.media.AudioAttributes.USAGE_GAME
import android.media.AudioAttributes.USAGE_MEDIA
import android.media.AudioAttributes.USAGE_NOTIFICATION
import android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE
import android.media.AudioAttributes.USAGE_UNKNOWN
import android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
import android.media.AudioDeviceInfo
import app.pureeq.audio.ListeningLevel
import app.pureeq.audio.OutputDevice
import app.pureeq.dsp.Curves
import app.pureeq.dsp.Loudness
import app.pureeq.headphones.MeasuredCorrectionTest
import app.pureeq.taste.TasteSettings
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto: the mode for what's playing, from the attributes every app can see, held steady. */
class AutoModeTest {

    private fun p(usage: Int, content: Int) = AutoMode.Player(usage, content)
    private fun modeFor(vararg players: AutoMode.Player) = AutoMode.modeFor(players.toList())

    @Test
    fun eachKindOfContentPlaysInItsMode() {
        assertEquals(ListeningMode.MUSIC, modeFor(p(USAGE_MEDIA, CONTENT_TYPE_MUSIC)))
        assertEquals(ListeningMode.VOICE, modeFor(p(USAGE_MEDIA, CONTENT_TYPE_SPEECH)))
        assertEquals(ListeningMode.GENERAL, modeFor(p(USAGE_MEDIA, CONTENT_TYPE_MOVIE)))
        // A game is Gaming whatever it says it plays, its UI sounds included.
        listOf(CONTENT_TYPE_UNKNOWN, CONTENT_TYPE_MUSIC, CONTENT_TYPE_SPEECH, CONTENT_TYPE_MOVIE, CONTENT_TYPE_SONIFICATION).forEach {
            assertEquals(ListeningMode.GAMING, modeFor(p(USAGE_GAME, it)))
        }
        // Players without a usage reach the EQ like media.
        assertEquals(ListeningMode.VOICE, modeFor(p(USAGE_UNKNOWN, CONTENT_TYPE_SPEECH)))
    }

    @Test
    fun contentAnAppDoesntNamePlaysAsMusic() {
        // Media3/ExoPlayer's and SoundPool's default, and a MediaPlayer without attributes.
        assertEquals(ListeningMode.MUSIC, AutoMode.FALLBACK)
        assertEquals(AutoMode.FALLBACK, modeFor(p(USAGE_MEDIA, CONTENT_TYPE_UNKNOWN)))
        assertEquals(AutoMode.FALLBACK, modeFor(p(USAGE_UNKNOWN, CONTENT_TYPE_UNKNOWN)))
        // A player that does name its content outweighs one that doesn't.
        assertEquals(ListeningMode.VOICE, modeFor(p(USAGE_MEDIA, CONTENT_TYPE_UNKNOWN), p(USAGE_MEDIA, CONTENT_TYPE_SPEECH)))
    }

    @Test
    fun soundsThatDontReachTheEqChangeNothing() {
        assertNull(modeFor())
        listOf(
            p(USAGE_NOTIFICATION, CONTENT_TYPE_SONIFICATION),
            p(USAGE_NOTIFICATION_RINGTONE, CONTENT_TYPE_MUSIC),
            p(USAGE_ALARM, CONTENT_TYPE_MUSIC),
            p(USAGE_ASSISTANCE_SONIFICATION, CONTENT_TYPE_SONIFICATION),
            p(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, CONTENT_TYPE_SPEECH),
            p(USAGE_ASSISTANT, CONTENT_TYPE_SPEECH),
            p(USAGE_VOICE_COMMUNICATION, CONTENT_TYPE_SPEECH),
            // A media app's click or swoosh.
            p(USAGE_MEDIA, CONTENT_TYPE_SONIFICATION),
        ).forEach { assertNull(it.toString(), modeFor(it)) }
        // Directions spoken over music leave it in Music.
        assertEquals(ListeningMode.MUSIC, modeFor(p(USAGE_MEDIA, CONTENT_TYPE_MUSIC), p(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, CONTENT_TYPE_SPEECH)))
    }

    @Test
    fun aGameWinsAndOtherMixesPlayAsGeneral() {
        assertEquals(ListeningMode.GAMING, modeFor(p(USAGE_MEDIA, CONTENT_TYPE_MUSIC), p(USAGE_GAME, CONTENT_TYPE_UNKNOWN)))
        assertEquals(ListeningMode.GENERAL, modeFor(p(USAGE_MEDIA, CONTENT_TYPE_MUSIC), p(USAGE_MEDIA, CONTENT_TYPE_SPEECH)))
        assertEquals(ListeningMode.GENERAL, modeFor(p(USAGE_MEDIA, CONTENT_TYPE_MUSIC), p(USAGE_MEDIA, CONTENT_TYPE_MOVIE)))
        assertEquals(ListeningMode.MUSIC, modeFor(p(USAGE_MEDIA, CONTENT_TYPE_MUSIC), p(USAGE_MEDIA, CONTENT_TYPE_MUSIC)))
    }

    @Test
    fun theFirstThingHeardIsTakenAtOnce() {
        val s = AutoSwitch()
        assertNull(s.mode)
        s.heard(null, 0)
        assertNull(s.waitMs(0))
        s.heard(ListeningMode.VOICE, 500)
        assertEquals(0L, s.waitMs(500))
        assertEquals(ListeningMode.VOICE, s.take(500))
        assertEquals(ListeningMode.VOICE, s.mode)
    }

    private fun startedIn(m: ListeningMode) = AutoSwitch().apply { heard(m, 0); take(0) }

    @Test
    fun anotherModeTakesOverOnlyAfterPlayingForThreeSeconds() {
        val s = startedIn(ListeningMode.MUSIC)
        s.heard(ListeningMode.VOICE, 10_000)
        assertEquals(AutoSwitch.STABLE_MS, s.waitMs(10_000))
        assertNull(s.take(12_999))
        assertEquals(ListeningMode.MUSIC, s.mode)
        assertEquals(ListeningMode.VOICE, s.take(13_000))
        assertNull(s.take(20_000))
    }

    @Test
    fun hearingTheSameAgainDoesntRestartTheWait() {
        val s = startedIn(ListeningMode.MUSIC)
        s.heard(ListeningMode.GAMING, 1000)
        s.heard(ListeningMode.GAMING, 2500)
        assertEquals(ListeningMode.GAMING, s.take(4000))
    }

    @Test
    fun aBreakOrAReturnStartsTheWaitAgain() {
        val s = startedIn(ListeningMode.MUSIC)
        // A one-second clip, then nothing: no switch, though nothing else plays.
        s.heard(ListeningMode.GENERAL, 1000)
        s.heard(null, 2000)
        assertNull(s.waitMs(2000))
        assertNull(s.take(60_000))
        // Speech, a pause, speech again: three seconds from where it resumed.
        s.heard(ListeningMode.VOICE, 61_000)
        s.heard(null, 62_000)
        s.heard(ListeningMode.VOICE, 62_500)
        assertNull(s.take(64_000))
        assertEquals(ListeningMode.VOICE, s.take(65_500))
        // Back to what already plays before the wait is over: nothing to switch.
        s.heard(ListeningMode.MUSIC, 70_000)
        s.heard(ListeningMode.VOICE, 71_000)
        assertNull(s.waitMs(71_000))
        assertNull(s.take(80_000))
        assertEquals(ListeningMode.VOICE, s.mode)
    }

    @Test
    fun silenceKeepsTheMode() {
        val s = startedIn(ListeningMode.GAMING)
        s.heard(null, 1000)
        assertNull(s.take(3_600_000))
        assertEquals(ListeningMode.GAMING, s.mode)
    }

    @Test
    fun contentFlippingFasterThanTheHoldNeverSwitches() {
        // A game and music taking turns every 2.9 s for ten minutes: the effect is never rebuilt.
        val s = startedIn(ListeningMode.MUSIC)
        var t = 0L
        var switches = 0
        var heard = ListeningMode.GAMING
        while (t < 600_000) {
            s.heard(heard, t)
            t += 2900
            if (s.take(t) != null) switches++
            heard = if (heard == ListeningMode.GAMING) ListeningMode.MUSIC else ListeningMode.GAMING
        }
        assertEquals(0, switches)
        assertEquals(ListeningMode.MUSIC, s.mode)
    }

    @Test
    fun anEveningOfListeningSwitchesOncePerChange() {
        // Music with notifications and track gaps, a podcast, a video, then a game: four switches.
        val heard = listOf(
            0L to ListeningMode.MUSIC, 180_000L to null, 180_400L to ListeningMode.MUSIC,
            600_000L to null, 605_000L to ListeningMode.VOICE, 900_000L to null, 902_000L to ListeningMode.VOICE,
            1_800_000L to ListeningMode.GENERAL, 1_801_000L to null, 1_801_500L to ListeningMode.GENERAL,
            2_400_000L to ListeningMode.GAMING,
        )
        val s = AutoSwitch()
        val picks = mutableListOf<ListeningMode>()
        heard.forEachIndexed { i, (t, m) ->
            s.heard(m, t)
            val next = heard.getOrNull(i + 1)?.first ?: (t + 60_000)
            val wait = s.waitMs(t)
            if (wait != null && t + wait <= next) s.take(t + wait)?.let { picks += it }
        }
        assertEquals(listOf(ListeningMode.MUSIC, ListeningMode.VOICE, ListeningMode.GENERAL, ListeningMode.GAMING), picks)
    }

    private val bt = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    private val tuned = Profile(
        "a", "Buds", 1L, deviceName = "Galaxy Buds2 Pro", deviceType = bt,
        taste = TasteSettings(bass = 5.0, treble = 2.0), recommendedTaste = TasteSettings(bass = 5.0, treble = 2.0),
        headphoneModel = MeasuredCorrectionTest.model(0),
        tasteLevel = ListeningLevel(9, 15, -17.0, bt),
        mode = ListeningMode.VOICE,
    )
    private val auto = tuned.copy(auto = true)

    @Test
    fun autoIsSavedWithTheProfile() {
        assertTrue(Profile.fromJson(auto.toJson()).auto)
        assertEquals(ListeningMode.VOICE, Profile.fromJson(auto.toJson()).mode)
        assertFalse(Profile.fromJson(tuned.toJson()).auto)
        assertFalse(tuned.toJson().has("auto"))
        // Profiles from before Auto, and Auto picked on one from before modes.
        assertFalse(Profile.fromJson(JSONObject(tuned.toJson().toString()).apply { remove("mode") }).auto)
        assertNull(Profile.fromJson(tuned.copy(mode = null, auto = true).toJson()).mode)
        // Reset leaves what the headphones are for alone.
        assertTrue(auto.copy(balanceDb = 2.0).recommended().auto)
    }

    @Test
    fun autoPlaysExactlyAsTheModeItPicked() {
        ListeningMode.entries.forEach { m ->
            val picked = ProfileEq(auto.playing(m))
            val byHand = ProfileEq(tuned.copy(mode = m))
            assertArrayEquals(m.name, byHand.leftCurve, picked.leftCurve, 0.0)
            assertArrayEquals(m.name, byHand.rightCurve, picked.rightCurve, 0.0)
            assertEquals(m.name, byHand.preampDb, picked.preampDb, 0.0)
            assertEquals(m.name, m.tuning, picked.tuning)
        }
        // Until anything is heard, and in exports, which can't follow what's playing: Music.
        assertEquals(ListeningMode.MUSIC, auto.playMode)
        assertEquals(ModeTuning.MUSIC, ProfileEq(auto).tuning)
        assertEquals(ListeningMode.MUSIC, auto.playing(null).mode)
        assertFalse(auto.playing(ListeningMode.GAMING).auto)
        // Without Auto the mode picked by hand plays, whatever Auto has heard.
        assertSame(tuned, tuned.playing(ListeningMode.GAMING))
    }

    @Test
    fun autoSwitchesChangeTheBalanceNotTheVolume() {
        fun loudness(e: ProfileEq) = e.preampDb + Loudness.offsetDb(Curves.average(e.leftCurve, e.rightCurve))
        val music = ProfileEq(auto.playing(ListeningMode.MUSIC))
        ListeningMode.entries.forEach { m ->
            assertEquals(m.name, loudness(music), loudness(ProfileEq(auto.playing(m))), 0.15)
        }
    }

    @Test
    fun volumeLinkedBassFollowsAutosPick() {
        val buds = OutputDevice("Galaxy Buds2 Pro", bt)
        val quieter = ListeningLevel(5, 15, -33.0, bt)
        ListeningMode.entries.forEach { m ->
            assertEquals(m.name, VolumeBass.shelves(tuned.copy(mode = m), buds, quieter), VolumeBass.shelves(auto.playing(m), buds, quieter))
        }
    }
}
