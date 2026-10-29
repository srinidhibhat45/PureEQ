package app.pureeq.dsp

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/** Log-spaced frequency axis from 20 Hz to 20 kHz, about 1/48 octave per step. Every curve is sampled on it. */
object FreqGrid {
    const val MIN_HZ = 20.0
    const val MAX_HZ = 20000.0
    const val SIZE = 481

    private val octaves = log2(MAX_HZ / MIN_HZ)
    val stepOctaves = octaves / (SIZE - 1)
    val hz: DoubleArray = DoubleArray(SIZE) { MIN_HZ * 2.0.pow(it * stepOctaves) }

    /** Fractional grid position of [freq]. */
    fun position(freq: Double): Double = log2(freq / MIN_HZ) / stepOctaves

    fun nearestIndex(freq: Double): Int = position(freq).roundToInt().coerceIn(0, SIZE - 1)
}

object Curves {

    /** Value of a [FreqGrid] curve at any frequency, linear in log-frequency, held at the ends. */
    fun sample(db: DoubleArray, freq: Double): Double {
        val p = FreqGrid.position(freq).coerceIn(0.0, (FreqGrid.SIZE - 1).toDouble())
        val i = floor(p).toInt().coerceAtMost(FreqGrid.SIZE - 2)
        val t = p - i
        return db[i] * (1 - t) + db[i + 1] * t
    }

    /** Fractional-octave smoothing (centred moving average in log-frequency, edges use a shrinking window). */
    fun smooth(db: DoubleArray, octaves: Double): DoubleArray {
        if (octaves <= 0.0) return db.copyOf()
        val half = (octaves / 2 / FreqGrid.stepOctaves).roundToInt().coerceAtLeast(1)
        val prefix = DoubleArray(db.size + 1)
        for (i in db.indices) prefix[i + 1] = prefix[i] + db[i]
        return DoubleArray(db.size) { i ->
            val lo = (i - half).coerceAtLeast(0)
            val hi = (i + half).coerceAtMost(db.size - 1)
            (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
        }
    }

    fun add(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(a.size) { a[it] + b[it] }

    fun average(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(a.size) { (a[it] + b[it]) / 2 }

    /** Maximum of [db] between [fromHz] and [toHz]. */
    fun max(db: DoubleArray, fromHz: Double = FreqGrid.MIN_HZ, toHz: Double = FreqGrid.MAX_HZ): Double {
        var m = Double.NEGATIVE_INFINITY
        for (i in db.indices) {
            val f = FreqGrid.hz[i]
            if (f in fromHz..toHz && db[i] > m) m = db[i]
        }
        return m
    }

    /**
     * Monotone piecewise-cubic (Fritsch–Carlson PCHIP) interpolation of points (xs, ys) at [x].
     * It never overshoots between measured points, so an interpolated EQ never invents peaks.
     * Outside the range the end values are held.
     */
    fun pchip(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        val n = xs.size
        require(n == ys.size && n > 0)
        if (n == 1 || x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        val d = pchipSlopes(xs, ys)
        var k = 0
        while (k < n - 2 && x > xs[k + 1]) k++
        val h = xs[k + 1] - xs[k]
        val t = (x - xs[k]) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2 * t3 - 3 * t2 + 1
        val h10 = t3 - 2 * t2 + t
        val h01 = -2 * t3 + 3 * t2
        val h11 = t3 - t2
        return h00 * ys[k] + h10 * h * d[k] + h01 * ys[k + 1] + h11 * h * d[k + 1]
    }

    private fun pchipSlopes(xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val n = xs.size
        val h = DoubleArray(n - 1) { xs[it + 1] - xs[it] }
        val delta = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / h[it] }
        val d = DoubleArray(n)
        if (n == 2) {
            d[0] = delta[0]
            d[1] = delta[0]
            return d
        }
        for (k in 1 until n - 1) {
            if (delta[k - 1] * delta[k] <= 0) {
                d[k] = 0.0
            } else {
                val w1 = 2 * h[k] + h[k - 1]
                val w2 = h[k] + 2 * h[k - 1]
                d[k] = (w1 + w2) / (w1 / delta[k - 1] + w2 / delta[k])
            }
        }
        d[0] = endSlope(h[0], h[1], delta[0], delta[1])
        d[n - 1] = endSlope(h[n - 2], h[n - 3], delta[n - 2], delta[n - 3])
        return d
    }

    private fun endSlope(h0: Double, h1: Double, d0: Double, d1: Double): Double {
        var s = ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
        if (sign(s) != sign(d0)) {
            s = 0.0
        } else if (sign(d0) != sign(d1) && abs(s) > abs(3 * d0)) {
            s = 3 * d0
        }
        return s
    }
}
