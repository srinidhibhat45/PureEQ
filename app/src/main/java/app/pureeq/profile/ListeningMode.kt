package app.pureeq.profile

import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.taste.TasteBand
import app.pureeq.taste.TasteSettings

/**
 * What a profile's headphones are mostly used for. Each mode reshapes the same personal tuning
 * (headphone correction, ear tuning, taste) for its use; [ModeTuning] says how, and why.
 * Every mode's own filters are the same for both ears, so where a sound sits stays where it was;
 * Gaming also limits how differently the ear tuning treats the ears in the treble.
 */
enum class ListeningMode {
    GAMING,
    MUSIC,
    VOICE,
    GENERAL,
    ;

    val tuning: ModeTuning
        get() = when (this) {
            MUSIC -> ModeTuning.MUSIC
            GENERAL -> ModeTuning.GENERAL
            GAMING -> ModeTuning.GAMING
            VOICE -> ModeTuning.VOICE
        }
}

/** How a mode changes the profile's sound. The defaults are the profile as tuned ([MUSIC]). */
data class ModeTuning(
    /** Added to both ears alike, on top of correction, ear tuning and taste. */
    val filters: List<FilterSpec> = emptyList(),
    /** Taste bands held within a range in this mode (dB); the others play as set. */
    val tasteLimits: Map<TasteBand, ClosedFloatingPointRange<Double>> = emptyMap(),
    /** Share of the ear tuning's strength that applies (capped at 100 %). */
    val earTuning: Double = 1.0,
    /**
     * Largest left/right difference the ear tuning may make from 4 kHz up, where the ears tell
     * direction ([HearingCompensation.DIRECTION_HZ], dB); null for no limit.
     */
    val maxEarDifferenceDb: Double? = null,
    /** Shares of the volume-linked bass and treble that apply ([VolumeBass]). */
    val volumeBass: Double = 1.0,
    val volumeTreble: Double = 1.0,
    /** A stereo-linked leveller in the system-wide EQ; null for none. */
    val leveller: Leveller? = null,
    /** The system-wide EQ's frame: shorter is quicker, longer resolves the bass more finely. */
    val frameMs: Float = FRAME_MS,
) {
    /** [taste] as this mode plays it. */
    fun taste(taste: TasteSettings): TasteSettings =
        tasteLimits.entries.fold(taste) { t, (band, range) -> if (t[band] in range) t else t.with(band, t[band].coerceIn(range)) }

    /**
     * Downward compression of the whole mix, both channels by the same gain, with no make-up gain,
     * so it only ever turns down and can't add clipping. The effect measures each frame's RMS and
     * applies (1/[ratio] − 1) × the excess over [thresholdDb] (AOSP DPFrequency, hard knee).
     * [thresholdDb] is for the music as it comes in; [shifted] moves it to where the effect sees
     * it (after the preamp, and on most outputs after the volume).
     */
    data class Leveller(val thresholdDb: Float, val ratio: Float, val attackMs: Float, val releaseMs: Float) {
        fun shifted(db: Double) = copy(thresholdDb = thresholdDb + db.toFloat())
    }

    companion object {
        /** The frame the system-wide EQ uses unless a mode needs another: 2048-point FFT, about 43 ms of delay. */
        const val FRAME_MS = 40f

        /**
         * The profile as tuned. Everything in it was chosen on music: the Harman target, the
         * blind taste test, loudness compensation. Static EQ suits music best: listeners rate
         * linear processing highest for music, and slow compression above fast (Kirchberger &
         * Russo 2016; Moore & Sęk 2016), and music needs its low end down to about 50 Hz to
         * sound natural (Moore & Tan 2003).
         */
        val MUSIC = ModeTuning()

        /**
         * A bit of everything: videos, social apps, podcasts, some music and games.
         * - A touch of clarity for voices: +1.5 dB at 2.5 kHz, broad. In noise listeners move
         *   speech energy into 1-4 kHz; kept small because broad tilts cost naturalness.
         * - A slow leveller (2:1 above −24 dB, 50 ms attack, 3 s release, Moore & Sęk's slow
         *   setting) narrows the gap between loud and quiet sources: music and videos are
         *   normalised to about −14 LUFS, film dialogue to −27, so a −14 track comes down about
         *   5 dB while dialogue below the threshold is untouched. Linked, so the image stays put.
         */
        val GENERAL = ModeTuning(
            filters = listOf(FilterSpec(FilterType.PEAK, 2500.0, 1.5, 0.7)),
            leveller = Leveller(thresholdDb = -24f, ratio = 2f, attackMs = 50f, releaseMs = 3000f),
        )

        /**
         * Hearing what matters in a game. No vendor publishes its preset values, and no study ties
         * EQ to game performance, so this follows the shared pattern (less bass, more 1-4 kHz,
         * treble kept) and what hearing science says about masking and direction:
         * - Bass at −3 dB or less at 105 Hz: explosions sit at 20-250 Hz and mask upwards more
         *   the louder they are. A taste for more bass plays at −3 dB here, a taste for less
         *   keeps its own. Warmth isn't added. No volume-linked bass, for the same reason.
         * - Footsteps, reloads and voices: +2 dB at 2 kHz, broad (footsteps sit around 1-4 kHz).
         * - Direction comes from 4-16 kHz: treble is never cut here (a taste for less plays at
         *   0), at most +3 dB, and a little air (+1.5 dB at 10 kHz, broad). Broad shapes only,
         *   since narrow peaks and notches up there move where sounds seem to come from.
         * - The ear tuning makes the ears differ by 3 dB at most from 4 kHz up (1.7.0's research
         *   suggested ±3 dB, inferred). A level difference of about 1 dB between the ears is
         *   audible and moves a sound sideways, and up there a measured one is least certain:
         *   re-seating headphones moves their response 8-15 dB. In simulation (FitSimulationTest)
         *   a one-sided 20 dB treble loss gets 6.2 dB up there on average, held to 2.9, while a
         *   listener with no real difference gets 0.2 dB and is left as is.
         * - No compression: whether it helps hear quiet cues is unproven, and the effect has no
         *   per-band linking.
         * - 20 ms frames: about 21 ms of delay instead of 43; changes of 27-49 ms are noticeable.
         *   Games on Android's low-latency path bypass every system effect anyway.
         */
        val GAMING = ModeTuning(
            filters = listOf(
                FilterSpec(FilterType.PEAK, 2000.0, 2.0, 0.8),
                FilterSpec(FilterType.HIGH_SHELF, 10000.0, 1.5, 0.7),
            ),
            tasteLimits = mapOf(
                TasteBand.BASS to TasteBand.BASS.minDb..-3.0,
                TasteBand.WARMTH to TasteBand.WARMTH.minDb..0.0,
                TasteBand.TREBLE to 0.0..3.0,
            ),
            maxEarDifferenceDb = 3.0,
            volumeBass = 0.0,
            frameMs = 20f,
        )

        /**
         * Voices: podcasts, audiobooks, voice notes and talk videos. Phone calls and app calls
         * (WhatsApp, Meet, Teams) don't reach any app's system-wide effect on Android: the modem
         * plays phone calls, and app calls go to an output of their own.
         * - Less boom: a deep shelf down from about 100 Hz (in place of a high-pass, so every export
         *   format can carry it; speech degrades only once the low cut passes about 120 Hz), and
         *   −2 dB at 300 Hz for boxiness. No bass or warmth boosts, no volume-linked bass.
         * - Clarity: +2.5 dB at 2 kHz, broad. The 1, 2 and 4 kHz octaves carry 71 % of speech
         *   intelligibility (ANSI S3.5).
         * - No treble or air boosts (sibilance), and nothing low-passed: speech sounds natural up
         *   to about 11 kHz.
         * - The ear tuning at full strength (75 % → 100 %), keeping its gentle slope and caps:
         *   speech prescriptions give more gain than music programs.
         * - A leveller for talkers who are louder or quieter (2:1 above −24 dB, 30 ms attack,
         *   400 ms release: low ratios and releases over 200 ms sound least pumped).
         */
        val VOICE = ModeTuning(
            filters = listOf(
                FilterSpec(FilterType.LOW_SHELF, 100.0, -9.0, 0.7),
                FilterSpec(FilterType.PEAK, 300.0, -2.0, 1.0),
                FilterSpec(FilterType.PEAK, 2000.0, 2.5, 0.8),
            ),
            tasteLimits = mapOf(
                TasteBand.BASS to TasteBand.BASS.minDb..0.0,
                TasteBand.WARMTH to TasteBand.WARMTH.minDb..0.0,
                TasteBand.TREBLE to TasteBand.TREBLE.minDb..0.0,
                TasteBand.AIR to TasteBand.AIR.minDb..0.0,
            ),
            earTuning = 1.0 / HearingCompensation.DEFAULT_STRENGTH,
            volumeBass = 0.0,
            volumeTreble = 0.0,
            leveller = Leveller(thresholdDb = -24f, ratio = 2f, attackMs = 30f, releaseMs = 400f),
        )
    }
}
