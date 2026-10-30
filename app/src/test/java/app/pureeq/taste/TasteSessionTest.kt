package app.pureeq.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class TasteSessionTest {

    /**
     * A listener with an ideal bass and treble who likes options less the further they are from it
     * (3 dB of bass or 3.5 dB of treble costs one unit), answers "Same" for small differences, and,
     * when [noise] > 0, judges with that much Gaussian noise.
     */
    private fun run(ideal: TasteSettings, seed: Int, noise: Double = 0.0, start: TasteSettings = TasteSettings(), maxRounds: Int = TasteSession.MAX_ROUNDS): Pair<TasteSettings, Int> {
        val rnd = Random(seed)
        val s = TasteSession(start = start, maxRounds = maxRounds, rnd = Random(seed + 100))
        fun u(t: TasteSettings) = -((t.bass - ideal.bass) / 3.0).let { it * it } - ((t.treble - ideal.treble) / 3.0).let { it * it }
        var rounds = 0
        while (true) {
            val c = s.current() ?: break
            assertEquals(start.warmth, c.a.warmth, 0.0)
            assertEquals(start.vocals, c.b.vocals, 0.0)
            val d = u(c.a) - u(c.b) + if (noise > 0) gaussian(rnd) * noise else 0.0
            s.answer(
                when {
                    abs(d) < 0.25 -> Choice.SAME
                    d > 0 -> Choice.A
                    else -> Choice.B
                },
            )
            rounds++
        }
        return s.result() to rounds
    }

    private fun gaussian(rnd: Random): Double {
        var s = 0.0
        repeat(12) { s += rnd.nextDouble() }
        return s - 6.0
    }

    @Test
    fun convergesOnAConsistentListenersPreference() {
        for ((i, ideal) in listOf(TasteSettings(bass = 6.0, treble = -3.0), TasteSettings(bass = -3.0, treble = 2.0), TasteSettings(bass = 1.0, treble = 4.0)).withIndex()) {
            repeat(5) { seed ->
                val (got, rounds) = run(ideal, seed * 10 + i)
                assertTrue("$ideal -> $got", abs(got.bass - ideal.bass) <= 1.5)
                assertTrue("$ideal -> $got", abs(got.treble - ideal.treble) <= 1.75)
                assertTrue(rounds in TasteSession.MIN_ROUNDS..TasteSession.MAX_ROUNDS)
            }
        }
    }

    @Test
    fun aNoisyListenerStillLandsClose() {
        var err = 0.0
        var rounds = 0
        val n = 30
        repeat(n) { seed ->
            val rnd = Random(seed)
            val ideal = TasteSettings(bass = rnd.nextDouble(-4.0, 8.0), treble = rnd.nextDouble(-4.0, 4.0))
            val (got, r) = run(ideal, seed, noise = 0.6 * sqrt(2.0))
            err += sqrt(((got.bass - ideal.bass) / 3.0).let { it * it } + ((got.treble - ideal.treble) / 3.5).let { it * it })
            rounds += r
        }
        println("noisy listener: mean error %.2f units, %.1f rounds".format(err / n, rounds.toDouble() / n))
        assertTrue(err / n < 0.6)
    }

    @Test
    fun noPreferenceStaysNearTheTypicalListener() {
        val s = TasteSession(rnd = Random(1))
        var asked = 0
        while (true) {
            s.current() ?: break
            s.answer(Choice.SAME)
            asked++
        }
        val r = s.result()
        assertTrue(abs(r.bass) <= 1.5)
        assertTrue(abs(r.treble) <= 1.0)
        assertTrue(asked <= TasteSession.MAX_ROUNDS)
    }

    @Test
    fun keepsWarmthAndVocalsAndStopsAtTheRoundLimit() {
        val start = TasteSettings(warmth = 2.0, vocals = -1.0)
        val (got, rounds) = run(TasteSettings(bass = 4.0), 7, noise = 1.5, start = start, maxRounds = 5)
        assertEquals(2.0, got.warmth, 0.0)
        assertEquals(-1.0, got.vocals, 0.0)
        assertTrue(rounds <= 5)
    }
}
