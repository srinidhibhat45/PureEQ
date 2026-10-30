package app.pureeq.headphones

import android.content.Context
import android.os.Build
import app.pureeq.BuildConfig
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.hearing.HeadphoneType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A headphone model's measured correction (AutoEq data, github.com/jaakkopasanen/AutoEq): the
 * parametric filters that bring its measured response to its target, bass shelf included. Since
 * PureEQ 1.4 it's computed on the phone from the model's measurement with AutoEq's own method
 * ([MeasuredCorrection]), against the Harman target as measured on the same rig. crinacle
 * publishes no measurements, so its models (and models picked with 1.3) use AutoEq's published
 * correction, which is against AutoEq's own target for that rig. Stored with a profile, so it
 * keeps working offline.
 */
data class HeadphoneModel(
    val name: String,
    /** Who measured it, e.g. "oratory1990", "crinacle", "Rtings". */
    val source: String,
    /** Measurement rig, when the source used more than one. */
    val rig: String?,
    val type: HeadphoneType,
    /** The correction as filters: what is applied, exported and allowed for in the hearing check. */
    val filters: List<FilterSpec>,
    /** The result's folder in AutoEq's index, which identifies it; null for models picked with PureEQ 1.3. */
    val path: String? = null,
    /** The target it was computed for, e.g. "Harman in-ear 2019"; null for AutoEq's published correction. */
    val target: String? = null,
    /** The computed correction on [app.pureeq.dsp.FreqGrid] minus the target's bass shelf (dB); null for AutoEq's published correction. */
    val curveDb: List<Double>? = null,
    /** The target's own bass shelf (105 Hz, Q 0.7) that [filters] include on top of [curveDb]. */
    val bassDb: Double? = null,
) {
    val attribution: String get() = "Headphone data: $source via AutoEq (Jaakko Pasanen, MIT)"

    /** What it's corrected to, for the listener. */
    val targetLabel: String get() = target?.let { "the $it target" } ?: "AutoEq's target for it"
}

/** One entry of AutoEq's results index: one model as measured by one source. */
data class AutoEqEntry(val name: String, val source: String, val rig: String?, val path: String) {
    /** The result folder names the form: "711 in-ear", "over-ear", "earbud"… */
    val type: HeadphoneType
        get() = path.lowercase().let { if ("in-ear" in it || "earbud" in it) HeadphoneType.IN_EAR else HeadphoneType.OVER_EAR }

    /** "in-ear", "over-ear" or "earbud", from the result folder. */
    val form: String
        get() = path.lowercase().split('/').let { it.getOrNull(it.size - 2).orEmpty() }.let { f ->
            listOf("in-ear", "over-ear", "earbud").firstOrNull { f.endsWith(it) } ?: if (type == HeadphoneType.IN_EAR) "in-ear" else "over-ear"
        }
}

/** AutoEq's entries with each name's words worked out once, so a search is only set arithmetic. */
class HeadphoneIndex(val entries: List<AutoEqEntry>) {
    internal val tokens: List<Set<String>> = entries.map { HeadphoneMatcher.tokens(it.name).toSet() }
}

/** Parses AutoEq's `results/INDEX.md`: lines like `- [Name](./source/rig%20form/Name) by source on rig`. */
object AutoEqIndex {
    private val LINE = Regex("""^- \[(.*)]\(\./(.*)\) by (.*?)(?: on (.*))?$""")

    fun parse(text: String): List<AutoEqEntry> = text.lineSequence().mapNotNull { line ->
        LINE.matchEntire(line.trim())?.let { m ->
            AutoEqEntry(m.groupValues[1], m.groupValues[3], m.groupValues[4].ifEmpty { null }, m.groupValues[2])
        }
    }.toList()
}

/** Parses an AutoEq `ParametricEQ.txt` (Equalizer APO syntax); the preamp line is ignored, PureEQ sets its own. */
object ParametricEqText {
    private val FILTER = Regex("""Filter\s+\d+:\s+ON\s+(PK|LSC|HSC)\s+Fc\s+([\d.]+)\s+Hz\s+Gain\s+(-?[\d.]+)\s+dB\s+Q\s+([\d.]+)""")

