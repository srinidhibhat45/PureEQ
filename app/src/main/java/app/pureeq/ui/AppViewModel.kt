package app.pureeq.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pureeq.PureEqApp
import app.pureeq.audio.ListeningOption
import app.pureeq.audio.Matching
import app.pureeq.audio.MusicTrack
import app.pureeq.audio.NoiseMeter
import app.pureeq.audio.OutputDevice
import app.pureeq.dsp.Ear
import app.pureeq.dsp.Stimulus
import app.pureeq.hearing.HeadphoneType
import app.pureeq.hearing.HearingSession
import app.pureeq.hearing.HearingTestResult
import app.pureeq.hearing.TestLength
import app.pureeq.profile.Profile
import app.pureeq.profile.ProfileEq
import app.pureeq.profile.ProfileStore
import app.pureeq.system.EqRuntime
import app.pureeq.system.EqService
import app.pureeq.taste.Choice
import app.pureeq.taste.Comparison
import app.pureeq.taste.TasteSession
import app.pureeq.taste.TasteSettings
import app.pureeq.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.random.Random

sealed interface Screen {
    data object Home : Screen
    data object Intro : Screen
    data object Headphones : Screen
    data object Quiet : Screen
    data object Volume : Screen
    data object Hearing : Screen
    data object HearingDone : Screen
    data object TasteIntro : Screen
    data object Taste : Screen
    data class Editor(val profileId: String?) : Screen
    data class Export(val profileId: String) : Screen
    data object Settings : Screen
    data object About : Screen
}

enum class PauseReason(val title: String, val text: String) {
    USER("Paused", "Take a breath. Continue whenever you're ready."),
    VOLUME("Volume changed", "The test needs the volume you set at the start. Restore it and carry on."),
    HEADPHONES("Headphones disconnected", "Reconnect the same headphones to continue."),
    BACKGROUND("Paused", "The test paused while PureEQ was in the background."),
}

/** Everything gathered while creating a new profile. */
data class Draft(
    val id: String = UUID.randomUUID().toString(),
    val length: TestLength = TestLength.STANDARD,
    val includeHearing: Boolean = true,
    val device: OutputDevice? = null,
    /** Picks the target curve the hearing test is measured against. */
    val headphones: HeadphoneType = HeadphoneType.guess(device?.name),
    val headphonesChosen: Boolean = false,
    val noiseDb: Double? = null,
    val volumeIndex: Int? = null,
    val hearing: HearingTestResult? = null,
    val taste: TasteSettings = TasteSettings(),
    /** The taste test's result (or the starting point when it was skipped), before any tweaks. */
    val recommendedTaste: TasteSettings? = null,
    /** Set when redoing the taste test of this saved profile: the result goes back into it. */
    val retuneOf: String? = null,
)

data class QuietUi(val measuring: Boolean = false, val level: Double? = null, val result: Double? = null, val failed: Boolean = false)

enum class ChannelVerdict { OK, SWAPPED, MONO, NOT_HEARD }

/** The left/right check that follows setting the volume: one beep on a random side. */
data class ChannelUi(val checking: Boolean = false, val side: Ear = Ear.LEFT, val playing: Boolean = false, val verdict: ChannelVerdict? = null)

data class HearingUi(
    val playing: Boolean = false,
    val canSayYes: Boolean = false,
    val canSayNo: Boolean = false,
    val progress: Float = 0f,
    val freq: Double? = null,
    val pause: PauseReason? = null,
    val guessingWarning: Boolean = false,
)

data class TasteUi(
    val comparison: Comparison? = null,
    val listening: Choice = Choice.A,
    val answered: Int = 0,
    val total: Int = 8,
    /** 0..1: how settled PureEQ's estimate of your taste is. */
    val certainty: Float = 0f,
)

