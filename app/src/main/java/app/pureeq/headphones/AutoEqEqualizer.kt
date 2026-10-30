package app.pureeq.headphones

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * A port of AutoEq's FrequencyResponse.equalize() and the helpers it calls (autoeq/frequency_response.py
 * and autoeq/utils.py at commit 7ae0f56d), with scipy's savgol_filter (mode "interp") and find_peaks
 * (prominence) as AutoEq uses them. Only AutoEq's defaults are ported: no slope decay, no concha
 * interference, treble gain factor 1.
 *
 * AutoEq is MIT licensed:
 *
 * Copyright (c) 2018-2022 Jaakko Pasanen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/**
 * AutoEq's equalization curve: the inverse of a headphone's error against a target, made safe to
 * apply. The error is smoothed (Savitzky-Golay, 1/12 octave, blending to 2 octaves between 6 and
 * 8 kHz where measurements are least reliable), slopes steeper than 18 dB/octave are limited on
 * the way into each boost so narrow notches aren't filled, boosts stop at +6 dB, and a final
 * 1/5-octave smoothing removes the kinks.
 */
object AutoEqEqualizer {
    const val MAX_GAIN_DB = 6.0
    const val MAX_SLOPE_DB_PER_OCT = 18.0
    const val WINDOW_OCT = 1.0 / 12
    const val TREBLE_WINDOW_OCT = 2.0
    const val TREBLE_F_LOWER = 6000.0
    const val TREBLE_F_UPPER = 8000.0
    const val FINAL_WINDOW_OCT = 1.0 / 5

    /** AutoEq's frequency grid: 20 Hz, then ×1.01 while at most 20 kHz (695 points), accumulated as AutoEq does. */
    val GRID: DoubleArray = run {
        val out = ArrayList<Double>()
        var f = 20.0
        while (f <= 20000.0) {
            out += f
            f *= 1.01
        }
        out.toDoubleArray()
    }

    class Result(
        /** The equalization curve (dB) on the given frequencies. */
        val curve: DoubleArray,
        /** The error after AutoEq's smoothing: the deviation the curve corrects. */
        val smoothedError: DoubleArray,
    )

    /** Equalization for [error] (raw − target, dB) on log-spaced [freqs] (normally [GRID]). */
    fun equalize(freqs: DoubleArray, error: DoubleArray, maxGain: Double = MAX_GAIN_DB, maxSlope: Double = MAX_SLOPE_DB_PER_OCT): Result {
        require(freqs.size == error.size && freqs.size >= 3)
        val smoothed = smoothen(freqs, error, WINDOW_OCT, TREBLE_WINDOW_OCT)
        val y = DoubleArray(freqs.size) { -smoothed[it] }
        val peaks = findPeaks(y, 1.0)
        val dips = findPeaks(DoubleArray(y.size) { -y[it] }, 1.0)
        // AutoEq uses a curve without peaks or dips as it is.
        if (peaks.isEmpty() && dips.isEmpty()) return Result(y, smoothed)
        val free = protectionMask(y, peaks, dips)
        val ltr = limitLeftToRight(freqs, y, maxSlope, 0, peaks, free)
        val rtl = limitRightToLeft(freqs, y, maxSlope, rtlStart(y, peaks, dips), peaks, free)
        val combined = DoubleArray(y.size) { min(min(ltr[it], rtl[it]), maxGain) }
        return Result(smoothen(freqs, combined, FINAL_WINDOW_OCT, FINAL_WINDOW_OCT), smoothed)
    }

    /** AutoEq's smoothing: Savitzky-Golay (order 2) at [window] octaves, blending to [trebleWindow] between 6 and 8 kHz. */
    fun smoothen(freqs: DoubleArray, data: DoubleArray, window: Double, trebleWindow: Double): DoubleArray {
        val normal = savgol(data, windowSize(freqs, window))
        val treble = savgol(data, windowSize(freqs, trebleWindow))
        val k = logFSigmoid(freqs, TREBLE_F_LOWER, TREBLE_F_UPPER)
        return DoubleArray(data.size) { normal[it] * (-k[it] + 1) + treble[it] * k[it] }
    }

