package app.pureeq.profile

import android.media.AudioDeviceInfo
import app.pureeq.audio.OutputDevice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** On or off for each output, and the profile each one comes back to. */
class OutputStatesTest {
    private val speaker = OutputDevice("Phone speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val buds = OutputDevice("Galaxy Buds2 Pro", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    private val cans = OutputDevice("WH-1000XM4", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    private val car = OutputDevice("My Car", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    private val budsA = Profile("a", "Buds", 1L, deviceName = buds.name, deviceType = buds.type)
    private val budsB = Profile("b", "Buds, gym", 2L, deviceName = buds.name, deviceType = buds.type)
    private val sony = Profile("c", "Sony", 3L, deviceName = cans.name, deviceType = cans.type)
    private val profiles = listOf(budsA, budsB, sony)

    @Test
    fun theSpeakerStartsOffAndHeadphonesWithAProfileFollowTheOthers() {
        val none = OutputStates()
        assertFalse(none.armed)
        // Nothing turned on yet: off everywhere, even for headphones a profile was made with.
        listOf(speaker, buds, cans, car).forEach { assertFalse(it.name, none.isOn(it, profiles)) }

        val budsOn = none.turned(buds, true)
        assertTrue(budsOn.armed)
        assertTrue(budsOn.isOn(buds, profiles))
        // The bug this fixes: the headphones' profile on the phone speaker.
        assertFalse(budsOn.isOn(speaker, profiles))
        // Other headphones with a profile come on with it; an output without one stays off.
        assertTrue(budsOn.isOn(cans, profiles))
        assertFalse(budsOn.isOn(car, profiles))
    }

    @Test
    fun eachOutputKeepsItsLastState() {
        val s = OutputStates().turned(buds, true).turned(speaker, true).turned(cans, false)
        assertTrue(s.isOn(speaker, profiles))
        assertTrue(s.isOn(buds, profiles))
        assertFalse(s.isOn(cans, profiles))
        // Off with the buds; still on for the speaker, so the service keeps waiting.
        val budsOff = s.turned(buds, false)
        assertFalse(budsOff.isOn(buds, profiles))
        assertTrue(budsOff.armed)
        assertEquals(listOf(OutputStates.SPEAKER), budsOff.onElsewhere(buds, profiles))
    }

    @Test
    fun turningOffTheLastOutputTurnsPureEqOff() {
        val s = OutputStates().turned(buds, true).turned(buds, false)
        assertFalse(s.armed)
        // Headphones a profile was made with no longer come on by themselves.
        assertFalse(s.isOn(cans, profiles))
        assertEquals(emptyList<String>(), s.onElsewhere(speaker, profiles))
        // Off everywhere keeps what each output played.
        val all = OutputStates().used(buds, budsB.id).turned(speaker, true).allOff()
        assertFalse(all.armed)
        assertEquals(budsB.id, all.byKey.getValue(buds.name).profileId)
    }

    @Test
    fun anOutputComesBackToTheProfileLastUsedThere() {
        // Never played: the active profile if it was made with these headphones, else the first made with them.
        assertEquals(budsB, OutputStates().profileFor(buds, profiles, activeId = budsB.id))
        assertEquals(budsA, OutputStates().profileFor(buds, profiles, activeId = sony.id))
        assertNull(OutputStates().profileFor(car, profiles, activeId = sony.id))
        assertNull(OutputStates().profileFor(speaker, profiles, activeId = sony.id))
        // Played with the gym profile last: that one, whatever is active.
        val used = OutputStates().used(buds, budsB.id).used(car, sony.id)
        assertTrue(used.isOn(buds, profiles))
        assertEquals(budsB, used.profileFor(buds, profiles, activeId = sony.id))
        assertEquals(sony, used.profileFor(car, profiles, activeId = budsA.id))
        // A deleted profile is forgotten: back to the rule above.
        assertEquals(budsA, used.without(budsB.id).profileFor(buds, profiles - budsB, activeId = sony.id))
    }

    @Test
    fun thePhoneSpeakerIsOneOutputWhateverItsCalled() {
        val model = OutputDevice("SM-S918B", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val earpiece = OutputDevice("Headphones", AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val s = OutputStates().turned(model, true)
        assertTrue(s.isOn(speaker, profiles))
        assertTrue(s.isOn(earpiece, profiles))
        assertEquals(OutputStates.SPEAKER, OutputStates.keyOf(model))
        // A profile can't claim the speaker by name.
        val odd = Profile("x", "Odd", 4L, deviceName = "Phone speaker")
        assertFalse(OutputStates().turned(buds, true).isOn(speaker, listOf(odd)))
    }

    @Test
    fun recordingWhatPlayedChangesNothingWhenItsAlreadyKnown() {
        val s = OutputStates().used(buds, budsA.id)
        assertSame(s, s.used(buds, budsA.id))
        assertSame(s, s.turned(buds, true))
    }

    @Test
    fun savedAndCarriedOverFromTheOldSingleSwitch() {
        val s = OutputStates().used(buds, budsB.id).turned(speaker, false).turned(car, true)
        assertEquals(s, OutputStates.fromJson(JSONObject(s.toJson().toString())))
        // 1.5's switch was on: on for each pair a profile was made with, off for the speaker.
        val migrated = OutputStates.migrated(wasOn = true, profiles)
        assertTrue(migrated.isOn(buds, profiles))
        assertTrue(migrated.isOn(cans, profiles))
        assertFalse(migrated.isOn(speaker, profiles))
        assertFalse(migrated.isOn(car, profiles))
        assertEquals(OutputStates(), OutputStates.migrated(wasOn = false, profiles))
    }

    @Test
    fun listsThePlayingOutputFirstThenTheSpeakerAndTheRest() {
        val s = OutputStates().turned(car, true)
        assertEquals(listOf(cans.name, "Phone speaker", car.name, buds.name), s.known(profiles, cans).map { it.name })
        assertEquals(listOf("Phone speaker", car.name, buds.name, cans.name), s.known(profiles, speaker).map { it.name })
        // A profile saved with the speaker's type (made before headphones were required) is still headphones.
        val odd = Profile("x", "Old", 5L, deviceName = "Old buds", deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val listed = OutputStates().known(listOf(odd), speaker)
        assertEquals(listOf("Phone speaker", "Old buds"), listed.map { it.name })
        assertTrue(listed[1].isHeadphones)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, OutputStates.migrated(wasOn = true, listOf(odd)).byKey.getValue("Old buds").type)
    }
}