data class EditorUi(
    val profile: Profile,
    val eq: ProfileEq? = null,
    val isDraft: Boolean,
    val previewing: Boolean = false,
    val eqOn: Boolean = true,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val pure = application as PureEqApp
    val store: ProfileStore = pure.store
    private val engine = pure.engine
    private val route = pure.route
    private val library = pure.library
    private val updater = pure.updater

    // ---- Navigation ---------------------------------------------------------------------------

    private val stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val screen: StateFlow<Screen> = stack.map { it.last() }.stateIn(viewModelScope, SharingStarted.Eagerly, Screen.Home)

    fun go(s: Screen) {
        leave(stack.value.last())
        stack.update { it + s }
        enter(s)
    }

    private fun replace(s: Screen) {
        leave(stack.value.last())
        stack.update { it.dropLast(1) + s }
        enter(s)
    }

    /** Returns false when already at the root. */
    fun back(): Boolean {
        if (stack.value.size <= 1) return false
        leave(stack.value.last())
        stack.update { it.dropLast(1) }
        enter(stack.value.last())
        return true
    }

    fun home() {
        leave(stack.value.last())
        stack.value = listOf(Screen.Home)
        enter(Screen.Home)
    }

    /** Pops back to [target] and re-enters it, or goes home when it isn't on the stack. */
    private fun backTo(target: Screen) {
        val i = stack.value.lastIndexOf(target)
        if (i < 0) return home()
        leave(stack.value.last())
        stack.value = stack.value.take(i + 1)
        enter(target)
    }

    private val audioScreens = setOf(Screen.Volume, Screen.Hearing, Screen.HearingDone, Screen.TasteIntro, Screen.Taste)

    /** Screens of the new-profile flow, up to saving it: an app restart (an update) would lose them. */
    private fun Screen.holdsUnsavedWork() = when (this) {
        Screen.Home, Screen.Settings, Screen.About, is Screen.Export -> false
        is Screen.Editor -> profileId == null
        else -> true
    }

    private fun enter(s: Screen) {
        updater.busy = stack.value.any { it.holdsUnsavedWork() }
        val audio = s in audioScreens
        EqRuntime.hold("flow", audio)
        // Keep the output stream running between beeps: Bluetooth earbuds that let an idle
        // stream sleep can clip the start of the next sound.
        engine.setKeepAlive(audio)
        if (audio) route.requestFocus()
        when (s) {
            Screen.Home -> route.abandonFocus()
            Screen.Volume -> {
                _channel.value = ChannelUi()
                startVolumeTone()
            }
            Screen.Hearing -> startHearing()
            Screen.TasteIntro -> viewModelScope.launch { prepareTaste() }
            Screen.Taste -> startTaste()
            is Screen.Editor -> openEditor(s.profileId)
            else -> Unit
        }
    }

    private fun leave(s: Screen) {
        when (s) {
            Screen.Volume -> {
                channelJob?.cancel()
                engine.stopTone()
            }
            Screen.Hearing -> {
                hearingJob?.cancel()
                engine.stopTone()
            }
            Screen.Quiet -> noiseJob?.cancel()
            Screen.Taste, Screen.TasteIntro -> {
                choosing?.cancel()
                setMusicPlaying(false)
            }
            is Screen.Editor -> {
                setPreview(false)
                editorJob?.cancel()
            }
            else -> Unit
        }
    }

    // ---- Shared state ------------------------------------------------------------------------

    val output: StateFlow<OutputDevice> = route.outputChanges().stateIn(viewModelScope, SharingStarted.Eagerly, route.currentOutput())

    /** True while a beep is actually sounding (or a silent catch trial is running). */
    val toneActive: StateFlow<Boolean> = engine.toneActive
    val systemStatus = EqRuntime.status

    private val _draft = MutableStateFlow(Draft())
    val draft: StateFlow<Draft> = _draft.asStateFlow()

    fun startNewProfile() {
        _draft.value = Draft(device = route.currentOutput())
        go(Screen.Intro)
    }

    fun setTestLength(l: TestLength) = _draft.update { it.copy(length = l) }
    fun setIncludeHearing(on: Boolean) = _draft.update { it.copy(includeHearing = on) }
    fun setHeadphones(type: HeadphoneType) = _draft.update { it.copy(headphones = type, headphonesChosen = true) }

    /** After the intro: hearing-test route or straight to taste tuning. */
    fun beginChecks() {
        updateDevice()
        go(Screen.Headphones)
    }

    fun afterHeadphones() {
        updateDevice()
        if (_draft.value.includeHearing) go(Screen.Quiet) else go(Screen.TasteIntro)
    }

    /** Follows the connected headphones; the type follows their name until the listener picks one. */
    fun updateDevice() {
        val out = route.currentOutput()
        _draft.update { if (it.headphonesChosen) it.copy(device = out) else it.copy(device = out, headphones = HeadphoneType.guess(out.name)) }
    }

    fun onAppStopped() {
        if (screen.value == Screen.Hearing) pauseHearing(PauseReason.BACKGROUND)
        setMusicPlaying(false)
        if (screen.value == Screen.Volume) {
            channelJob?.cancel()
            engine.stopTone()
        }
        engine.setKeepAlive(false)
        // PureEQ plays nothing in the background, so hand the system EQ back to other apps' audio.
        if (_editor.value?.previewing == true) setPreview(false)
        EqRuntime.hold("flow", false)
    }

    fun onAppStarted() {
        _installAllowed.value = updater.installAllowed()
        val audio = screen.value in audioScreens
        EqRuntime.hold("flow", audio)
        engine.setKeepAlive(audio)
        if (screen.value == Screen.Volume) {
            _channel.value = ChannelUi()
            startVolumeTone()
        }
        if (store.settings.value.systemEqEnabled) EqService.start(getApplication())
    }

    // ---- Room noise --------------------------------------------------------------------------

    private val _quiet = MutableStateFlow(QuietUi())
    val quiet: StateFlow<QuietUi> = _quiet.asStateFlow()
    private var noiseJob: Job? = null

    @android.annotation.SuppressLint("MissingPermission")
    fun measureNoise() {
        noiseJob?.cancel()
        _quiet.value = QuietUi(measuring = true)
        noiseJob = viewModelScope.launch {
            val db = runCatching {
                NoiseMeter(getApplication()).measure(3.0) { level -> _quiet.update { it.copy(level = level) } }
            }.getOrNull()
            _quiet.value = QuietUi(measuring = false, level = db, result = db, failed = db == null)
            _draft.update { it.copy(noiseDb = db) }
        }
    }

    // ---- Volume setup ------------------------------------------------------------------------

    val volumeMax: Int get() = route.maxVolume
    val volumeMin: Int get() = route.minVolume
    fun currentVolume(): Int = route.volume
    fun setVolume(index: Int) {
        route.volume = index
    }

    private fun startVolumeTone() {
        engine.playTone(Stimulus(1000.0, Ear.BOTH, CAL_DB, warble = store.settings.value.warbleTones, repeat = true))
    }

    /** The volume is set; one beep on a random side checks left and right before the test. */
    fun confirmVolume() {
        engine.stopTone()
        _draft.update { it.copy(volumeIndex = route.volume) }
        playChannel(if (Random.nextBoolean()) Ear.LEFT else Ear.RIGHT)
    }

    // ---- Left / right check ------------------------------------------------------------------

    private val _channel = MutableStateFlow(ChannelUi())
    val channel: StateFlow<ChannelUi> = _channel.asStateFlow()
    private var channelJob: Job? = null

    fun playChannel(side: Ear) {
        channelJob?.cancel()
        _channel.value = ChannelUi(checking = true, side = side, playing = true)
        debugLog { "channel side=$side" }
        engine.playTone(Stimulus(1000.0, side, CHANNEL_DB, warble = store.settings.value.warbleTones, pulses = 3))
    }

    fun retryChannel() = playChannel(if (Random.nextBoolean()) Ear.LEFT else Ear.RIGHT)

    /** Back to adjusting the volume, e.g. when the check beep was too quiet to place. */
    fun redoVolume() {
        channelJob?.cancel()
        _channel.value = ChannelUi()
        startVolumeTone()
    }

    /** [heard] is where the listener heard the beep: LEFT, RIGHT, BOTH, or null for nowhere. Correct goes straight on to the test. */
    fun answerChannel(heard: Ear?) {
        engine.stopTone()
        val c = _channel.value
        val verdict = when (heard) {
            null -> ChannelVerdict.NOT_HEARD
            Ear.BOTH -> ChannelVerdict.MONO
            c.side -> ChannelVerdict.OK
            else -> ChannelVerdict.SWAPPED
        }
        _channel.value = c.copy(playing = false, verdict = verdict)
        if (verdict == ChannelVerdict.OK) {
            channelJob = viewModelScope.launch {
                delay(900)
                if (screen.value == Screen.Volume) go(Screen.Hearing)
            }
        }
    }

    // ---- Hearing test ------------------------------------------------------------------------

    private val _hearing = MutableStateFlow(HearingUi())
    val hearing: StateFlow<HearingUi> = _hearing.asStateFlow()
    private var hearingJob: Job? = null
    private val answers = Channel<Boolean>(Channel.CONFLATED)
    private var session: HearingSession? = null
    private var lockedVolume = -1

    private sealed interface Outcome {
        data class Answer(val heard: Boolean) : Outcome
        data object Paused : Outcome
        data object Timeout : Outcome
    }

    fun answer(heard: Boolean) {
        answers.trySend(heard)
    }

    fun pauseHearing(reason: PauseReason) {
        if (_hearing.value.pause == null) _hearing.update { it.copy(pause = reason) }
        engine.stopTone()
    }

    fun resumeHearing() {
        if (_hearing.value.pause == PauseReason.VOLUME && lockedVolume >= 0) route.volume = lockedVolume
        _hearing.update { it.copy(pause = null) }
    }

    fun dismissGuessingWarning() = _hearing.update { it.copy(guessingWarning = false) }

    private fun startHearing() {
        hearingJob?.cancel()
        val d = _draft.value
        val config = d.length.config(CAL_DB, store.settings.value.warbleTones, d.headphones)
        val s = HearingSession(config)
        session = s
        lockedVolume = d.volumeIndex ?: route.volume
        _hearing.value = HearingUi()
        var warned = false
        hearingJob = viewModelScope.launch {
            launch {
                while (isActive) {
                    awaitResumed() // asleep while paused, e.g. in the background
                    delay(400)
                    if (_hearing.value.pause == null && route.volume != lockedVolume) pauseHearing(PauseReason.VOLUME)
                }
            }
            val startedOn = route.currentOutput()
            launch {
                output.collect { if (startedOn.isHeadphones && it != startedOn) pauseHearing(PauseReason.HEADPHONES) }
            }
            while (isActive) {
                awaitResumed()
                val p = s.next() ?: break
                debugLog { "trial ear=${p.ear} f=${p.freq} level=${p.levelDb} catch=${p.isCatch} latencyMs=${engine.latencyMs}" }
                _hearing.update { it.copy(freq = s.currentFrequency, progress = s.progress, playing = false, canSayYes = false, canSayNo = false) }
                // A short, slightly varied gap; silent catch trials keep guessing in check.
                delay(Random.nextLong(350, 600))
                if (_hearing.value.pause != null) continue
                while (answers.tryReceive().isSuccess) Unit
                val stim = Stimulus(p.freq, p.ear, p.levelDb, warble = config.warble, silent = p.isCatch, pulses = 2, offMs = 200.0)
                engine.playTone(stim)
                _hearing.update { it.copy(playing = true, canSayYes = true) }
                // "No" unlocks only once the last beep has reached the ear: measured output latency,
                // with a Bluetooth floor in case the device doesn't report its codec delay.
                val delay = maxOf(engine.latencyMs, if (output.value.kind == OutputDevice.Kind.BLUETOOTH) 250 else 0)
                var outcome = awaitOutcome(stim.durationMs.toLong() + delay + 150)
                if (outcome == Outcome.Timeout) {
                    _hearing.update { it.copy(playing = false, canSayNo = true) }
                    outcome = awaitOutcome(null)
                }
                engine.stopTone()
                if (outcome is Outcome.Answer) s.answer(outcome.heard)
                _hearing.update { it.copy(playing = false, canSayYes = false, canSayNo = false, progress = s.progress) }
                if (!warned && s.catchTrials >= 3 && s.falseAlarms >= 2 && s.falseAlarms.toDouble() / s.catchTrials > 0.34) {
                    warned = true
                    _hearing.update { it.copy(guessingWarning = true) }
                }
            }
            if (isActive) {
                engine.stopTone()
                val result = s.result()
                debugLog { "hearing done trials=${result.trials} " + result.points.joinToString { "${it.ear.name[0]}${it.freq.toInt()}=%.1f".format(it.db) } }
                _draft.update { it.copy(hearing = result) }
                replace(Screen.HearingDone)
            }
        }
    }

    private suspend fun awaitResumed() {
        _hearing.first { it.pause == null }
    }

    private suspend fun awaitOutcome(timeoutMs: Long?): Outcome {
        val wait = suspend {
            merge(
                answers.receiveAsFlow().map { Outcome.Answer(it) },
                _hearing.filter { it.pause != null }.map { Outcome.Paused },
            ).first()
        }
        return if (timeoutMs == null) wait() else withTimeoutOrNull(timeoutMs) { wait() } ?: Outcome.Timeout
    }

    // ---- Music -------------------------------------------------------------------------------

    private val _music = MutableStateFlow<MusicTrack?>(null)
    val music: StateFlow<MusicTrack?> = _music.asStateFlow()
    private val _musicBusy = MutableStateFlow(true)
    val musicBusy: StateFlow<Boolean> = _musicBusy.asStateFlow()
    private val _musicPlaying = MutableStateFlow(false)
    val musicPlaying: StateFlow<Boolean> = _musicPlaying.asStateFlow()
    val musicError = MutableStateFlow<String?>(null)
    private var builtIn: MusicTrack? = null

    init {
        viewModelScope.launch {
            val t = library.builtIn(engine.nativeRate)
            builtIn = t
            if (_music.value == null) setTrack(t)
            _musicBusy.value = false
        }
    }

    fun pickSong(uri: Uri) {
        viewModelScope.launch {
            _musicBusy.value = true
            runCatching { library.decode(uri) }
                .onSuccess { setTrack(it) }
                .onFailure { musicError.value = "Couldn't play that file (${it.message ?: it.javaClass.simpleName})." }
            _musicBusy.value = false
        }
    }

    fun useBuiltIn() {
        builtIn?.let { setTrack(it) }
    }

    private fun setTrack(t: MusicTrack) {
        _music.value = t
        engine.setMusic(t.pcm, t.rate)
        // Loudness matching depends on the music's spectrum.
        when (screen.value) {
            Screen.Taste -> viewModelScope.launch {
                prepareTaste()
                showComparison()
            }
            Screen.TasteIntro -> previewForVolume(_musicPlaying.value)
            is Screen.Editor -> _editor.value?.eq?.let { applyPreview(it) }
            else -> Unit
        }
    }

    fun setMusicPlaying(play: Boolean) {
        engine.playMusic(play && _music.value != null)
        _musicPlaying.value = play && _music.value != null
    }

    /**
     * Taste intro: plays the music at the loudness the comparisons will use (flat EQ, same
     * headroom), so the volume the listener sets here is the volume they compare at.
     */
    fun previewForVolume(play: Boolean) {
        viewModelScope.launch {
            val t = _music.value ?: return@launch
            if (play) prepareTaste()
            val specs = Matching.matched(listOf(ListeningOption.FLAT), t.spectrumDb, tasteHeadroom)
            engine.setSlot(0, specs[0])
            engine.select(0)
            setMusicPlaying(play)
        }
    }

    // ---- Taste test --------------------------------------------------------------------------

    private val _taste = MutableStateFlow(TasteUi())
    val taste: StateFlow<TasteUi> = _taste.asStateFlow()
    private var tasteSession: TasteSession? = null
    private var choosing: Job? = null
    private var hearingBase: ProfileEq? = null
    private var tasteHeadroom: Double? = null
    private var tastePreparedFor: Pair<HearingTestResult?, MusicTrack?>? = null

    /**
     * Fits the hearing correction once for the whole taste test and fixes one headroom for it:
     * the most extreme option the session can reach (bass and treble at their maximum) sets it.
     */
    private suspend fun prepareTaste() {
        val d = _draft.value
        val track = _music.value
        if (tastePreparedFor == (d.hearing to track) && hearingBase != null) return
        val base = withContext(Dispatchers.Default) { ProfileEq(draftProfile().copy(taste = TasteSettings())) }
        hearingBase = base
        if (track != null) {
            val extreme = TasteSession.extreme(d.taste).filters()
            tasteHeadroom = withContext(Dispatchers.Default) {
                Matching.headroom(
                    listOf(ListeningOption(base.hearingFiltersLeft, base.hearingFiltersRight), ListeningOption(base.hearingFiltersLeft + extreme, base.hearingFiltersRight + extreme)),
                    track.spectrumDb,
                )
            }
        }
        tastePreparedFor = d.hearing to track
    }

    private fun startTaste() {
        val d = _draft.value
        val ts = TasteSession(start = d.taste, maxRounds = if (d.includeHearing) d.length.tasteRounds else TasteSession.MAX_ROUNDS)
        tasteSession = ts
        _taste.value = TasteUi(total = ts.total)
        viewModelScope.launch {
            prepareTaste()
            withContext(Dispatchers.Default) { ts.current() }
            showComparison()
            setMusicPlaying(true)
        }
    }

    private fun showComparison() {
        val ts = tasteSession ?: return
        val base = hearingBase ?: return
        val track = _music.value ?: return
        val c = ts.current()
        if (c == null) {
            finishTaste()
            return
        }
        val opts = listOf(Choice.A, Choice.B).map { side ->
            val t = ts.candidate(c, side).filters()
            ListeningOption(base.hearingFiltersLeft + t, base.hearingFiltersRight + t)
        }
        val specs = Matching.matched(opts, track.spectrumDb, tasteHeadroom)
        engine.setSlot(0, specs[0])
        engine.setSlot(1, specs[1])
        engine.select(0)
        debugLog { "compare a=${c.a.bass}/${c.a.treble} b=${c.b.bass}/${c.b.treble}" }
        _taste.update { it.copy(comparison = c, listening = Choice.A, answered = ts.answered, certainty = ts.certainty) }
    }

    fun listen(side: Choice) {
        engine.select(if (side == Choice.B) 1 else 0)
        _taste.update { it.copy(listening = side) }
        if (!_musicPlaying.value) setMusicPlaying(true)
    }

    fun choose(choice: Choice) {
        // Ignore a second tap while the next pair is still being picked.
        if (choosing?.isActive == true) return
        val ts = tasteSession ?: return
        ts.answer(choice)
        // Picking the next pair takes a few milliseconds; keep it off the main thread.
        choosing = viewModelScope.launch {
            withContext(Dispatchers.Default) { ts.current() }
            if (screen.value == Screen.Taste && tasteSession === ts) showComparison()
        }
    }

    fun skipTaste() = finishTaste()

    /**
     * Fine-tune → a fresh taste test for a saved profile, from a flat start like the first one.
     * Its hearing result, name, correction and balance stay; backing out changes nothing.
     */
    fun redoTaste(profileId: String) {
        val p = store.profile(profileId) ?: return
        _draft.value = Draft(includeHearing = p.hearing != null, hearing = p.hearing, retuneOf = p.id)
        go(Screen.TasteIntro)
    }

    /** From the taste intro: keep the hearing result as is and go straight to the editor. */
    fun skipTasteFromIntro() {
        setMusicPlaying(false)
        if (_draft.value.retuneOf != null) {
            back()
            return
        }
        _draft.update { it.copy(recommendedTaste = it.recommendedTaste ?: it.taste) }
        go(Screen.Editor(null))
    }

    private fun finishTaste() {
        tasteSession?.let { ts -> ts.result().let { r -> _draft.update { it.copy(taste = r, recommendedTaste = r) } } }
        debugLog { "taste done ${_draft.value.taste}" }
        setMusicPlaying(false)
        val retune = _draft.value.retuneOf
        if (retune == null) {
            replace(Screen.Editor(null))
            return
        }
        // A redo for a saved profile: the result replaces its taste and becomes what Reset returns to.
        val r = _draft.value.taste
        store.profile(retune)?.let { store.upsert(it.copy(taste = r, recommendedTaste = r)) }
        backTo(Screen.Editor(retune))
    }

    // ---- Editor ------------------------------------------------------------------------------

    private val _editor = MutableStateFlow<EditorUi?>(null)
    val editor: StateFlow<EditorUi?> = _editor.asStateFlow()
    private var editorJob: Job? = null

    private fun draftProfile(): Profile {
        val d = _draft.value
        return Profile(
            id = d.id,
            name = d.device?.takeIf { it.isHeadphones }?.name ?: "My sound",
            createdAt = System.currentTimeMillis(),
            deviceName = d.device?.takeIf { it.isHeadphones }?.name,
            deviceType = d.device?.type,
            hearing = d.hearing,
            taste = d.taste,
            recommendedTaste = d.recommendedTaste ?: d.taste,
        )
    }

    /**
     * Saved profiles always load fresh from the store (edits are saved as they happen). A draft is
     * rebuilt from the flow's results, so a redone taste test shows up; its name, correction
     * strength and balance survive going back and forth.
     */
    private fun openEditor(id: String?) {
        val prev = _editor.value
        val p = if (id != null) {
            store.profile(id) ?: return
        } else {
            val base = draftProfile()
            val keep = prev?.takeIf { it.isDraft && it.profile.id == base.id }?.profile
            if (keep != null) base.copy(name = keep.name, hearingStrength = keep.hearingStrength, balanceDb = keep.balanceDb) else base
        }
        _editor.value = EditorUi(profile = p, isDraft = id == null)
        recompute(save = false)
    }

    fun editProfile(change: (Profile) -> Profile) {
        _editor.update { it?.copy(profile = change(it.profile)) }
        // A draft's taste lives in the flow, so a redone taste test starts from the tweaked values.
        _editor.value?.takeIf { it.isDraft }?.let { ui -> _draft.update { it.copy(taste = ui.profile.taste) } }
        recompute(save = true)
    }

    /**
     * Puts hearing correction, taste and balance back to what the tests recommended. Returns the
     * profile as it was, for Undo, or null when there was nothing to reset.
     */
    fun resetEditor(): Profile? {
        val before = _editor.value?.profile?.takeIf { !it.isRecommended } ?: return null
        editProfile { it.recommended() }
        return before
    }

    /** Undo for [resetEditor]: brings the adjustments back and keeps the current name. */
    fun restoreAdjustments(p: Profile) =
        editProfile { it.copy(hearingStrength = p.hearingStrength, taste = p.taste, balanceDb = p.balanceDb) }

    private fun recompute(save: Boolean) {
        editorJob?.cancel()
        editorJob = viewModelScope.launch {
            delay(40)
            val ui = _editor.value ?: return@launch
            val eq = withContext(Dispatchers.Default) { ProfileEq(ui.profile) }
            _editor.update { it?.copy(eq = eq) }
            if (_editor.value?.previewing == true) applyPreview(eq)
            if (save && !ui.isDraft) store.upsert(ui.profile)
        }
    }

    /** Never returns early: stopping must always release the music and the system-EQ hold. */
    fun setPreview(on: Boolean) {
        _editor.update { it?.copy(previewing = on) }
        EqRuntime.hold("preview", on)
        if (on) {
            route.requestFocus()
            _editor.value?.eq?.let { applyPreview(it) }
        }
        setMusicPlaying(on)
    }

    fun setEqOn(on: Boolean) {
        _editor.update { it?.copy(eqOn = on) }
        engine.select(if (on) 0 else 1)
    }

    private fun applyPreview(eq: ProfileEq) {
        val track = _music.value ?: return
        val specs = Matching.matched(
            listOf(ListeningOption(eq.leftFilters, eq.rightFilters, eq.leftTrimDb, eq.rightTrimDb), ListeningOption.FLAT),
            track.spectrumDb,
        )
        engine.setSlot(0, specs[0])
        engine.setSlot(1, specs[1])
        engine.select(if (_editor.value?.eqOn != false) 0 else 1)
    }

    /** Saves the profile being edited and returns home. A new profile becomes the active one. */
    fun saveEditor() {
        val ui = _editor.value ?: return
        setPreview(false)
        editorJob?.cancel()
        store.upsert(ui.profile)
        if (ui.isDraft) store.setActive(ui.profile.id)
        _editor.value = null
        home()
    }

    // ---- Updates -----------------------------------------------------------------------------

    val updates: StateFlow<UpdateState> = updater.state
    val updatesLastChecked: StateFlow<Long> = updater.lastChecked
    val updatePromptDismissed: StateFlow<Boolean> = updater.promptDismissed
    val updateSource: String get() = updater.source
    val updatesSilent: Boolean get() = updater.installsSilently()

    /** Whether PureEQ may install apps; re-read whenever the app comes back (the user may have just allowed it). */
    private val _installAllowed = MutableStateFlow(updater.installAllowed())
    val installAllowed: StateFlow<Boolean> = _installAllowed.asStateFlow()

    fun checkForUpdates() = updater.checkNow()
    fun installUpdate() = updater.installNow()
    fun dismissUpdatePrompt() = updater.dismissPrompt()

    // ---- Profiles and system EQ --------------------------------------------------------------

    fun setActive(id: String) = store.setActive(id)

    fun deleteProfile(id: String) = store.delete(id)

    fun setSystemEq(enabled: Boolean) {
        store.updateSettings { it.copy(systemEqEnabled = enabled) }
        if (enabled) EqService.start(getApplication())
    }

    fun importProfile(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                }
                ProfileStore.importJson(text, UUID.randomUUID().toString())
            }.onSuccess {
                store.upsert(it)
                store.setActive(it.id)
            }.onFailure { musicError.value = "That file isn't a PureEQ profile." }
        }
    }

    suspend fun profileEq(id: String): ProfileEq? {
        val p = store.profile(id) ?: return null
        return withContext(Dispatchers.Default) { ProfileEq(p) }
    }

    fun writeText(uri: Uri, text: String): Boolean = runCatching {
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
    }.isSuccess

    override fun onCleared() {
        engine.stopTone()
        engine.playMusic(false)
        EqRuntime.hold("flow", false)
        EqRuntime.hold("preview", false)
        route.abandonFocus()
        updater.busy = false
    }

    /** Debug builds log each trial so the test can be driven by a simulated listener over adb. */
    private val debuggable = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private inline fun debugLog(msg: () -> String) {
        if (debuggable) Log.d("PureEQ", msg())
    }

    companion object {
        /** Level of the volume-setup beep: the listener makes this "soft but clear". */
        const val CAL_DB = -40.0
        const val CHANNEL_DB = -30.0
    }
}
