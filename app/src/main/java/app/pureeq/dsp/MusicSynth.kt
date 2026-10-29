package app.pureeq.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * PureEQ's built-in listening track, synthesised so no copyrighted audio ships with the app.
 * An 8-bar pop loop in A minor at 92 BPM: kick, snare, hats, shaker, bass, pad, an FM electric
 * piano, a formant-synthesised "aah" vocal and a plate-like reverb. Together they cover
 * 40 Hz to 16 kHz, which is what a bass, vocal and treble taste test needs.
 * Rendering is deterministic, and the reverb and release tails are folded back onto the start,
 * so the loop is seamless.
 */
object MusicSynth {

    private const val BPM = 92.0
    private const val BARS = 8
    private const val TAIL_SECONDS = 3.0

    /** Bar chords as MIDI notes (voice-led triads) and their bass roots. */
    private val chords = listOf(
        intArrayOf(57, 60, 64), intArrayOf(57, 60, 65), intArrayOf(55, 60, 64), intArrayOf(55, 59, 62),
        intArrayOf(57, 60, 64), intArrayOf(57, 60, 65), intArrayOf(55, 60, 64), intArrayOf(56, 59, 64),
    )
    private val roots = intArrayOf(33, 29, 36, 31, 33, 29, 36, 28)

    /** Vocal melody: start beat, length in beats, MIDI note, vowel (0 = "ah", 1 = "oh"). */
    private val melody = listOf(
        doubleArrayOf(0.0, 1.5, 72.0, 0.0), doubleArrayOf(1.5, 0.5, 71.0, 0.0), doubleArrayOf(2.0, 2.0, 69.0, 1.0),
        doubleArrayOf(4.0, 1.0, 69.0, 0.0), doubleArrayOf(5.0, 2.0, 72.0, 0.0), doubleArrayOf(7.0, 1.0, 69.0, 1.0),
        doubleArrayOf(8.0, 2.0, 76.0, 0.0), doubleArrayOf(10.0, 1.0, 74.0, 0.0), doubleArrayOf(11.0, 1.0, 72.0, 1.0),
        doubleArrayOf(12.0, 2.0, 71.0, 0.0), doubleArrayOf(14.0, 1.0, 74.0, 0.0), doubleArrayOf(15.0, 1.0, 71.0, 1.0),
        doubleArrayOf(16.0, 1.5, 72.0, 0.0), doubleArrayOf(17.5, 0.5, 71.0, 0.0), doubleArrayOf(18.0, 1.0, 69.0, 1.0),
        doubleArrayOf(19.0, 1.0, 76.0, 0.0),
        doubleArrayOf(20.0, 2.0, 77.0, 0.0), doubleArrayOf(22.0, 1.0, 76.0, 0.0), doubleArrayOf(23.0, 1.0, 72.0, 1.0),
        doubleArrayOf(24.0, 1.0, 76.0, 0.0), doubleArrayOf(25.0, 1.0, 74.0, 0.0), doubleArrayOf(26.0, 1.0, 72.0, 0.0),
        doubleArrayOf(27.0, 1.0, 67.0, 1.0),
        doubleArrayOf(28.0, 2.0, 71.0, 0.0), doubleArrayOf(30.0, 1.25, 68.0, 1.0),
    )

    /** Soprano formants (Hz, linear gain, bandwidth Hz) for "ah" and "oh"; upper formants lifted for presence. */
    private val vowelF = arrayOf(doubleArrayOf(800.0, 1150.0, 2900.0, 3900.0, 4950.0), doubleArrayOf(450.0, 800.0, 2830.0, 3800.0, 4950.0))
    private val vowelA = arrayOf(doubleArrayOf(1.0, 0.63, 0.3, 0.22, 0.05), doubleArrayOf(1.0, 0.38, 0.2, 0.15, 0.04))
    private val vowelBw = arrayOf(doubleArrayOf(80.0, 90.0, 120.0, 130.0, 140.0), doubleArrayOf(70.0, 80.0, 100.0, 130.0, 135.0))