    /** Window length in samples for [octaves] on [freqs]: odd, from the average step, rounded half to even as Python does. */
    fun windowSize(freqs: DoubleArray, octaves: Double): Int {
        var sum = 0.0
        for (i in 1 until freqs.size) sum += freqs[i] / freqs[i - 1]
        val step = sum / (freqs.size - 1)
        var n = Math.rint(ln(2.0.pow(octaves)) / ln(step)).toInt()
        if (n % 2 == 0) n += 1
        return n
    }

    /** 0 below [lower], 1 above [upper], a logistic in log-frequency between them. */
    fun logFSigmoid(freqs: DoubleArray, lower: Double, upper: Double): DoubleArray {
        val centre = sqrt(upper / lower) * lower
        val halfRange = log10(upper) - log10(centre)
        val c = log10(centre)
        return DoubleArray(freqs.size) { 1.0 / (1.0 + exp(-((log10(freqs[it]) - c) / (halfRange / 4)))) }
    }

    /**
     * scipy.signal.savgol_filter(x, window, 2) with its default mode "interp": a centred
     * least-squares quadratic inside, and within half a window of either end the quadratic fitted
     * to the first (or last) [window] samples.
     */
    fun savgol(x: DoubleArray, window: Int): DoubleArray {
        val n = x.size
        val w = min(window, if (n % 2 == 1) n else n - 1)
        val m = w / 2
        val out = DoubleArray(n)
        if (m == 0) return x.copyOf()
        // Quadratic smoothing weights: (3(3m² + 3m − 1) − 15i²) / ((2m − 1)(2m + 1)(2m + 3)).
        val den = (2.0 * m - 1) * (2.0 * m + 1) * (2.0 * m + 3)
        val c = DoubleArray(w) { val i = (it - m).toDouble(); (3.0 * (3.0 * m * m + 3.0 * m - 1) - 15.0 * i * i) / den }
        for (i in m until n - m) {
            var s = 0.0
            for (j in 0 until w) s += c[j] * x[i - m + j]
            out[i] = s
        }
        fitEdge(x, 0, w, 0, m, out)
        fitEdge(x, n - w, w, n - m, n, out)
        return out
    }

    /** Least-squares quadratic through x[from until from + w], evaluated at [interpFrom, interpTo). */
    private fun fitEdge(x: DoubleArray, from: Int, w: Int, interpFrom: Int, interpTo: Int, out: DoubleArray) {
        // Centred abscissa keeps the 3×3 normal equations well conditioned.
        val mid = (w - 1) / 2.0
        var s0 = 0.0
        var s2 = 0.0
        var s4 = 0.0
        var y0 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (k in 0 until w) {
            val t = k - mid
            val v = x[from + k]
            s0 += 1.0
            s2 += t * t
            s4 += t * t * t * t
            y0 += v
            y1 += t * v
            y2 += t * t * v
        }
        val det = s0 * s4 - s2 * s2
        val a0 = (y0 * s4 - s2 * y2) / det
        val a1 = y1 / s2
        val a2 = (s0 * y2 - s2 * y0) / det
        for (i in interpFrom until interpTo) {
            val t = (i - from) - mid
            out[i] = a0 + a1 * t + a2 * t * t
        }
    }

    /** scipy.signal.find_peaks(x, prominence=[minProminence]): plateau midpoints, never the first or last sample. */
    fun findPeaks(x: DoubleArray, minProminence: Double): IntArray {
        val out = ArrayList<Int>()
        var i = 1
        val last = x.size - 1
        while (i < last) {
            if (x[i - 1] < x[i]) {
                var ahead = i + 1
                while (ahead < last && x[ahead] == x[i]) ahead++
                if (x[ahead] < x[i]) {
                    val mid = (i + ahead - 1) / 2
                    if (prominence(x, mid) >= minProminence) out += mid
                    i = ahead
                }
            }
            i++
        }
        return out.toIntArray()
    }

    /** scipy's peak prominence with no window: height above the higher of the lowest points on each side before higher ground. */
    fun prominence(x: DoubleArray, peak: Int): Double {
        var leftMin = x[peak]
        var i = peak
        while (i >= 0 && x[i] <= x[peak]) {
            if (x[i] < leftMin) leftMin = x[i]
            i--
        }
        var rightMin = x[peak]
        i = peak
        while (i < x.size && x[i] <= x[peak]) {
            if (x[i] < rightMin) rightMin = x[i]
            i++
        }
        return x[peak] - max(leftMin, rightMin)
    }

