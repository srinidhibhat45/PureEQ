package app.pureeq.audio

import app.pureeq.dsp.Curves
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FreqGrid
import app.pureeq.dsp.Loudness
import app.pureeq.dsp.Response
import app.pureeq.dsp.StereoEqSpec

/** One option in a listening comparison: per-ear filters plus per-ear trims. */
class ListeningOption(
    val left: List<FilterSpec>,
    val right: List<FilterSpec>,
    val leftTrimDb: Double = 0.0,
    val rightTrimDb: Double = 0.0,
) {
    companion object {
        val FLAT = ListeningOption(emptyList(), emptyList())
    }
}

object Matching {
    /**
     * Gains that make every option equally loud on the current music (predicted from its spectrum
     * through K-weighting), with one shared headroom so no option boosts any frequency above 0 dB.
     * Loudness-matching keeps "louder" from winning comparisons by default.
     */
    fun matched(options: List<ListeningOption>, spectrumDb: DoubleArray, fixedHeadroomDb: Double? = null): List<StereoEqSpec> {
        val loud = options.map { loudness(it, spectrumDb) }
        val headroom = fixedHeadroomDb ?: headroom(options, spectrumDb)
        return options.mapIndexed { i, o ->
            StereoEqSpec(o.left, o.right, o.leftTrimDb - loud[i] + headroom, o.rightTrimDb - loud[i] + headroom)
        }
    }

    /**
     * Negative gain that keeps every option's loudness-matched peak at or below 0 dB. A test that
     * fixes this for its whole run (from the most extreme option it can reach) plays every round,
     * and the volume-setting preview, at the same loudness.
     */
    fun headroom(options: List<ListeningOption>, spectrumDb: DoubleArray): Double {
        val peak = options.maxOf { o ->
            val (l, r) = curves(o)
            maxOf(Curves.max(l), Curves.max(r)) - Loudness.offsetDb(Curves.average(l, r), spectrumDb)
        }
        return -maxOf(0.0, peak)
    }

    private fun curves(o: ListeningOption) =
        Response.of(o.left, FreqGrid.hz, o.leftTrimDb) to Response.of(o.right, FreqGrid.hz, o.rightTrimDb)

    private fun loudness(o: ListeningOption, spectrumDb: DoubleArray): Double {
        val (l, r) = curves(o)
        return Loudness.offsetDb(Curves.average(l, r), spectrumDb)
    }
}
