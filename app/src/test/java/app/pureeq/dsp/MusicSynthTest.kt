package app.pureeq.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

class MusicSynthTest {

    @Test
    fun loopIsCleanAndAtASensibleLevel() {
        val t0 = System.nanoTime()
        val pcm = MusicSynth.render(48000)
        val ms = (System.nanoTime() - t0) / 1e6
        val frames = pcm.size / 2
        assertEquals(8 * 4 * 60.0 / 92.0, frames / 48000.0, 0.01)
        var peak = 0.0
        var sum = 0.0
        for (v in pcm) {
            assertTrue(v.isFinite())
            peak = maxOf(peak, abs(v.toDouble()))
            sum += v.toDouble() * v
        }
        val rmsDb = 10 * log10(sum / pcm.size)
        println("render %.0f ms, peak %.2f dBFS, rms %.1f dBFS".format(ms, 20 * log10(peak), rmsDb))
        assertTrue(peak <= 0.9)
        assertTrue("rms $rmsDb", rmsDb in -26.0..-10.0)
        // The loop seam: last and first samples are close (no step when it wraps).
        assertTrue(abs(pcm[0] - pcm[pcm.size - 2]) < 0.2)
        System.getProperty("pureeq.wavOut")?.let { writeWav(File(it), pcm, 48000) }
    }

    private fun writeWav(file: File, pcm: FloatArray, rate: Int) {
        file.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            fun i32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun i16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.writeBytes("RIFF"); i32(36 + pcm.size * 2); out.writeBytes("WAVE")
            out.writeBytes("fmt "); i32(16); i16(1); i16(2); i32(rate); i32(rate * 4); i16(4); i16(16)
            out.writeBytes("data"); i32(pcm.size * 2)
            for (v in pcm) i16((v * 32767).roundToInt().coerceIn(-32768, 32767))
        }
        println("wrote ${file.absolutePath}")
    }
}