    /**
     * Zones around dips that sit lower than the dips on either side: the slope limit doesn't apply
     * there, so a deep, wide boost can rise as fast as it needs to.
     */
    fun protectionMask(y: DoubleArray, peaks: IntArray, dips: IntArray): BooleanArray {
        val dipInds: IntArray
        val dipLevels: DoubleArray
        if (peaks.isNotEmpty() && (dips.isEmpty() || peaks.last() > dips.last())) {
            // The last peak comes after the last dip: add a dip at the lowest point after it.
            var low = peaks.last()
            for (k in peaks.last() until y.size) if (y[k] < y[low]) low = k
            dipInds = dips + low
            dipLevels = DoubleArray(dipInds.size) { y[dipInds[it]] }
        } else {
            // A placeholder dip at the end, at the curve's minimum.
            dipInds = dips + (y.size - 1)
            dipLevels = DoubleArray(dipInds.size) { if (it == dipInds.lastIndex) y.min() else y[dipInds[it]] }
        }
        val mask = BooleanArray(y.size)
        if (dipInds.size < 3) return mask
        for (i in 1 until dipInds.size - 1) {
            val dip = dipInds[i]
            var left = -1
            for (k in dip - 1 downTo 0) if (y[k] >= dipLevels[i - 1]) { left = k; break }
            var right = -1
            for (k in dip until y.size) if (y[k] >= dipLevels[i + 1]) { right = k; break }
            if (left < 0 || right < 0) continue
            for (k in left + 1..right - 1) mask[k] = true
        }
        return mask
    }

    /** Where the right-to-left pass starts: past the last peak where the curve falls to the last dip's level, or at the last dip. */
    fun rtlStart(y: DoubleArray, peaks: IntArray, dips: IntArray): Int {
        if (peaks.isNotEmpty() && (dips.isEmpty() || peaks.last() > dips.last())) {
            val level = if (dips.isNotEmpty()) y[dips.last()] else max(y[0], y.last())
            for (k in peaks.last() until y.size) if (y[k] <= level) return k
            return y.size - 1
        }
        return dips.last()
    }

    /**
     * Rising slopes steeper than [maxSlope] (dB/octave), left to right, are held to it from
     * [start] on, except inside [free]. A limited stretch that doesn't reach one of [peaks] is let
     * go again: only the climb into a boost is limited.
     */
    fun limitLeftToRight(x: DoubleArray, y: DoubleArray, maxSlope: Double, start: Int, peaks: IntArray, free: BooleanArray): DoubleArray {
        val n = x.size
        val limited = DoubleArray(n)
        val clipped = BooleanArray(n)
        var regionStart = -1
        for (i in 0 until n) {
            if (i <= start) {
                limited[i] = y[i]
                continue
            }
            // AutoEq's log_log_gradient(x[i], x[i - 1], y[i], limited[i - 1]), operation for operation.
            val slope = (limited[i - 1] - y[i]) / (ln(x[i - 1] / x[i]) / ln(2.0))
            if (slope > maxSlope && !free[i]) {
                if (!clipped[i - 1]) regionStart = i
                clipped[i] = true
                limited[i] = limited[i - 1] + maxSlope * (ln(x[i] / x[i - 1]) / ln(2.0))
            } else {
                limited[i] = y[i]
                if (clipped[i - 1] && peaks.none { it >= regionStart && it < i }) {
                    for (k in regionStart until i) {
                        limited[k] = y[k]
                        clipped[k] = false
                    }
                }
            }
        }
        return limited
    }

    /** The same limit for falling slopes: the left-to-right pass on the mirrored curve (frequencies stay as they are, as in AutoEq). */
    fun limitRightToLeft(x: DoubleArray, y: DoubleArray, maxSlope: Double, start: Int, peaks: IntArray, free: BooleanArray): DoubleArray {
        val n = x.size
        val mirroredPeaks = IntArray(peaks.size) { n - peaks[it] - 1 }
        return limitLeftToRight(x, y.reversedArray(), maxSlope, n - start - 1, mirroredPeaks, free.reversedArray()).reversedArray()
    }
}
