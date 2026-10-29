package app.pureeq.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import app.pureeq.dsp.Fft
import app.pureeq.dsp.MusicSynth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A looping music excerpt for A/B listening, with its long-term spectrum for loudness matching. */
class MusicTrack(val title: String, val pcm: FloatArray, val rate: Int, val spectrumDb: DoubleArray)

class MusicLibrary(private val context: Context) {

    /** The built-in synthesised loop, rendered once and cached (in cacheDir, so backups skip its 8 MB). */
    suspend fun builtIn(rate: Int): MusicTrack = withContext(Dispatchers.Default) {
        val file = File(context.cacheDir, "loop_v${LOOP_VERSION}_$rate.f32")
        val pcm = readFloats(file) ?: MusicSynth.render(rate).also { writeFloats(file, it) }
        MusicTrack("PureEQ demo track", pcm, rate, Fft.longTermSpectrumDb(pcm, rate))
    }

    /** Decodes about 40 s from a quarter of the way into a song and turns it into a seamless loop. */
    suspend fun decode(uri: Uri): MusicTrack = withContext(Dispatchers.IO) {
        val title = displayName(uri) ?: "Your song"
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val trackIndex = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio found in this file")
        ex.selectTrack(trackIndex)
        val format = ex.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val startUs = if (durationUs > 0) min((durationUs * 0.25).toLong(), max(0L, durationUs - EXCERPT_SECONDS * 1_000_000L)) else 0L
        if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val codec = MediaCodec.createDecoderByType(mime)
        val out = FloatList()
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val started = System.nanoTime()
            while (true) {
                // A broken or exotic file must not hang the app.
                if (System.nanoTime() - started > DECODE_TIMEOUT_NS) error("Decoding took too long")
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0 || out.size / 2 >= EXCERPT_SECONDS * rate) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    appendPcm(buf.slice().order(ByteOrder.nativeOrder()), encoding, channels, out)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        val pcm = makeLoop(out.toArray(), rate)
        if (pcm.size < rate * 2 * 4) error("This file is too short to use")
        MusicTrack(title, pcm, rate, Fft.longTermSpectrumDb(pcm, rate))
    }

    private fun appendPcm(buf: ByteBuffer, encoding: Int, channels: Int, out: FloatList) {
        val ch = max(1, channels)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                val frames = fb.remaining() / ch
                for (i in 0 until frames) {
                    val l = fb.get(i * ch)
                    out.add(l)
                    out.add(if (ch > 1) fb.get(i * ch + 1) else l)
                }
            }
            else -> {
                val sb = buf.asShortBuffer()
                val frames = sb.remaining() / ch
                for (i in 0 until frames) {
                    val l = sb.get(i * ch) / 32768f
                    out.add(l)
                    out.add(if (ch > 1) sb.get(i * ch + 1) / 32768f else l)
                }
            }
        }
    }

    /** Equal-power crossfade of the last second into the first, so the excerpt loops without a seam. */
    private fun makeLoop(pcm: FloatArray, rate: Int): FloatArray {
        val frames = pcm.size / 2
        val fade = min(rate, frames / 4)
        if (fade < 16) return pcm
        val len = frames - fade
        val out = FloatArray(len * 2)
        System.arraycopy(pcm, 0, out, 0, len * 2)
        for (i in 0 until fade) {
            val t = i.toDouble() / fade
            val gIn = sin(t * PI / 2).toFloat()
            val gOut = cos(t * PI / 2).toFloat()
            for (c in 0..1) out[2 * i + c] = pcm[2 * i + c] * gIn + pcm[2 * (len + i) + c] * gOut
        }
        return out
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()

    private fun readFloats(file: File): FloatArray? = runCatching {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(fb.remaining()).also { fb.get(it) }
    }.getOrNull()

    private fun writeFloats(file: File, pcm: FloatArray) {
        runCatching {
            val bb = ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(pcm)
            file.writeBytes(bb.array())
        }
    }

    /** Growable float array without boxing. */
    private class FloatList {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    private companion object {
        const val EXCERPT_SECONDS = 40
        const val DECODE_TIMEOUT_NS = 30_000_000_000L
        /** Bump when MusicSynth changes so cached renders are replaced. */
        const val LOOP_VERSION = 1
    }
}
