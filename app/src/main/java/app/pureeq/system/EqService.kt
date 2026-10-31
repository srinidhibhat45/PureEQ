package app.pureeq.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.pureeq.BuildConfig
import app.pureeq.MainActivity
import app.pureeq.PureEqApp
import app.pureeq.R
import app.pureeq.audio.ListeningLevel
import app.pureeq.audio.OutputDevice
import app.pureeq.profile.AppSettings
import app.pureeq.profile.OutputStates
import app.pureeq.profile.Profile
import app.pureeq.profile.ProfileEq
import app.pureeq.profile.SystemEqMode
import app.pureeq.profile.VolumeBass
import app.pureeq.ui.Labels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the active profile applied to system audio. A foreground service is required because the
 * effect only lives as long as this process. It follows the active profile, settings and output
 * device, and rebuilds the effect whenever the output changes: the effect fixes its sample rate
 * when created, so a Bluetooth/USB switch would otherwise mis-map frequencies.
 *
 * It runs while the EQ is on for any output ([OutputStates]). On an output it's off for (the phone
 * speaker, unless turned on there) it holds no effect at all and waits for one it's on for.
 */
class EqService : LifecycleService() {

    private val app get() = application as PureEqApp
    private var global: DpEffect? = null
    private val sessions = HashMap<Int, DpEffect>()
    private val knownSessions = LinkedHashSet<Int>()
    private var curves: SystemCurves? = null
    /** [curves] before the volume's shelves. */
    private var baseCurves: SystemCurves? = null
    private var shelves = VolumeBass.FLAT
    /** The media volume, as of the last change or output switch. */
    private var level: ListeningLevel? = null
    private var lastOutput: OutputDevice? = null
    private var appliedMode: SystemEqMode? = null
    private var appliedProfileId: String? = null
    /** The profile the effects currently carry; null forces a rebuild. */
    private var appliedProfile: Profile? = null
    private var paused = false
    /** Off for the output playing now: no effects, and players that open meanwhile get none. */
    private var standby = false
    /** Set when the output changes; cleared only once the effects really are rebuilt for it. */
    private var needsRecreate = false

