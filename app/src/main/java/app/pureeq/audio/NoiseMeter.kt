package app.pureeq.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import app.pureeq.dsp.Fft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Rough A-weighted room noise level, so the listener knows whether the room is quiet enough.
 * Uses the unprocessed (or voice-recognition) microphone path, which Android's compatibility
 * rules calibrate to about 90 dB SPL = -22.35 dBFS RMS at 1 kHz. Expect ±5 dB.
 * Audio is analysed in memory and never stored.
 */
class NoiseMeter(private val context: Context) {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun measure(seconds: Double, onLevel: (Double) -> Unit): Double? = withContext(Dispatchers.IO) {
        val rate = 48000
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val n = 4096
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, n * 4))
        }.getOrNull() ?: return@withContext null
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return@withContext null
        }
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val windowPower = window.sumOf { it * it }
        val weight = DoubleArray(n / 2 + 1) { k -> 10.0.pow(Fft.aWeightingDb(k * rate.toDouble() / n) / 10) }
        val buf = ShortArray(n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var energy = 0.0
        var blocks = 0
        val total = (seconds * rate / n).toInt().coerceAtLeast(1)
        try {
            rec.startRecording()
            // Let the microphone settle before measuring.
            var skip = 2
            while (blocks < total && currentCoroutineContext().isActive) {
                var got = 0
                while (got < n) {
                    val r = rec.read(buf, got, n - got)
                    if (r <= 0) return@withContext null
                    got += r
                }
                if (skip-- > 0) continue
                for (i in 0 until n) {
                    re[i] = buf[i] / 32768.0 * window[i]
                    im[i] = 0.0
                }
                Fft.transform(re, im)
                var s = 0.0
                for (k in 0..n / 2) {
                    val p = (re[k] * re[k] + im[k] * im[k]) * weight[k]
                    s += if (k == 0 || k == n / 2) p else 2 * p
                }
                val meanSquare = s / (n * windowPower)
                energy += meanSquare
                blocks++
                onLevel(toSpl(meanSquare))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (blocks == 0) null else toSpl(energy / blocks)
    }

    private fun toSpl(meanSquare: Double) = 10 * log10(max(meanSquare, 1e-14)) + CALIBRATION_DB

    companion object {
        /** dB SPL of a full-scale RMS signal under Android's CDD microphone sensitivity. */
        const val CALIBRATION_DB = 112.35
        const val QUIET_DB = 35.0
        const val NOISY_DB = 45.0
    }
}
