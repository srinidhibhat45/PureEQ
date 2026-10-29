package app.pureeq.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import app.pureeq.MainActivity
import app.pureeq.PureEqApp
import app.pureeq.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Background update runs, kept off the battery: every 30 minutes only while the phone charges on
 * Wi-Fi, otherwise once a day (never on low battery), plus one run a few seconds after the app
 * closes with an update waiting. The app itself checks whenever it opens.
 */
class UpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val updater = (application as PureEqApp).updater
        scope.launch {
            runCatching { updater.runInBackground() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return params.jobId == SOON
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val WHILE_CHARGING = 4101
        private const val DAILY = 4102
        private const val SOON = 4103
        private const val MINUTE = 60_000L

        /** Called at every process start; reschedules only when a definition changed. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            ensure(
                js,
                JobInfo.Builder(WHILE_CHARGING, component(context))
                    .setPeriodic(30 * MINUTE)
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPersisted(true)
                    .build(),
            )
            ensure(
                js,
                JobInfo.Builder(DAILY, component(context))
                    .setPeriodic(24 * 60 * MINUTE)
                    .setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }

        /** Installs a waiting update shortly after the app closes (not on low battery). */
        fun runSoon(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(SOON, component(context))
                    .setMinimumLatency(5_000)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
        }

        private fun component(context: Context) = ComponentName(context, UpdateJob::class.java)

        private fun ensure(js: JobScheduler, job: JobInfo) {
            val p = js.getPendingJob(job.id)
            val same = p != null && p.intervalMillis == job.intervalMillis && p.isRequireCharging == job.isRequireCharging &&
                p.isRequireBatteryNotLow == job.isRequireBatteryNotLow && p.isPersisted == job.isPersisted
            if (!same) js.schedule(job)
        }
    }
}

/** Receives PackageInstaller's verdict on an update. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        (context.applicationContext as PureEqApp).updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirm)
    }

    companion object {
        const val ACTION_STATUS = "app.pureeq.UPDATE_STATUS"
    }
}

internal object UpdateNotifications {
    private const val CHANNEL_ID = "updates"
    private const val READY_ID = 2
    private const val UPDATED_ID = 3

    fun ready(context: Context, m: UpdateManifest) {
        val tap = "Tap to install. PureEQ closes for a moment while it updates."
        post(
            context,
            READY_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("PureEQ ${m.versionName} is ready")
                .setContentText(tap)
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(m.notes, tap).filter { it.isNotBlank() }.joinToString("\n\n")))
                .setContentIntent(openApp(context, Updater.ACTION_INSTALL)),
        )
    }

    fun updated(context: Context, versionName: String, notes: String) {
        val text = notes.ifBlank { "Tap to open." }
        post(
            context,
            UPDATED_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("PureEQ updated to $versionName")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSilent(true)
                .setContentIntent(openApp(context, null)),
        )
    }

    fun cancelReady(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(READY_ID)

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.update_channel_description)
                },
            )
        }
        nm.notify(id, builder.setSmallIcon(R.drawable.ic_stat_eq).setAutoCancel(true).build())
    }

    private fun openApp(context: Context, action: String?): PendingIntent = PendingIntent.getActivity(
        context,
        if (action == null) 10 else 11,
        Intent(context, MainActivity::class.java).setAction(action ?: Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
