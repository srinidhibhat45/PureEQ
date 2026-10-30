package app.pureeq.dsp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * Minimum-phase FIR filters that reproduce a filter chain's magnitude exactly, and the WAV files
 * that carry them to convolution engines (JamesDSP, ViPER4Android, Equalizer APO). Minimum phase,
 * like the IIR filters themselves, so there is no pre-ringing and no added latency beyond the
 * filters' own.
 */
object ImpulseResponse {
    /** Spectrum resolution: 2.9 Hz per bin at 48 kHz, fine enough for the 105 Hz bass shelf. */
    const val FFT_SIZE = 16384

    /** 171 ms at 48 kHz; the last eighth fades out so the truncation doesn't ring. */
    const val TAPS = 8192

    /**
     * Impulse response of [filters] plus [gainDb] at [sampleRate]. Below 20 Hz the gain never
     * rises above its 20 Hz value, as in the system-wide EQ: no headroom spent on infrasound.
     */
    fun of(filters: List<FilterSpec>, gainDb: Double, sampleRate: Int): DoubleArray {
        val bins = FFT_SIZE / 2 + 1
        val freqs = DoubleArray(bins) { maxOf(it * sampleRate.toDouble() / FFT_SIZE, 1.0) }
        val db = Response.of(filters, freqs, gainDb, sampleRate.toDouble())
        val at20 = Response.of(filters, doubleArrayOf(20.0), gainDb, sampleRate.toDouble())[0]
        for (k in db.indices) if (freqs[k] < 20.0) db[k] = min(db[k], at20)
        return minimumPhase(db)
    }

    /**
     * Minimum-phase taps for a magnitude given in dB at bins k·rate/[FFT_SIZE], k = 0..[FFT_SIZE]/2,
     * by the real-cepstrum (homomorphic) method: fold the cepstrum of ln|H| onto positive time,
     * exponentiate back to a spectrum, and return its first [TAPS] samples.
     */
    fun minimumPhase(gainDb: DoubleArray): DoubleArray {
        val n = FFT_SIZE
        require(gainDb.size == n / 2 + 1)
        val re = DoubleArray(n) { k -> gainDb[if (k <= n / 2) k else n - k] * ln(10.0) / 20 }
        val im = DoubleArray(n)
        inverse(re, im)
        // ln|H| is real and even, so its cepstrum is too; keep 0 and n/2, double the causal half.
        for (i in 0 until n) {
            re[i] *= when {
                i == 0 || i == n / 2 -> 1.0
                i < n / 2 -> 2.0
                else -> 0.0
            }
            im[i] = 0.0
        }
        Fft.transform(re, im)
        for (i in 0 until n) {
            val mag = exp(re[i])
            val ph = im[i]
            re[i] = mag * cos(ph)
            im[i] = mag * sin(ph)
        }
        inverse(re, im)
        val fade = TAPS / 8
        return DoubleArray(TAPS) { i ->
            val w = if (i < TAPS - fade) 1.0 else 0.5 * (1 + cos(PI * (i - (TAPS - fade)) / fade))
            re[i] * w
        }
    }

    /** Inverse FFT through the forward one: conjugate, transform, conjugate, scale. */
    private fun inverse(re: DoubleArray, im: DoubleArray) {
        for (i in im.indices) im[i] = -im[i]
        Fft.transform(re, im)
        val n = re.size.toDouble()
        for (i in re.indices) {
            re[i] /= n
            im[i] = -im[i] / n
        }
    }

    /** A stereo 32-bit float WAV (WAVE_FORMAT_IEEE_FLOAT): what convolution engines load. */
    fun wav(left: DoubleArray, right: DoubleArray, sampleRate: Int): ByteArray {
        require(left.size == right.size)
        val data = left.size * 2 * 4
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(2).putInt(sampleRate).putInt(sampleRate * 8).putShort(8).putShort(32)
        b.put("data".toByteArray()).putInt(data)
        for (i in left.indices) {
            b.putFloat(left[i].toFloat())
            b.putFloat(right[i].toFloat())
        }
        return ByteArrayOutputStream(b.capacity()).apply { write(b.array()) }.toByteArray()
    }
}