    fun parse(text: String): List<FilterSpec> = FILTER.findAll(text).map { m ->
        FilterSpec(
            FilterType.entries.first { it.apoCode == m.groupValues[1] },
            m.groupValues[2].toDouble(),
            m.groupValues[3].toDouble(),
            m.groupValues[4].toDouble(),
        )
    }.toList()
}

/**
 * Finds AutoEq entries for a device or search name. Names are compared as word sets after
 * normalising ("Galaxy Buds2 Pro (3A2F)" → galaxy buds 2 pro), and model numbers and tier words
 * (pro, max, lite, anc…) must agree, so "WH-1000XM5" doesn't land on "WF-1000XM5" and "Buds2"
 * doesn't land on "Buds2 Pro".
 */
object HeadphoneMatcher {
    data class Match(val entry: AutoEqEntry, val score: Double)

    private val TIERS = setOf("pro", "max", "plus", "lite", "mini", "fe", "live", "se", "ultra", "anc", "x", "z", "i", "s", "studio", "sport", "open")

    /** AutoEq's own order of trust in its sources, best first; others follow. */
    private val SOURCES = listOf("oratory1990", "crinacle", "Rtings", "HypetheSonics", "Innerfidelity", "Super Review", "Headphone.com Legacy")

    fun tokens(name: String): List<String> {
        var s = name.lowercase().replace('’', '\'')
        s = s.replace(Regex("""^.*?'s\s+"""), "")
        s = s.replace(Regex("""^le[_-]"""), "")
        s = s.replace(Regex("""\(\s*[0-9a-f]{2,6}\s*\)\s*$"""), "")
        s = s.replace(Regex("""(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])"""), " ")
        s = s.replace(Regex("""[^a-z0-9]+"""), " ")
        return s.split(' ').filter { it.isNotBlank() }
    }

    fun score(q: Set<String>, e: Set<String>): Double {
        if (q.isEmpty() || e.isEmpty()) return 0.0
        val common = (q intersect e).size.toDouble()
        val key = { t: String -> t.all { it.isDigit() } || t in TIERS }
        val mismatch = (q - e).count(key) + (e - q).count(key)
        return 0.65 * common / q.size + 0.35 * common / e.size - 0.25 * mismatch
    }

    fun search(index: HeadphoneIndex, query: String, limit: Int = 40): List<Match> {
        val q = tokens(query).toSet()
        if (q.isEmpty()) return emptyList()
        return index.entries.indices.asSequence()
            .map { Match(index.entries[it], score(q, index.tokens[it])) }
            .filter { it.score > 0.3 }
            .sortedWith(
                compareByDescending<Match> { it.score }
                    .thenBy { SOURCES.indexOf(it.entry.source).let { i -> if (i < 0) SOURCES.size else i } }
                    .thenBy { it.entry.name.length },
            )
            .take(limit)
            .toList()
    }
}

/**
 * Downloads AutoEq's index (about 850 KB, once, then kept on the phone) and single models, pinned
 * to one AutoEq commit so results never change under a saved profile. jsDelivr first, GitHub as a
 * fallback (jsDelivr refuses files of this repository it hasn't cached yet, as the repository is
 * over its size limit). Nothing is sent but the requested file's address.
 */
class AutoEqClient(private val context: Context) {
    private val lock = Mutex()
    private var cached: HeadphoneIndex? = null

    /** Both servers answered that the file doesn't exist. */
    class NotFound(path: String) : IOException("AutoEq has no $path")

