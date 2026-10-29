package app.pureeq.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class TasteSessionTest {

    /** Prefers whichever option is closer to its ideal; can't tell options within 0.75 dB of each other apart. */
    private fun run(ideal: TasteSettings, seed: Int): TasteSettings {
        val s = TasteSession(rnd = Random(seed))
        while (true) {
            val c = s.current() ?: break
            val target = ideal[c.band]
            val da = abs(c.valueA - target)
            val db = abs(c.valueB - target)
            s.answer(
                when {
                    abs(da - db) < 0.75 -> Choice.SAME
                    da < db -> Choice.A
                    else -> Choice.B
                },
            )
        }
        return s.result()
    }

    @Test
    fun convergesOnTheListenersPreference() {
        val ideal = TasteSettings(bass = 8.0, warmth = -2.0, vocals = 1.5, treble = -4.0)
        repeat(10) { seed ->
            val got = run(ideal, seed)
            for (band in TasteBand.entries) {
                assertTrue("$band: ${got[band]} vs ${ideal[band]}", abs(got[band] - ideal[band]) <= 2.0)
            }
        }
    }

    @Test
    fun noPreferenceStaysNeutral() {
        val s = TasteSession(rnd = Random(1))
        var asked = 0
        while (true) {
            s.current() ?: break
            s.answer(Choice.SAME)
            asked++
        }
        assertEquals(TasteSettings(), s.result())
        // Each band: round 1 "same" keeps going, round 2 "same" settles it.
        assertEquals(TasteBand.entries.size * 2, asked)
    }
}
