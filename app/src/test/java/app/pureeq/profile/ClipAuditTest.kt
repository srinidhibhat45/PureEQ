package app.pureeq.profile

import app.pureeq.dsp.FilterChain
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.dsp.MusicSynth
import app.pureeq.headphones.HeadphoneModel
import app.pureeq.headphones.MeasuredCorrectionTest
import app.pureeq.hearing.HeadphoneType
import app.pureeq.taste.TasteSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How often the music-aware preamp lets peaks through: the built-in loop, mastered as loud as a
 * modern release (about −9 dBFS RMS behind a −0.3 dBFS brickwall limiter), played through
 * demanding profiles with their real filters and preamp. Prints the share of samples above full
 * scale and the worst over, and fails above 0.01 % of samples, the level at which a fully safe
 * preamp (the largest boost plus 0.5 dB) would be worth its lost loudness. On 2026-10-30 only
 * treble +6 with air +6 went over: 0.0004 % of samples, by at most 0.6 dB. Synthesised music, so
 * real masters with denser sub-bass or treble could do worse.
 */
class ClipAuditTest {

    private val rate = 48000

    /** The loop with +[gainDb], then a look-ahead brickwall limiter at [ceilingDb], like a loud master. */
    private fun master(pcm: FloatArray, gainDb: Double, ceilingDb: Double = -0.3): DoubleArray {
        val g = 10.0.pow(gainDb / 20)
        val ceiling = 10.0.pow(ceilingDb / 20)
        val frames = pcm.size / 2
        val need = DoubleArray(frames) { i -> min(1.0, ceiling / max(abs(pcm[2 * i] * g), abs(pcm[2 * i + 1] * g)).coerceAtLeast(1e-9)) }
        val ahead = rate * 5 / 1000
        val release = 1 - 1.0 / (rate * 0.05)
        val out = DoubleArray(pcm.size)
        var env = 1.0
        // Minimum over the look-ahead window, then an instant attack and a 50 ms release.
        val window = ArrayDeque<Int>()
        for (i in 0 until frames + ahead) {
            if (i < frames) {
                while (window.isNotEmpty() && need[window.last()] >= need[i]) window.removeLast()
                window.addLast(i)
            }
            val j = i - ahead
            if (j < 0) continue
            while (window.first() < j) window.removeFirst()
            val target = need[window.first()]
            env = if (target < env) target else min(target, 1 - (1 - env) * release)
            out[2 * j] = pcm[2 * j] * g * env
            out[2 * j + 1] = pcm[2 * j + 1] * g * env
        }
        return out
    }

    private fun db(x: Double) = 20 * log10(x)

    private fun audit(name: String, eq: ProfileEq, music: DoubleArray) {
        val share = overs(name, eq, music)
        assertTrue("$name: ${100 * share} % of samples over full scale", share < 1e-4)
    }

    private fun overs(name: String, eq: ProfileEq, music: DoubleArray): Double {
        val left = FilterChain(eq.leftFilters, rate.toDouble(), 10.0.pow((eq.preampDb + eq.leftTrimDb) / 20))
        val right = FilterChain(eq.rightFilters, rate.toDouble(), 10.0.pow((eq.preampDb + eq.rightTrimDb) / 20))
        var overs = 0
        var peak = 0.0
        // Twice round the loop, counting the second pass, so the filters have settled.
        for (pass in 0 until 2) {
            for (i in 0 until music.size / 2) {
                val l = left.process(music[2 * i])
                val r = right.process(music[2 * i + 1])
                if (pass == 1) {
                    if (abs(l) > 1.0) overs++
                    if (abs(r) > 1.0) overs++
                    peak = max(peak, max(abs(l), abs(r)))
                }
            }
        }
        println("%-46s preamp %6.1f dB   overs %7.4f %%   peak %+5.2f dBFS".format(name, eq.preampDb, 100.0 * overs / music.size, db(peak)))
        return overs.toDouble() / music.size
    }

    @Test
    fun loudMasterThroughDemandingProfiles() {
        val pcm = MusicSynth.render(rate)
        val music = master(pcm, gainDb = 11.0)
        val rms = sqrt(music.sumOf { it * it } / music.size)
        println("master: rms %.1f dBFS, peak %.2f dBFS".format(db(rms), db(music.maxOf { abs(it) })))
        val hd650 = MeasuredCorrectionTest.model(0)
        val buds2 = MeasuredCorrectionTest.model(1)
        val bassLight = HeadphoneModel("Bass-light buds", "test", null, HeadphoneType.IN_EAR, listOf(FilterSpec(FilterType.LOW_SHELF, 105.0, 12.0, 0.7)))
        fun p(taste: TasteSettings, model: HeadphoneModel? = null) = Profile("a", "a", 0L, taste = taste, headphoneModel = model)
        audit("HD 650, bass +5, treble +2", ProfileEq(p(TasteSettings(bass = 5.0, treble = 2.0), hd650)), music)
        audit("HD 650, bass +5, full sub-bass", ProfileEq(p(TasteSettings(bass = 5.0), hd650).copy(fullSubBass = true)), music)
        audit("bass-light in-ear at the bass ceiling", ProfileEq(p(TasteSettings(bass = 8.0), bassLight)), music)
        audit("no model, treble +6, air +6", ProfileEq(p(TasteSettings(treble = 6.0, air = 6.0))), music)
        audit("Galaxy Buds2, vocals +4, treble +3", ProfileEq(p(TasteSettings(vocals = 4.0, treble = 3.0), buds2)), music)
    }
}