    /** Renders the loop as interleaved stereo floats at [sampleRate], peak-normalised to -1 dBFS. */
    fun render(sampleRate: Int): FloatArray {
        val sr = sampleRate.toDouble()
        val beat = 60.0 / BPM
        val loopFrames = (BARS * 4 * beat * sr).toInt()
        val total = loopFrames + (TAIL_SECONDS * sr).toInt()
        val mix = Mix(total, sr)
        val rnd = Random(20261029)

        for (bar in 0 until BARS) {
            val t0 = bar * 4 * beat
            // Drums
            kick(mix, t0, 1.0)
            kick(mix, t0 + 2 * beat, 0.95)
            if (bar % 2 == 1) kick(mix, t0 + 2.5 * beat, 0.7)
            snare(mix, t0 + beat, 1.0, rnd)
            snare(mix, t0 + 3 * beat, 1.0, rnd)
            if (bar == BARS - 1) snare(mix, t0 + 3.75 * beat, 0.45, rnd)
            for (e in 0 until 8) {
                val open = e == 7 && bar % 2 == 1
                hat(mix, t0 + e * beat / 2, if (e % 2 == 1) 0.8 else 0.55, open, rnd)
            }
            for (s in 0 until 16) shaker(mix, t0 + s * beat / 4, doubleArrayOf(0.5, 0.22, 0.38, 0.22)[s % 4], rnd)
            // Bass: root, octave and fifth in a syncopated eighth pattern
            val r = roots[bar]
            val pattern = listOf(0 to 1.5, 3 to 1.0, 4 to 0.9, 6 to 0.9, 7 to 0.9)
            for ((step, len) in pattern) {
                val note = when (step) {
                    4 -> r + 12
                    7 -> r + 7
                    else -> r
                }
                bass(mix, t0 + step * beat / 2, len * beat / 2 * 0.92, midiHz(note), if (step == 0) 1.0 else 0.85)
            }
            // Pad and electric-piano arpeggio
            pad(mix, t0, 4 * beat, chords[bar])
            val arp = intArrayOf(0, 1, 2, 1, 0, 2, 1, 2)
            for (e in 0 until 8) {
                val n = chords[bar][arp[e]] + 12
                epiano(mix, t0 + e * beat / 2, midiHz(n), if (e % 2 == 0) 0.9 else 0.65, if (e % 2 == 0) -0.4 else 0.4)
            }
        }
        vocal(mix, beat)

        return mix.finish(loopFrames)
    }

    // ---- Instruments ------------------------------------------------------------------------

    private fun kick(m: Mix, t0: Double, vel: Double) {
        val start = (t0 * m.sr).toInt()
        val len = (0.5 * m.sr).toInt()
        var phase = 0.0
        val clickFilter = Sos(Biquad.bandPass(3500.0, 0.8, m.sr))
        val rnd = Random(start)
        for (n in 0 until len) {
            val t = n / m.sr
            val f = 47.0 + 100.0 * exp(-t / 0.03)
            phase += 2 * PI * f / m.sr
            val env = min(1.0, t / 0.002) * exp(-t / 0.32)
            val click = clickFilter.process(rnd.nextDouble(-1.0, 1.0)) * exp(-t / 0.004) * 0.5
            val v = tanh(1.5 * (sin(phase) * env + click)) * 0.5 * vel
            m.add(start + n, v, 0.0, 0.0)
        }
    }

    private fun snare(m: Mix, t0: Double, vel: Double, rnd: Random) {
        val start = (t0 * m.sr).toInt()
        val len = (0.4 * m.sr).toInt()
        val bpL = Sos(Biquad.bandPass(3000.0, 0.6, m.sr))
        val bpR = Sos(Biquad.bandPass(3400.0, 0.6, m.sr))
        var phase = 0.0
        for (n in 0 until len) {
            val t = n / m.sr
            phase += 2 * PI * (178.0 + 45.0 * exp(-t / 0.02)) / m.sr
            val body = sin(phase) * exp(-t / 0.055) * 0.55
            val common = rnd.nextDouble(-1.0, 1.0)
            val nEnv = min(1.0, t / 0.001) * exp(-t / 0.12)
            val nl = bpL.process(0.8 * common + 0.2 * rnd.nextDouble(-1.0, 1.0)) * nEnv * 1.6
            val nr = bpR.process(0.8 * common + 0.2 * rnd.nextDouble(-1.0, 1.0)) * nEnv * 1.6
            val g = 0.42 * vel
            m.addStereo(start + n, (body + nl) * g, (body + nr) * g, 0.28)
        }
    }

