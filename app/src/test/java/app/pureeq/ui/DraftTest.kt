package app.pureeq.ui

import android.media.AudioDeviceInfo
import app.pureeq.audio.OutputDevice
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.headphones.HeadphoneModel
import app.pureeq.hearing.HeadphoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-device memory of the headphone model, as a new profile's draft applies it. */
class DraftTest {
    private val buds = OutputDevice("Galaxy Buds2 Pro", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    private val cans = OutputDevice("WH-1000XM4", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    private fun model(name: String, type: HeadphoneType) =
        HeadphoneModel(name, "oratory1990", null, type, listOf(FilterSpec(FilterType.PEAK, 3000.0, 2.0, 1.0)), path = "oratory1990/x/$name")

    @Test
    fun aNewProfileStartsWithTheModelPickedForTheseHeadphones() {
        val m = model("Sony WH-1000XM4", HeadphoneType.OVER_EAR)
        val d = Draft(device = cans).withRememberedModel(m)
        assertEquals(m, d.headphoneModel)
        assertTrue(d.modelRemembered)
        assertEquals(HeadphoneType.OVER_EAR, d.headphones)
    }

    @Test
    fun aRememberedModelFollowsTheHeadphonesButAPickStays() {
        val remembered = Draft(device = cans).withRememberedModel(model("Sony WH-1000XM4", HeadphoneType.OVER_EAR))
        // Other headphones without a remembered model: it goes, and the type is guessed again.
        val other = remembered.copy(device = buds).withRememberedModel(null)
        assertNull(other.headphoneModel)
        assertFalse(other.modelRemembered)
        assertEquals(HeadphoneType.IN_EAR, other.headphones)
        // A model picked for this profile isn't replaced by memory.
        val picked = Draft(device = buds, headphoneModel = model("Samsung Galaxy Buds2 Pro", HeadphoneType.IN_EAR))
        assertSame(picked, picked.withRememberedModel(model("Something else", HeadphoneType.OVER_EAR)))
        // Nothing remembered and nothing picked: unchanged.
        val plain = Draft(device = buds)
        assertSame(plain, plain.withRememberedModel(null))
    }
}
