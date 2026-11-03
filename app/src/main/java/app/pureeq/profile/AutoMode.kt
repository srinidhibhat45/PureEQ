package app.pureeq.profile

import android.media.AudioAttributes
import kotlin.math.max

/**
 * Auto: the [ListeningMode] for what's playing, read from the audio attributes apps give their
 * players. Android tells any app the usage and content type of each player that is playing, and
 * nothing else: no app name, session or output (AOSP PlaybackActivityMonitor, the same from
 * API 26 to 36). So that is all Auto goes on:
 * - A game (USAGE_GAME) → Gaming, whatever plays alongside. Most engines don't say so (Oboe
 *   defaults to media/music, OpenSL's music stream reads as music), so most games play as Music.
 * - Speech → Voice, music → Music, films and videos → General.
 * - Content the app doesn't name → [FALLBACK]. Common: Media3/ExoPlayer and SoundPool report
 *   media with content unknown unless the app sets it.
 * - Different kinds at once → General, the mode for a mix.
 *
 * Only players whose sound reaches the system-wide EQ count: media, games and players without a
 * usage. Notifications, alarms, navigation and the assistant take other paths, calls never reach
 * an app's effect, and media UI sounds (CONTENT_TYPE_SONIFICATION) are too short to switch for.
 */
object AutoMode {
    /** A player that's playing, as any app sees it: its [AudioAttributes] usage and content type. */
    data class Player(val usage: Int, val contentType: Int)

    /** What Auto plays when the players don't say what they play: the profile as tuned. */
    val FALLBACK = ListeningMode.MUSIC

    /** The mode for [players], or null when none of them counts (nothing to go on). */
    fun modeFor(players: List<Player>): ListeningMode? {
        val counted = players.filter { counts(it) }
        if (counted.isEmpty()) return null
        if (counted.any { it.usage == AudioAttributes.USAGE_GAME }) return ListeningMode.GAMING
        val named = counted.mapNotNull { named(it.contentType) }.toSet()
        return when (named.size) {
            0 -> FALLBACK
            1 -> named.single()
            else -> ListeningMode.GENERAL
        }
    }

    private fun counts(p: Player): Boolean = when (p.usage) {
        AudioAttributes.USAGE_GAME -> true
        AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_UNKNOWN -> p.contentType != AudioAttributes.CONTENT_TYPE_SONIFICATION
        else -> false
    }

    private fun named(contentType: Int): ListeningMode? = when (contentType) {
        AudioAttributes.CONTENT_TYPE_SPEECH -> ListeningMode.VOICE
        AudioAttributes.CONTENT_TYPE_MUSIC -> ListeningMode.MUSIC
        AudioAttributes.CONTENT_TYPE_MOVIE -> ListeningMode.GENERAL
        else -> null
    }
}

/**
 * Keeps Auto steady. What's playing has to ask for another mode for [stableMs] without a break
 * before the sound changes, so a pause, a track change or a short clip doesn't flip it back and
 * forth, and the effect is rebuilt (the frame changes to and from Gaming) at most once for each
 * change of what plays. Silence changes nothing. The first thing heard is taken at once: there is
 * no earlier pick to keep steady.
 */
class AutoSwitch(private val stableMs: Long = STABLE_MS) {
    /** Auto's pick; null until anything has been heard. */
    var mode: ListeningMode? = null
        private set
    private var pending: ListeningMode? = null
    private var dueAt = 0L

    /** What's playing asks for [heard] (null: nothing that counts) from [now] on (ms). */
    fun heard(heard: ListeningMode?, now: Long) {
        when {
            heard == null || heard == mode -> pending = null
            heard != pending -> {
                pending = heard
                dueAt = if (mode == null) now else now + stableMs
            }
        }
    }

    /** How long until [take] can switch (ms), or null when no switch is waiting. */
    fun waitMs(now: Long): Long? = pending?.let { max(0L, dueAt - now) }

    /** Switches to what has been asked for long enough and returns it; null when nothing changes. */
    fun take(now: Long): ListeningMode? {
        val p = pending ?: return null
        if (now < dueAt) return null
        mode = p
        pending = null
        return p
    }

    companion object {
        const val STABLE_MS = 3000L
    }
}