    private fun hat(m: Mix, t0: Double, vel: Double, open: Boolean, rnd: Random) {
        val start = (t0 * m.sr).toInt()
        val decay = if (open) 0.2 else 0.032
        val len = ((decay * 6) * m.sr).toInt()
        val hp = Sos(Biquad.design(FilterSpec(FilterType.HIGH_PASS, 7500.0, 0.0, 0.7), m.sr))
        val bp = Sos(Biquad.bandPass(11000.0, 1.2, m.sr))
        for (n in 0 until len) {
            val t = n / m.sr
            val x = rnd.nextDouble(-1.0, 1.0)
            val v = (hp.process(x) * 0.7 + bp.process(x) * 0.6) * exp(-t / decay) * 0.16 * vel
            m.add(start + n, v, 0.3, 0.06)
        }
    }

    private fun shaker(m: Mix, t0: Double, vel: Double, rnd: Random) {
        val start = (t0 * m.sr).toInt()
        val len = (0.18 * m.sr).toInt()
        val bp = Sos(Biquad.bandPass(6500.0, 1.4, m.sr))
        for (n in 0 until len) {
            val t = n / m.sr
            val env = min(1.0, t / 0.008) * exp(-t / 0.04)
            m.add(start + n, bp.process(rnd.nextDouble(-1.0, 1.0)) * env * 0.11 * vel, -0.4, 0.05)
        }
    }

    private fun bass(m: Mix, t0: Double, length: Double, f: Double, vel: Double) {
        val start = (t0 * m.sr).toInt()
        val release = 0.05
        val len = ((length + release) * m.sr).toInt()
        var ph = 0.0
        val lp = Sos(Biquad.lowPass(900.0, 0.9, m.sr))
        val dt = f / m.sr
        for (n in 0 until len) {
            val t = n / m.sr
            if (n % 32 == 0) lp.set(Biquad.lowPass(240.0 + 1100.0 * exp(-t / 0.09), 0.9, m.sr))
            ph += dt
            if (ph >= 1.0) ph -= 1.0
            val saw = 2 * ph - 1 - polyBlep(ph, dt)
            val sine = sin(2 * PI * ph)
            val ampEnv = min(1.0, t / 0.004) * (0.75 + 0.25 * exp(-t / 0.12)) *
                (if (t > length) max(0.0, 1 - (t - length) / release) else 1.0)
            val v = tanh(1.3 * (0.48 * sine + 0.62 * lp.process(saw))) * 0.45 * vel * ampEnv
            m.add(start + n, v, 0.0, 0.0)
        }
    }

    private fun pad(m: Mix, t0: Double, length: Double, notes: IntArray) {
        val start = (t0 * m.sr).toInt()
        val attack = 0.35
        val release = 0.6
        val len = ((length + release) * m.sr).toInt()
        val detune = doubleArrayOf(-0.08, 0.0, 0.08)
        val pans = doubleArrayOf(-0.6, 0.0, 0.6)
        val phases = DoubleArray(notes.size * 3) { (it * 0.137) % 1.0 }
        val lpL = Sos(Biquad.lowPass(1800.0, 0.6, m.sr))
        val lpR = Sos(Biquad.lowPass(1800.0, 0.6, m.sr))
        for (n in 0 until len) {
            val t = n / m.sr
            if (n % 64 == 0) {
                val cutoff = 1700.0 + 500.0 * sin(2 * PI * 0.23 * (t0 + t))
                lpL.set(Biquad.lowPass(cutoff, 0.6, m.sr))
                lpR.set(Biquad.lowPass(cutoff * 1.06, 0.6, m.sr))
            }
            var l = 0.0
            var r = 0.0
            for (k in notes.indices) {
                for (d in 0 until 3) {
                    val idx = k * 3 + d
                    val dt = midiHz(notes[k] + detune[d]) / m.sr
                    var p = phases[idx] + dt
                    if (p >= 1.0) p -= 1.0
                    phases[idx] = p
                    val s = 2 * p - 1 - polyBlep(p, dt)
                    val (gl, gr) = panGains(pans[d])
                    l += s * gl
                    r += s * gr
                }
            }
            val env = min(1.0, t / attack) * (if (t > length) max(0.0, 1 - (t - length) / release) else 1.0)
            val g = 0.03 * env
            m.addStereo(start + n, lpL.process(l) * g, lpR.process(r) * g, 0.4)
        }
    }

