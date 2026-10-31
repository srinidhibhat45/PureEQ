package app.pureeq.profile

import android.media.AudioDeviceInfo
import app.pureeq.audio.ListeningLevel
import app.pureeq.audio.OutputDevice
import app.pureeq.dsp.Curves
import app.pureeq.dsp.FreqGrid
import app.pureeq.system.SystemCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bass that follows the volume, from the taste test's volume down. */
class VolumeBassTest {
    private val bt = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    private val buds = OutputDevice("Galaxy Buds2 Pro", bt)
    private val wired = OutputDevice("Wired headphones", AudioDeviceInfo.TYPE_WIRED_HEADPHONES)

    private fun level(index: Int, db: Double?, type: Int = bt) = ListeningLevel(index, 15, db, type)
    private val tuned = Profile("a", "Buds", 1L, deviceName = buds.name, deviceType = bt, tasteLevel = level(9, -17.0))

    @Test
    fun halfTheLoudnessContoursLossComesBackWithinLimits() {
        assertTrue(VolumeBass.forDrop(0.0).isFlat)
        // 20 dB quieter: 0.5 × 0.31 × 20 = 3.1 dB of bass, 0.5 × 0.07 × 20 = 0.7 dB of treble.
        VolumeBass.forDrop(20.0).let {
            assertEquals(3.1, it.bassDb, 1e-9)
            assertEquals(0.7, it.trebleDb, 1e-9)
        }
        assertEquals(VolumeBass.MAX_BASS_DB, VolumeBass.forDrop(60.0).bassDb, 1e-9)
        assertEquals(VolumeBass.MAX_TREBLE_DB, VolumeBass.forDrop(100.0).trebleDb, 1e-9)
        // Louder than the tuning volume: a little less bass, never less treble.
        VolumeBass.forDrop(-10.0).let {
            assertEquals(-1.55, it.bassDb, 1e-9)
            assertEquals(0.0, it.trebleDb, 1e-9)
        }
        assertEquals(VolumeBass.MIN_BASS_DB, VolumeBass.forDrop(-40.0).bassDb, 1e-9)
    }

    @Test
    fun theDropComesFromAndroidsDecibelsOrTheDefaultCurve() {
        assertEquals(16.0, VolumeBass.dropDb(level(9, -17.0), level(5, -33.0))!!, 1e-9)
        // Without Android's figures: AOSP's default curve, 60 % → -17 dB and 20 % → -40 dB.
        assertEquals(23.0, VolumeBass.dropDb(level(9, null), level(3, null))!!, 1e-9)
        assertEquals(0.0, VolumeBass.dropDb(level(15, null), level(15, null))!!, 1e-9)
    }

    @Test
    fun onlyForTheKindOfOutputItWasTunedOnAndWhenOn() {
        val quieter = level(5, -33.0)
        assertEquals(VolumeBass.forDrop(16.0), VolumeBass.shelves(tuned, buds, quieter))
        // Another kind of output has another volume curve and other headphones.
        assertEquals(VolumeBass.FLAT, VolumeBass.shelves(tuned, wired, level(5, -33.0, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
        // Switched off, or no tuning volume (profiles from before 1.5, or the taste test skipped).
        assertEquals(VolumeBass.FLAT, VolumeBass.shelves(tuned.copy(volumeBass = false), buds, quieter))
        assertEquals(VolumeBass.FLAT, VolumeBass.shelves(tuned.copy(tasteLevel = null), buds, quieter))
        assertEquals(VolumeBass.FLAT, VolumeBass.shelves(tuned, buds, null))
    }

    @Test
    fun smallVolumeMovesDontRebuildTheEffect() {
        assertEquals(VolumeBass.Shelves(3.0, 0.75), VolumeBass.forDrop(20.0).rounded())
        // A volume step's worth of change within the same 0.25 dB keeps the effect as it is.
        assertEquals(VolumeBass.forDrop(20.0).rounded(), VolumeBass.forDrop(19.0).rounded())
    }

    @Test
    fun theBoostNeverOutgrowsTheHeadroomTheLowerVolumeLeaves() {
        // Where Android applies the volume before the effect, listening d dB below the tuning
        // volume leaves at least d dB of headroom; the boost must stay below it.
        var d = 0.0
        while (d <= 80.0) {
            val s = VolumeBass.forDrop(d)
            assertTrue("$d dB down", maxOf(s.bassDb, s.trebleDb) <= d)
            d += 0.5
        }
        assertTrue(VolumeBass.needsHeadroom(buds))
        assertFalse(VolumeBass.needsHeadroom(wired))
    }

    @Test
    fun theShelvesShapeBothEarsAndMakeRoomOnBluetooth() {
        val base = SystemCurves(DoubleArray(FreqGrid.SIZE) { -3.0 }, DoubleArray(FreqGrid.SIZE) { -4.0 })
        val s = VolumeBass.forDrop(20.0)
        val plain = base.with(s, headroom = false)
        assertEquals(-3.0 + 3.1, Curves.sample(plain.left, 20.0), 0.1)
        assertEquals(-4.0 + 3.1, Curves.sample(plain.right, 20.0), 0.1)
        assertEquals(-3.0, Curves.sample(plain.left, 1000.0), 0.15)
        // Full-scale outputs lose the largest boost, so the bass stays where it was and the rest drops.
        val roomy = base.with(s, headroom = true)
        assertEquals(-3.0, Curves.sample(roomy.left, 20.0), 0.1)
        assertEquals(-3.0 - 3.1, Curves.sample(roomy.left, 1000.0), 0.15)
        assertSame(base, base.with(VolumeBass.FLAT, headroom = true))
    }

    @Test
    fun theSwitchIsSavedAndResetTurnsItBackOn() {
        val off = tuned.copy(volumeBass = false)
        assertFalse(Profile.fromJson(org.json.JSONObject(off.toJson().toString())).volumeBass)
        assertTrue(Profile.fromJson(org.json.JSONObject(tuned.toJson().toString())).volumeBass)
        assertTrue(off.recommended().volumeBass)
        assertFalse(off.isRecommended)
    }
}
