package app.pureeq.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Filter shapes. The first three are the ones exported; codes match Equalizer APO (PK, LSC, HSC). */
enum class FilterType(val apoCode: String) {
    PEAK("PK"),
    LOW_SHELF("LSC"),
    HIGH_SHELF("HSC"),
    HIGH_PASS("HP"),
}

/**
 * A parametric filter as a user sees it.
 * [freq] is the centre (peak) or the shelf midpoint, [q] is the RBJ cookbook Q.
 */
data class FilterSpec(
    val type: FilterType,
    val freq: Double,
    val gainDb: Double,
    val q: Double,
)

/**
 * Biquad coefficients normalised to a0 = 1, designed with the RBJ Audio EQ Cookbook.
 * Equalizer APO, AutoEq, Poweramp Equalizer and Wavelet use the same formulas, so a curve
 * computed here is the curve those apps reproduce.
 */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    /** Magnitude response in dB at [freq] Hz. */
    fun magnitudeDb(freq: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * freq / sampleRate
        val c1 = cos(w)
        val c2 = cos(2.0 * w)
        val num = b0 * b0 + b1 * b1 + b2 * b2 + 2.0 * b1 * (b0 + b2) * c1 + 2.0 * b0 * b2 * c2
        val den = 1.0 + a1 * a1 + a2 * a2 + 2.0 * a1 * (1.0 + a2) * c1 + 2.0 * a2 * c2
        return 10.0 * log10(max(num, 1e-30) / max(den, 1e-30))
    }

    companion object {
        fun design(spec: FilterSpec, sampleRate: Double): Biquad {
            // Keep the design frequency below Nyquist (matters for 16 kHz shelves at 44.1 kHz).
            val f0 = spec.freq.coerceIn(1.0, sampleRate * 0.49)
            val q = spec.q.coerceAtLeast(0.05)
            val w0 = 2.0 * PI * f0 / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a = 10.0.pow(spec.gainDb / 40.0)
            val b0: Double
            val b1: Double
            val b2: Double
            val a0: Double
            val a1: Double
            val a2: Double
            when (spec.type) {
                FilterType.PEAK -> {
                    b0 = 1.0 + alpha * a
                    b1 = -2.0 * cw
                    b2 = 1.0 - alpha * a
                    a0 = 1.0 + alpha / a
                    a1 = -2.0 * cw
                    a2 = 1.0 - alpha / a
                }
                FilterType.LOW_SHELF -> {
                    val s = 2.0 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cw + s)
                    b1 = 2.0 * a * ((a - 1) - (a + 1) * cw)
                    b2 = a * ((a + 1) - (a - 1) * cw - s)
                    a0 = (a + 1) + (a - 1) * cw + s
                    a1 = -2.0 * ((a - 1) + (a + 1) * cw)
                    a2 = (a + 1) + (a - 1) * cw - s
                }
                FilterType.HIGH_SHELF -> {
                    val s = 2.0 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cw + s)
                    b1 = -2.0 * a * ((a - 1) + (a + 1) * cw)
                    b2 = a * ((a + 1) + (a - 1) * cw - s)
                    a0 = (a + 1) - (a - 1) * cw + s
                    a1 = 2.0 * ((a - 1) - (a + 1) * cw)
                    a2 = (a + 1) - (a - 1) * cw - s
                }
                FilterType.HIGH_PASS -> {
                    b0 = (1.0 + cw) / 2.0
                    b1 = -(1.0 + cw)
                    b2 = (1.0 + cw) / 2.0
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cw
                    a2 = 1.0 - alpha
                }
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        /** Constant-peak-gain band-pass (RBJ BPF, 0 dB at [freq]); used by the synthesiser. */
        fun bandPass(freq: Double, q: Double, sampleRate: Double): Biquad {
            val w0 = 2.0 * PI * freq.coerceIn(1.0, sampleRate * 0.49) / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(alpha / a0, 0.0, -alpha / a0, -2.0 * cos(w0) / a0, (1.0 - alpha) / a0)
        }

        /** RBJ low-pass; used by the synthesiser. */
        fun lowPass(freq: Double, q: Double, sampleRate: Double): Biquad {
            val w0 = 2.0 * PI * freq.coerceIn(1.0, sampleRate * 0.49) / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad((1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
        }
    }
}

/** Frequency responses of filter lists, evaluated at a reference rate shared by every app we export to. */
object Response {
    const val REFERENCE_RATE = 48000.0

    /** Summed dB response of [filters] plus [preampDb] at each of [freqs]. */
    fun of(
        filters: List<FilterSpec>,
        freqs: DoubleArray,
        preampDb: Double = 0.0,
        sampleRate: Double = REFERENCE_RATE,
    ): DoubleArray {
        val out = DoubleArray(freqs.size) { preampDb }
        for (spec in filters) {
            if (spec.gainDb == 0.0 && spec.type != FilterType.HIGH_PASS) continue
            val bq = Biquad.design(spec, sampleRate)
            for (i in freqs.indices) out[i] += bq.magnitudeDb(freqs[i], sampleRate)
        }
        return out
    }
}

/**
 * A cascade of biquads for one channel in transposed direct form II.
 * State is double precision so low shelves stay clean at 48 kHz.
 */
class FilterChain(filters: List<FilterSpec>, sampleRate: Double, val gain: Double) {
    private val n = filters.count { it.gainDb != 0.0 }
    private val c = DoubleArray(n * 5)
    private val z = DoubleArray(n * 2)

    init {
        var i = 0
        for (spec in filters) {
            if (spec.gainDb == 0.0) continue
            val bq = Biquad.design(spec, sampleRate)
            c[i * 5] = bq.b0
            c[i * 5 + 1] = bq.b1
            c[i * 5 + 2] = bq.b2
            c[i * 5 + 3] = bq.a1
            c[i * 5 + 4] = bq.a2
            i++
        }
    }

    fun process(x: Double): Double {
        // A -500 dB offset keeps the recursive state out of the denormal range during silence.
        var y = x + DENORMAL_GUARD
        var ci = 0
        var zi = 0
        for (i in 0 until n) {
            val out = c[ci] * y + z[zi]
            z[zi] = c[ci + 1] * y - c[ci + 3] * out + z[zi + 1]
            z[zi + 1] = c[ci + 2] * y - c[ci + 4] * out
            y = out
            ci += 5
            zi += 2
        }
        return y * gain
    }

    fun reset() = z.fill(0.0)

    private companion object {
        const val DENORMAL_GUARD = 1e-25
    }
}

/** Stereo EQ settings: separate filter lists per ear plus a per-ear output gain. */
data class StereoEqSpec(
    val left: List<FilterSpec>,
    val right: List<FilterSpec>,
    val leftGainDb: Double,
    val rightGainDb: Double,
) {
    companion object {
        fun flat(gainDb: Double = 0.0) = StereoEqSpec(emptyList(), emptyList(), gainDb, gainDb)
    }
}

fun dbToGain(db: Double): Double = 10.0.pow(db / 20.0)