    private data class State(
        val profiles: List<Profile>,
        val activeId: String?,
        val settings: AppSettings,
        val suspended: Boolean,
        val output: OutputDevice,
    )

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (session <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> openSession(session)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> closeSession(session)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground(buildNotification(getString(R.string.notif_starting)))
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(this, sessionReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        lifecycleScope.launch {
            combine(
                app.store.profiles,
                app.store.activeId,
                app.store.settings,
                EqRuntime.suspended,
                app.route.outputChanges(),
            ) { profiles, activeId, settings, suspended, output -> State(profiles, activeId, settings, suspended, output) }
                .collectLatest { apply(it) }
        }
        lifecycleScope.launch {
            app.route.volumeChanges(this@EqService).collect {
                level = it
                followVolume()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_TURN_OFF -> {
                val out = app.route.currentOutput()
                app.store.updateSettings { it.copy(outputs = it.outputs.turned(out, false)) }
            }
            ACTION_TURN_OFF_ALL -> app.store.updateSettings { it.copy(outputs = it.outputs.allOff()) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sessionReceiver) }
        releaseAll()
        EqRuntime.status.value = SystemEqStatus.Off
        super.onDestroy()
    }

    private suspend fun apply(s: State) {
        val outputs = s.settings.outputs
        if (!outputs.armed) {
            releaseAll()
            EqRuntime.status.value = SystemEqStatus.Off
            stopSelf()
            return
        }
        val outputChanged = lastOutput != null && lastOutput != s.output
        if (outputChanged) {
            needsRecreate = true
            // Each output keeps its own volume.
            level = app.route.level()
        }
        lastOutput = s.output

        // Another output started playing: switch to the profile last used there, or to the one made
        // with these headphones unless the profile in use already was (you may keep several for one pair).
        if (outputChanged && s.settings.autoSwitch) {
            val match = outputs.profileFor(s.output, s.profiles, s.activeId)
            if (match != null && match.id != s.activeId) {
                app.store.setActive(match.id)
                return
            }
        }
        val profile = s.profiles.firstOrNull { it.id == s.activeId } ?: s.profiles.firstOrNull()
        if (profile == null) {
            releaseAll()
            EqRuntime.status.value = SystemEqStatus.Failed(getString(R.string.status_no_profile))
            updateNotification(getString(R.string.notif_no_profile))
            return
        }
        if (!outputs.isOn(s.output, s.profiles)) {
            // Off for this output: no effect at all, so what plays here is untouched. Rebuilt from
            // scratch when an output it's on for comes back.
            releaseAll()
            needsRecreate = true
            standby = true
            EqRuntime.volumeShelves.value = null
            val onFor = outputs.onElsewhere(s.output, s.profiles)
            EqRuntime.status.value = SystemEqStatus.Standby(s.output, onFor)
            updateNotification(
                getString(R.string.notif_comes_back, Labels.outputList(this, onFor)),
                title = getString(R.string.notif_off_for, Labels.outputInText(this, OutputStates.keyOf(s.output))),
                standby = true,
            )
            return
        }
        standby = false
        val modeChanged = appliedMode != s.settings.mode
        val effectsLive = s.settings.mode == SystemEqMode.PER_APP || global != null
        // When only the pause state changed (PureEQ started or stopped its own sound), the effects
        // already carry this profile: just flip them on or off below.
        if (profile != appliedProfile || needsRecreate || modeChanged || !effectsLive) {
            val base = withContext(Dispatchers.Default) { SystemCurves.of(ProfileEq(profile)) }
            baseCurves = base
            shelves = VolumeBass.shelves(profile, s.output, level ?: app.route.level()).rounded()
            if (BuildConfig.DEBUG) Log.d("PureEQ", "apply ${profile.name} on ${s.output.name}: volume bass ${shelves.bassDb} dB, treble ${shelves.trebleDb} dB")
            val newCurves = base.with(shelves, VolumeBass.needsHeadroom(s.output))
            curves = newCurves
            appliedMode = s.settings.mode
            appliedProfileId = profile.id
            try {
                when (s.settings.mode) {
                    SystemEqMode.GLOBAL -> {
                        releaseSessions()
                        val g = global
                        if (g == null || needsRecreate || modeChanged) {
                            g?.release()
                            global = null
                            global = DpEffect(0, newCurves)
                        } else {
                            g.update(newCurves)
                        }
                    }
                    SystemEqMode.PER_APP -> {
                        global?.release()
                        global = null
                        if (needsRecreate || modeChanged) {
                            releaseSessions()
                            knownSessions.toList().forEach { openSession(it) }
                        } else {
                            sessions.values.forEach { it.update(newCurves) }
                        }
                    }
                }
                needsRecreate = false
                appliedProfile = profile
            } catch (e: Exception) {
                releaseAll()
                val msg = getString(R.string.status_refused, e.javaClass.simpleName)
                EqRuntime.status.value = SystemEqStatus.Failed(msg)
                updateNotification(getString(R.string.notif_failed))
                return
            }
        }
        paused = s.suspended
        global?.setEnabled(!paused)
        sessions.values.forEach { it.setEnabled(!paused) }
        EqRuntime.volumeShelves.value = shelves
        publishStatus(profile)
        // What this output comes back to next time: on, with this profile.
        val used = outputs.used(s.output, profile.id)
        if (used != outputs) app.store.updateSettings { it.copy(outputs = it.outputs.used(s.output, profile.id)) }
    }

    /** The volume moved: adds or takes away the bass it calls for, if that changed by a step. */
    private fun followVolume() {
        val profile = appliedProfile ?: return
        val output = lastOutput ?: return
        val base = baseCurves ?: return
        if (standby) return
        val next = VolumeBass.shelves(profile, output, level).rounded()
        if (next == shelves) return
        if (BuildConfig.DEBUG) Log.d("PureEQ", "volume ${level?.index}/${level?.maxIndex} (${level?.db} dB) on ${output.name}: bass ${next.bassDb} dB, treble ${next.trebleDb} dB")
        shelves = next
        val c = base.with(next, VolumeBass.needsHeadroom(output))
        curves = c
        runCatching {
            global?.update(c)
            sessions.values.forEach { it.update(c) }
        }
        EqRuntime.volumeShelves.value = next
    }

    private fun publishStatus(profile: Profile) {
        val global = appliedMode == SystemEqMode.GLOBAL
        EqRuntime.status.value = if (paused) {
            SystemEqStatus.Paused(profile.name)
        } else {
            SystemEqStatus.Active(profile.name, global, sessions.size)
        }
        updateNotification(
            when {
                paused -> getString(R.string.notif_paused, profile.name)
                global -> getString(R.string.notif_all_audio, profile.name)
                else -> resources.getQuantityString(R.plurals.notif_music_apps, sessions.size, profile.name, sessions.size)
            },
        )
    }

    private fun openSession(session: Int) {
        knownSessions += session
        if (appliedMode != SystemEqMode.PER_APP || standby) return
        val c = curves ?: return
        sessions.remove(session)?.release()
        runCatching { DpEffect(session, c) }.onSuccess {
            it.setEnabled(!paused)
            sessions[session] = it
        }
        app.store.profile(appliedProfileId)?.let { publishStatus(it) }
    }

    private fun closeSession(session: Int) {
        knownSessions -= session
        sessions.remove(session)?.release()
        app.store.profile(appliedProfileId)?.let { if (appliedMode == SystemEqMode.PER_APP && !standby) publishStatus(it) }
    }

    private fun releaseSessions() {
        sessions.values.forEach { it.release() }
        sessions.clear()
    }

    private fun releaseAll() {
        global?.release()
        global = null
        releaseSessions()
        appliedProfile = null
        EqRuntime.volumeShelves.value = null
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String, title: String = getString(R.string.notif_on), standby: Boolean = false) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, title, standby))
    }

    /** [standby]: off for this output; its button then turns PureEQ off for every output. */
    private fun buildNotification(text: String, title: String = getString(R.string.notif_on), standby: Boolean = false): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.eq_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.eq_channel_description)
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val off = PendingIntent.getService(
            this, if (standby) 2 else 1, Intent(this, EqService::class.java).setAction(if (standby) ACTION_TURN_OFF_ALL else ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eq)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(if (standby) R.string.notif_turn_off_everywhere else R.string.notif_turn_off), off)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "eq"
        private const val NOTIFICATION_ID = 1
        /** Off for the output playing now. */
        private const val ACTION_TURN_OFF = "app.pureeq.TURN_OFF"

        /** Off for every output: the service stops. */
        private const val ACTION_TURN_OFF_ALL = "app.pureeq.TURN_OFF_ALL"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, EqService::class.java)) }
        }
    }
}

/** Restores the system EQ after a reboot or an app update (an update always restores it). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as PureEqApp
        val s = app.store.settings.value
        val updated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (s.outputs.armed && (s.startOnBoot || updated)) EqService.start(context)
        if (updated) app.updater.onPackageReplaced()
    }
}