    suspend fun index(): HeadphoneIndex = lock.withLock {
        cached ?: withContext(Dispatchers.IO) {
            val cache = File(context.filesDir, "autoeq/INDEX-${COMMIT.take(8)}.md")
            val text = if (cache.isFile) {
                cache.readText()
            } else {
                fetch("results/INDEX.md", MAX_INDEX_BYTES).also { t ->
                    cache.parentFile?.mkdirs()
                    val part = File(cache.path + ".part")
                    part.writeText(t)
                    part.renameTo(cache)
                }
            }
            HeadphoneIndex(AutoEqIndex.parse(text))
        }.also { cached = it }
    }

    /**
     * [entry]'s correction: computed from its measurement against its rig's target when AutoEq
     * publishes the measurement, otherwise (crinacle) AutoEq's published parametric correction.
     */
    suspend fun model(entry: AutoEqEntry): HeadphoneModel = withContext(Dispatchers.IO) {
        if (AutoEqTarget.hasMeasurement(entry)) {
            try {
                return@withContext measured(entry)
            } catch (e: NotFound) {
                // No measurement after all: AutoEq's own correction below.
            } catch (e: IllegalArgumentException) {
                // An unreadable measurement: the same.
            }
        }
        published(entry)
    }

    private fun measured(entry: AutoEqEntry): HeadphoneModel {
        val target = AutoEqTarget.of(entry)
        val raw = AutoEqCsv.parse(cachedFetch("results/${entry.path}/" + encode("${decodedName(entry)}.csv"), MAX_CSV_BYTES))
        val curve = AutoEqCsv.parse(cachedFetch("targets/" + encode("${target.file}.csv"), MAX_CSV_BYTES))
        return MeasuredCorrection.model(entry, target, raw, curve)
    }

    private fun published(entry: AutoEqEntry): HeadphoneModel {
        val file = entry.path + "/" + encode("${decodedName(entry)} ParametricEQ.txt")
        val filters = ParametricEqText.parse(cachedFetch("results/$file", MAX_FILE_BYTES))
        if (filters.isEmpty()) throw IOException("AutoEq has no parametric correction for ${entry.name}")
        return HeadphoneModel(entry.name, entry.source, entry.rig, entry.type, filters, path = entry.path)
    }

    /** The folder's last segment is the file-name stem, %-encoded in the index. */
    private fun decodedName(entry: AutoEqEntry) = java.net.URLDecoder.decode(entry.path.substringAfterLast('/').replace("+", "%2B"), "UTF-8")

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** A file of the pinned commit, kept on the phone once fetched: files there never change. */
    private fun cachedFetch(path: String, limit: Int): String {
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(context.filesDir, "autoeq/${COMMIT.take(8)}/$name")
        if (file.isFile) return file.readText()
        return fetch(path, limit).also { t ->
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            part.writeText(t)
            if (!part.renameTo(file)) part.delete()
        }
    }

    private fun fetch(path: String, limit: Int): String {
        var last: IOException? = null
        for (base in BASES) {
            try {
                val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "PureEQ/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
                }
                try {
                    val code = c.responseCode
                    // GitHub holds the repository itself, so its "not found" is final.
                    if (code == HttpURLConnection.HTTP_NOT_FOUND && base == BASES.last()) throw NotFound(path)
                    if (code != HttpURLConnection.HTTP_OK) throw IOException("$base answered $code")
                    return c.inputStream.use { it.readAtMost(limit) }.toString(Charsets.UTF_8)
                } finally {
                    c.disconnect()
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("AutoEq unreachable")
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("AutoEq file too large")
        }
        return out.toByteArray()
    }

    companion object {
        /** AutoEq master as of 2025-07-20; pinned so a profile's correction stays reproducible. */
        const val COMMIT = "7ae0f56d53074872b028649617a22bbb4232feb7"
        private val BASES = listOf(
            "https://cdn.jsdelivr.net/gh/jaakkopasanen/AutoEq@$COMMIT/",
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/$COMMIT/",
        )
        private const val MAX_INDEX_BYTES = 4 shl 20
        private const val MAX_FILE_BYTES = 64 shl 10
        private const val MAX_CSV_BYTES = 256 shl 10
    }
}
