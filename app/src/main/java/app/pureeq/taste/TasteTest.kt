package app.pureeq.taste

import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import kotlin.random.Random

/**
 * The four tone controls the taste test tunes. Shapes follow headphone preference research:
 * the 105 Hz low shelf is the bass filter of the Harman target studies. The ranges are
 * what listeners choose in practice.
 */
enum class TasteBand(
    val label: String,
    val hint: String,
    val type: FilterType,
    val freq: Double,
    val q: Double,
    val minDb: Double,
    val maxDb: Double,
    /** Half-distance between the two options in each round: big, then finer. */
    val steps: DoubleArray,
) {
    BASS("Bass", "Kick drum and bass guitar weight", FilterType.LOW_SHELF, 105.0, 0.7, -6.0, 12.0, doubleArrayOf(6.0, 3.0, 1.5)),
    WARMTH("Warmth", "Body of voices and instruments", FilterType.PEAK, 250.0, 0.8, -6.0, 6.0, doubleArrayOf(4.0, 2.0, 1.0)),
    VOCALS("Vocals", "Clarity and presence of voices", FilterType.PEAK, 2000.0, 0.8, -6.0, 6.0, doubleArrayOf(4.0, 2.0, 1.0)),
    TREBLE("Treble", "Cymbals, air and detail", FilterType.HIGH_SHELF, 6000.0, 0.7, -8.0, 8.0, doubleArrayOf(4.5, 2.25, 1.2)),
    ;

    fun filter(gainDb: Double) = FilterSpec(type, freq, gainDb, q)

    /** The furthest a session starting at [start] can move this band upward. */
    fun reachableMax(start: Double): Double = (start + steps.sum()).coerceAtMost(maxDb)
}

data class TasteSettings(
    val bass: Double = 0.0,
    val warmth: Double = 0.0,
    val vocals: Double = 0.0,
    val treble: Double = 0.0,
) {
    operator fun get(band: TasteBand): Double = when (band) {
        TasteBand.BASS -> bass
        TasteBand.WARMTH -> warmth
        TasteBand.VOCALS -> vocals
        TasteBand.TREBLE -> treble
    }

    fun with(band: TasteBand, value: Double): TasteSettings {
        val v = value.coerceIn(band.minDb, band.maxDb)
        return when (band) {
            TasteBand.BASS -> copy(bass = v)
            TasteBand.WARMTH -> copy(warmth = v)
            TasteBand.VOCALS -> copy(vocals = v)
            TasteBand.TREBLE -> copy(treble = v)
        }
    }

    /** Filters in a fixed order; bands at 0 dB are left out. */
    fun filters(): List<FilterSpec> = TasteBand.entries.filter { this[it] != 0.0 }.map { it.filter(this[it]) }
}

/** One A/B question: two values of one band. Which side holds the higher value is random. */
data class Comparison(val band: TasteBand, val round: Int, val valueA: Double, val valueB: Double)

enum class Choice { A, B, SAME }

/**
 * Adaptive paired comparison. In each round the listener hears the current setting minus d
 * and plus d (blind, loudness-matched) and the preferred one becomes the new setting.
 * d halves every round. Bands are interleaved so no single band dominates attention.
 * "Sounds the same" keeps the setting and, after the first round, stops refining that band.
 */
class TasteSession(start: TasteSettings = TasteSettings(), private val rnd: Random = Random.Default) {
    private var settings = start
    private val schedule: List<Pair<TasteBand, Int>> =
        (0 until ROUNDS).flatMap { round -> TasteBand.entries.map { it to round } }
    private var index = 0
    private val settled = mutableSetOf<TasteBand>()
    private var pending: Comparison? = null

    val history = mutableListOf<Pair<Comparison, Choice>>()

    val total: Int get() = schedule.size
    val answered: Int get() = history.size

    fun current(): Comparison? {
        pending?.let { return it }
        while (index < schedule.size) {
            val (band, round) = schedule[index]
            if (band in settled) {
                index++
                continue
            }
            val c = settings[band]
            val d = band.steps[round]
            val low = (c - d).coerceIn(band.minDb, band.maxDb)
            val high = (c + d).coerceIn(band.minDb, band.maxDb)
            if (high - low < 0.5) {
                index++
                continue
            }
            val swap = rnd.nextBoolean()
            return Comparison(band, round, if (swap) high else low, if (swap) low else high).also { pending = it }
        }
        return null
    }

    fun answer(choice: Choice) {
        val cmp = pending ?: return
        pending = null
        history += cmp to choice
        when (choice) {
            Choice.A -> settings = settings.with(cmp.band, cmp.valueA)
            Choice.B -> settings = settings.with(cmp.band, cmp.valueB)
            Choice.SAME -> if (cmp.round >= 1) settled += cmp.band
        }
        index++
    }

    /** Settings for the side the listener would be choosing, to preview while deciding. */
    fun candidate(cmp: Comparison, choice: Choice): TasteSettings = when (choice) {
        Choice.A -> settings.with(cmp.band, cmp.valueA)
        Choice.B -> settings.with(cmp.band, cmp.valueB)
        Choice.SAME -> settings
    }

    fun result(): TasteSettings = settings

    companion object {
        const val ROUNDS = 3
    }
}
