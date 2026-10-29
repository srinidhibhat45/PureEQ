package app.pureeq.dsp

import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow

/**
 * Predicts how much louder or quieter an EQ curve makes music, so A/B comparisons can be
 * loudness-matched. Without this, the louder option usually wins, whatever it sounds like.
 *
 * Model: a long-term music spectrum weighted by the ITU-R BS.1770 K-filter, integrated in power.
 */
object Loudness {

    /** BS.1770 K-weighting (pre-filter shelf + RLB high-pass), in dB on [FreqGrid]. */
    private val kWeightingDb: DoubleArray = Response.of(
        listOf(
            FilterSpec(FilterType.HIGH_SHELF, 1681.974450955533, 3.999843853973347, 0.7071752369554196),
            FilterSpec(FilterType.HIGH_PASS, 38.13547087602444, 0.0, 0.5003270373238773),
        ),
        FreqGrid.hz,
    )

    /**
     * Typical pop/rock long-term average spectrum, as power per log-frequency band in dB:
     * flat (pink) from 40 to 250 Hz, -3 dB/octave above, steep roll-off below 40 Hz.
     */
    val typicalMusicSpectrumDb: DoubleArray = DoubleArray(FreqGrid.SIZE) { i ->
        val f = FreqGrid.hz[i]
        when {
            f < 40.0 -> -12.0 * log2(40.0 / f)
            f <= 250.0 -> 0.0
            else -> -3.0 * log2(f / 250.0)
        }
    }

    /** Loudness change in dB that [curveDb] causes on programme material with [spectrumDb]. */
    fun offsetDb(curveDb: DoubleArray, spectrumDb: DoubleArray = typicalMusicSpectrumDb): Double {
        var num = 0.0
        var den = 0.0
        for (i in curveDb.indices) {
            val w = 10.0.pow((spectrumDb[i] + kWeightingDb[i]) / 10.0)
            num += w * 10.0.pow(curveDb[i] / 10.0)
            den += w
        }
        return 10.0 * log10(num / den)
    }
}
