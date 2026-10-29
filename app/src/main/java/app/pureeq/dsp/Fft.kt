package app.pureeq.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 FFT (size must be a power of two). */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** IEC 61672 A-weighting in dB (0 dB at 1 kHz). */
    fun aWeightingDb(f: Double): Double {
        if (f <= 0) return -200.0
        val f2 = f * f
        val ra = 12194.0.pow(2) * f2 * f2 /
            ((f2 + 20.6 * 20.6) * sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194.0))
        return 20 * log10(max(ra, 1e-12)) + 2.0
    }

    /**
     * Long-term spectrum of interleaved stereo [pcm] as power per log-frequency band in dB on [FreqGrid],
     * smoothed to 1/3 octave. Used to loudness-match A/B on the listener's own song.
     */
    fun longTermSpectrumDb(pcm: FloatArray, sampleRate: Int): DoubleArray {
        val n = 8192
        val frames = pcm.size / 2
        val psd = DoubleArray(n / 2 + 1)
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var count = 0
        var start = 0
        while (start + n <= frames && count < 400) {
            for (k in 0 until n) {
                val i = 2 * (start + k)
                re[k] = (pcm[i] + pcm[i + 1]) * 0.5 * window[k]
                im[k] = 0.0
            }
            transform(re, im)
            for (k in psd.indices) psd[k] += re[k] * re[k] + im[k] * im[k]
            count++
            start += n / 2
        }
        if (count == 0) return Loudness.typicalMusicSpectrumDb.copyOf()
        val binHz = sampleRate.toDouble() / n
        // Power per log band ∝ PSD(f)·f; sample on the grid, interpolating between bins.
        val raw = DoubleArray(FreqGrid.SIZE) { g ->
            val f = FreqGrid.hz[g]
            val pos = (f / binHz).coerceIn(1.0, (psd.size - 2).toDouble())
            val k = pos.toInt()
            val t = pos - k
            val p = psd[k] * (1 - t) + psd[k + 1] * t
            10 * log10(max(p * f, 1e-30))
        }
        val smooth = Curves.smooth(raw, 1.0 / 3.0)
        val peak = smooth.max()
        return DoubleArray(smooth.size) { smooth[it] - peak }
    }
}
