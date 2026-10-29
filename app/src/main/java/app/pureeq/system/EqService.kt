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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.pureeq.MainActivity
import app.pureeq.PureEqApp
import app.pureeq.R
import app.pureeq.audio.OutputDevice
import app.pureeq.profile.AppSettings
import app.pureeq.profile.Profile
import app.pureeq.profile.ProfileEq
import app.pureeq.profile.SystemEqMode
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
 */
class EqService : LifecycleService() {

    private val app get() = application as PureEqApp
    private var global: DpEffect? = null
    private val sessions = HashMap<Int, DpEffect>()
    private val knownSessions = LinkedHashSet<Int>()
    private var curves: SystemCurves? = null
    private var lastOutput: OutputDevice? = null
    private var appliedMode: SystemEqMode? = null
    private var appliedProfileId: String? = null
    /** The profile the effects currently carry; null forces a rebuild. */
    private var appliedProfile: Profile? = null
    private var paused = false
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
        startInForeground(buildNotification("Starting…"))
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_TURN_OFF) {
            app.store.updateSettings { it.copy(systemEqEnabled = false) }
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
        if (!s.settings.systemEqEnabled) {
            releaseAll()
            EqRuntime.status.value = SystemEqStatus.Off
            stopSelf()
            return
        }
        val outputChanged = lastOutput != null && lastOutput != s.output
        if (outputChanged) needsRecreate = true
        lastOutput = s.output

        // Headphones you made a profile with just connected: switch to it, unless the profile
        // in use was already made with them (you may keep several for one pair).
        if (outputChanged && s.settings.autoSwitch) {
            val active = s.profiles.firstOrNull { it.id == s.activeId }
            val match = s.profiles.firstOrNull { it.deviceName != null && it.deviceName == s.output.name }
            if (match != null && active?.deviceName != s.output.name) {
                app.store.setActive(match.id)
                return
            }
        }
        val profile = s.profiles.firstOrNull { it.id == s.activeId } ?: s.profiles.firstOrNull()
        if (profile == null) {
            releaseAll()
            EqRuntime.status.value = SystemEqStatus.Failed("No profile yet")
            updateNotification("No profile yet. Open PureEQ to create one.")
            return
        }
        val modeChanged = appliedMode != s.settings.mode
        val effectsLive = s.settings.mode == SystemEqMode.PER_APP || global != null
        // When only the pause state changed (PureEQ started or stopped its own sound), the effects
        // already carry this profile: just flip them on or off below.
        if (profile != appliedProfile || needsRecreate || modeChanged || !effectsLive) {
            val newCurves = withContext(Dispatchers.Default) { SystemCurves.of(ProfileEq(profile)) }
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
                val msg = "This phone refused the system equalizer (${e.javaClass.simpleName}). Try the \"Music apps\" mode in Settings."
                EqRuntime.status.value = SystemEqStatus.Failed(msg)
                updateNotification("Couldn't start the equalizer")
                return
            }
        }
        paused = s.suspended
        global?.setEnabled(!paused)
        sessions.values.forEach { it.setEnabled(!paused) }
        publishStatus(profile)
    }

    private fun publishStatus(profile: Profile) {
        val global = appliedMode == SystemEqMode.GLOBAL
        EqRuntime.status.value = if (paused) {
            SystemEqStatus.Paused(profile.name)
        } else {
            SystemEqStatus.Active(profile.name, global, sessions.size)
        }
        val where = if (global) "All audio" else "Music apps · ${sessions.size} playing"
        updateNotification(if (paused) "${profile.name} · paused while PureEQ plays" else "${profile.name} · $where")
    }

    private fun openSession(session: Int) {
        knownSessions += session
        if (appliedMode != SystemEqMode.PER_APP) return
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
        app.store.profile(appliedProfileId)?.let { if (appliedMode == SystemEqMode.PER_APP) publishStatus(it) }
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
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
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
            this, 1, Intent(this, EqService::class.java).setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eq)
            .setContentTitle("PureEQ is on")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Turn off", off)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "eq"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TURN_OFF = "app.pureeq.TURN_OFF"

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
        if (s.systemEqEnabled && (s.startOnBoot || updated)) EqService.start(context)
        if (updated) app.updater.onPackageReplaced()
    }
}