    private fun epiano(m: Mix, t0: Double, f: Double, vel: Double, pan: Double) {
        val start = (t0 * m.sr).toInt()
        val len = (1.6 * m.sr).toInt()
        val (gl, gr) = panGains(pan)
        for (n in 0 until len) {
            val t = n / m.sr
            val index = 2.6 * exp(-t / 0.22) + 0.45
            val w = 2 * PI * f * t
            val car = sin(w + index * sin(w))
            val tine = sin(2 * PI * f * 3.98 * t) * 0.18 * exp(-t / 0.05)
            val env = min(1.0, t / 0.003) * exp(-t / 0.75)
            val v = (car + tine) * env * 0.115 * vel
            m.addStereo(start + n, v * gl, v * gr, 0.25)
        }
    }

    private fun vocal(m: Mix, beat: Double) {
        val bank = Array(5) { Sos(Biquad(1.0, 0.0, 0.0, 0.0, 0.0)) }
        var curF = midiHz(melody.first()[2])
        var ph = 0.0
        var amp = 0.0
        val vowelNow = vowelF[0].copyOf()
        val gainNow = vowelA[0].copyOf()
        val bwNow = vowelBw[0].copyOf()
        val breath = Random(7)
        val endFrame = ((melody.last()[0] + melody.last()[1]) * beat * m.sr).toInt() + (0.4 * m.sr).toInt()
        var noteIdx = 0
        for (n in 0 until endFrame) {
            val t = n / m.sr
            val tb = t / beat
            while (noteIdx < melody.size - 1 && tb >= melody[noteIdx + 1][0]) noteIdx++
            val note = melody[noteIdx]
            val inNote = tb >= note[0] && tb < note[0] + note[1]
            val sinceOn = (tb - note[0]) * beat
            val untilOff = (note[0] + note[1] - tb) * beat
            // Legato pitch glide, vibrato that eases in, small dip between syllables
            val targetF = midiHz(note[2])
            curF *= (targetF / curF).pow(1.0 - exp(-1.0 / (0.035 * m.sr)))
            val vibDepth = 0.35 * ((sinceOn - 0.2) / 0.3).coerceIn(0.0, 1.0)
            val f = curF * 2.0.pow(vibDepth * sin(2 * PI * 5.3 * t) / 12.0)
            val targetAmp = when {
                !inNote -> 0.0
                sinceOn < 0.05 -> 0.8
                untilOff < 0.06 -> 0.75
                else -> 1.0
            }
            val tau = if (targetAmp > amp) 0.03 else 0.07
            amp += (targetAmp - amp) * (1 - exp(-1.0 / (tau * m.sr)))
            // Vowel morph
            val v = note[3].toInt()
            if (n % 64 == 0) {
                val k = 1 - exp(-64.0 / (0.08 * m.sr))
                for (i in 0 until 5) {
                    vowelNow[i] += (vowelF[v][i] - vowelNow[i]) * k
                    gainNow[i] += (vowelA[v][i] - gainNow[i]) * k
                    bwNow[i] += (vowelBw[v][i] - bwNow[i]) * k
                    bank[i].set(Biquad.bandPass(vowelNow[i], vowelNow[i] / bwNow[i], m.sr))
                }
            }
            val dt = f / m.sr
            ph += dt
            if (ph >= 1.0) ph -= 1.0
            val src = (2 * ph - 1 - polyBlep(ph, dt)) + breath.nextDouble(-1.0, 1.0) * 0.06
            var out = 0.0
            for (i in 0 until 5) out += bank[i].process(src) * gainNow[i]
            m.add(n, out * amp * 0.72, 0.0, 0.3)
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun midiHz(note: Double): Double = 440.0 * 2.0.pow((note - 69.0) / 12.0)
    private fun midiHz(note: Int): Double = midiHz(note.toDouble())

    private fun panGains(p: Double): Pair<Double, Double> {
        val a = (p + 1) * PI / 4
        return cos(a) to sin(a)
    }

    /** PolyBLEP residual that removes aliasing from a naive sawtooth. */
    private fun polyBlep(t: Double, dt: Double): Double = when {
        t < dt -> {
            val x = t / dt
            x + x - x * x - 1.0
        }
        t > 1.0 - dt -> {
            val x = (t - 1.0) / dt
            x * x + x + x + 1.0
        }
        else -> 0.0
    }

    /** A single biquad section with state, for the synthesiser's per-voice filters. */
    private class Sos(bq: Biquad) {
        private var b0 = bq.b0; private var b1 = bq.b1; private var b2 = bq.b2
        private var a1 = bq.a1; private var a2 = bq.a2
        private var z1 = 0.0; private var z2 = 0.0

        fun set(bq: Biquad) {
            b0 = bq.b0; b1 = bq.b1; b2 = bq.b2; a1 = bq.a1; a2 = bq.a2
        }

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    /** Dry stereo bus plus a reverb send; [finish] adds the reverb, folds the tail and normalises. */
    private class Mix(val frames: Int, val sr: Double) {
        val l = DoubleArray(frames)
        val r = DoubleArray(frames)
        val send = DoubleArray(frames)

        fun add(i: Int, v: Double, pan: Double, sendAmt: Double) {
            if (i < 0 || i >= frames) return
            val (gl, gr) = if (pan == 0.0) CENTER else panGains(pan)
            l[i] += v * gl
            r[i] += v * gr
            send[i] += v * sendAmt
        }

        fun addStereo(i: Int, vl: Double, vr: Double, sendAmt: Double) {
            if (i < 0 || i >= frames) return
            l[i] += vl
            r[i] += vr
            send[i] += (vl + vr) * 0.5 * sendAmt
        }

        fun finish(loopFrames: Int): FloatArray {
            val (wl, wr) = Reverb(sr).process(send)
            for (i in 0 until frames) {
                l[i] += wl[i]
                r[i] += wr[i]
            }
            // Fold everything after the loop end back onto the start: seamless when repeated.
            for (i in loopFrames until frames) {
                val j = i - loopFrames
                if (j >= loopFrames) break
                l[j] += l[i]
                r[j] += r[i]
            }
            // Remove the small DC offset the saturators leave (subtracting the mean keeps the seam intact).
            val dcL = l.take(loopFrames).average()
            val dcR = r.take(loopFrames).average()
            var peak = 1e-9
            for (i in 0 until loopFrames) {
                l[i] -= dcL
                r[i] -= dcR
                peak = max(peak, max(abs(l[i]), abs(r[i])))
            }
            val g = 0.891 / peak
            val out = FloatArray(loopFrames * 2)
            for (i in 0 until loopFrames) {
                out[2 * i] = (l[i] * g).toFloat()
                out[2 * i + 1] = (r[i] * g).toFloat()
            }
            return out
        }

        companion object {
            val CENTER = sqrt(0.5) to sqrt(0.5)
        }
    }

    /** Freeverb-style stereo reverb with a 22 ms pre-delay and a low cut on the send. */
    private class Reverb(private val sr: Double) {
        private val scale = sr / 44100.0
        private val combTune = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val apTune = intArrayOf(556, 441, 341, 225)

        fun process(send: DoubleArray): Pair<DoubleArray, DoubleArray> {
            val n = send.size
            val pre = (0.022 * sr).toInt()
            val lowCut = Sos(Biquad.design(FilterSpec(FilterType.HIGH_PASS, 220.0, 0.0, 0.7), sr))
            val input = DoubleArray(n) { i -> if (i >= pre) lowCut.process(send[i - pre]) else lowCut.process(0.0) }
            return channel(input, 0) to channel(input, 23)
        }

        private fun channel(input: DoubleArray, spread: Int): DoubleArray {
            val feedback = 0.84
            val damp = 0.25
            val combs = combTune.map { DoubleArray(((it + spread) * scale).toInt().coerceAtLeast(1)) }
            val combIdx = IntArray(combs.size)
            val combStore = DoubleArray(combs.size)
            val aps = apTune.map { DoubleArray(((it + spread) * scale).toInt().coerceAtLeast(1)) }
            val apIdx = IntArray(aps.size)
            val out = DoubleArray(input.size)
            for (i in input.indices) {
                val x = input[i] * 0.015
                var acc = 0.0
                for (c in combs.indices) {
                    val buf = combs[c]
                    val y = buf[combIdx[c]]
                    combStore[c] = y * (1 - damp) + combStore[c] * damp
                    buf[combIdx[c]] = x + combStore[c] * feedback
                    combIdx[c] = (combIdx[c] + 1) % buf.size
                    acc += y
                }
                for (a in aps.indices) {
                    val buf = aps[a]
                    val bo = buf[apIdx[a]]
                    val y = -acc + bo
                    buf[apIdx[a]] = acc + bo * 0.5
                    apIdx[a] = (apIdx[a] + 1) % buf.size
                    acc = y
                }
                out[i] = acc * 3.0 * 0.3
            }
            return out
        }
    }
}
