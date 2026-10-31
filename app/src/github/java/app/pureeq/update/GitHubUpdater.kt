package app.pureeq.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import app.pureeq.BuildConfig
import app.pureeq.R
import app.pureeq.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/** The GitHub build's updater; with an update address it also schedules the background checks. */
fun createUpdater(context: Context, store: ProfileStore): Updater =
    GitHubUpdater(context, store).also { if (it.enabled) UpdateJob.schedule(context) }

class UpdateError(message: String) : Exception(message)

/**
 * Over-the-air updates. Reads the update notice compiled into this build ([BuildConfig.UPDATE_URL],
 * normally `github.com/<repo>/releases/latest/download/update.json`). When it announces a newer
 * build, downloads the APK and checks size, SHA-256, package, version and signing key, then
 * installs it with [PackageInstaller].
 *
 * On Android 12+ an app the user allowed to install apps may update itself without a
 * confirmation screen, so updates install by themselves while PureEQ is closed. Otherwise
 * Android asks once per update, from a notification or the Install button.
 */
class GitHubUpdater(private val context: Context, private val store: ProfileStore) : Updater {
    private val prefs = context.getSharedPreferences("pureeq_update", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val dir = File(context.cacheDir, "updates")
    private val url = BuildConfig.UPDATE_URL
    private val allowHttp = BuildConfig.DEBUG
    private val audio = context.getSystemService(AudioManager::class.java)

    override val enabled = url.isNotEmpty()

    override val source: String = runCatching {
        URL(url).let { it.authority + it.path.substringBefore("/releases/").removeSuffix("/update.json") }
    }.getOrDefault("")

    private val _state = MutableStateFlow(if (enabled) restore() else UpdateState.Disabled)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _lastChecked = MutableStateFlow(prefs.getLong(KEY_LAST_CHECK, 0L))
    override val lastChecked: StateFlow<Long> = _lastChecked.asStateFlow()

    private val _promptDismissed = MutableStateFlow(prefs.getBoolean(KEY_PROMPT_DISMISSED, false))
    override val promptDismissed: StateFlow<Boolean> = _promptDismissed.asStateFlow()

    @Volatile override var foreground = false

    @Volatile override var busy = false

    /** The running install came from a button or notification, so a confirmation screen is expected. */
    @Volatile private var userAsked = false

    override fun installAllowed() = context.packageManager.canRequestPackageInstalls()

    override fun installsSilently() = Build.VERSION.SDK_INT >= 31 && installAllowed()

    override fun dismissPrompt() {
        _promptDismissed.value = true
        prefs.edit().putBoolean(KEY_PROMPT_DISMISSED, true).apply()
    }

    override fun checkSoon() {
        if (enabled && System.currentTimeMillis() - _lastChecked.value > RECHECK_MS) scope.launch { check() }
    }

    override fun checkNow() {
        if (enabled) scope.launch { check() }
    }

    override fun installNow() {
        scope.launch { install(userAsked = true) }
    }

    /** With an update waiting: install it shortly, from a job so it runs. */
    override fun onAppHidden() {
        if (_state.value is UpdateState.Ready) UpdateJob.runSoon(context)
    }

    /** From [UpdateJob]: check, then install if nobody is using the app and nothing is playing. */
    suspend fun runInBackground() {
        if (!enabled) return
        check()
        val m = (_state.value as? UpdateState.Ready)?.manifest ?: return
        when {
            foreground || busy -> Unit // the app shows an Install button instead
            !store.settings.value.autoUpdate || !installsSilently() -> notifyOnce(m)
            audio.isMusicActive -> Unit // don't cut into a song; the next run tries again
            else -> install(userAsked = false)
        }
    }

    /** Fetches the notice and, when it announces a newer build, downloads and verifies it. */
    suspend fun check() {
        if (!enabled || !mutex.tryLock()) return
        try {
            checkLocked()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun checkLocked() {
        val before = _state.value
        if (before is UpdateState.Installing) return
        if (before !is UpdateState.Ready) _state.value = UpdateState.Checking
        val m = try {
            fetchManifest()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.value = if (before is UpdateState.Ready) before else UpdateState.Failed(describe(e))
            return
        }
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        _lastChecked.value = now
        if (m == null || m.versionCode <= BuildConfig.VERSION_CODE || m.minSdk > Build.VERSION.SDK_INT) {
            discard()
            _state.value = UpdateState.UpToDate
            return
        }
        if (before is UpdateState.Ready && before.manifest == m && apkFile(m).length() == m.size) {
            _state.value = before
            return
        }
        _state.value = try {
            download(m)
            prefs.edit().putString(KEY_READY, m.toJson().toString()).apply()
            UpdateState.Ready(m)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            UpdateState.Failed(describe(e))
        }
    }

    /** The published notice, or null when nothing has been published yet. */
    private fun fetchManifest(): UpdateManifest? {
        val c = open(url)
        try {
            when (c.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return null
                else -> throw IOException(context.getString(R.string.update_problem_address_answered, c.responseCode))
            }
            val text = c.inputStream.use { it.readAtMost(MAX_NOTICE_BYTES) }.toString(Charsets.UTF_8)
            return UpdateManifest.parse(text, context.packageName, allowHttp)
        } finally {
            c.disconnect()
        }
    }

    private suspend fun download(m: UpdateManifest) {
        discard()
        dir.mkdirs()
        _state.value = UpdateState.Downloading(m, 0f)
        val part = File(dir, apkFile(m).name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val c = open(m.url)
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException(context.getString(R.string.update_problem_download_answered, c.responseCode))
            var got = 0L
            var shown = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        got += n
                        if (got > m.size) throw UpdateError(context.getString(R.string.update_error_too_big))
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        val now = SystemClock.elapsedRealtime()
                        if (now - shown >= 100) {
                            shown = now
                            _state.value = UpdateState.Downloading(m, got.toFloat() / m.size)
                        }
                    }
                }
            }
            if (got != m.size) throw UpdateError(context.getString(R.string.update_error_incomplete))
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
        if (hex(digest.digest()) != m.sha256) {
            part.delete()
            throw UpdateError(context.getString(R.string.update_error_checksum))
        }
        val apk = apkFile(m)
        if (!part.renameTo(apk)) throw IOException(context.getString(R.string.update_problem_not_stored))
        try {
            verifyPackage(apk, m)
        } catch (e: Exception) {
            apk.delete()
            throw e
        }
    }

    /** The download must be this app, the announced version, and signed with this copy's key (or its rotated successor). */
    private fun verifyPackage(apk: File, m: UpdateManifest) {
        val info = archiveInfo(apk) ?: throw UpdateError(context.getString(R.string.update_error_not_an_app))
        if (info.packageName != context.packageName) throw UpdateError(context.getString(R.string.update_error_other_app, info.packageName))
        if (info.longVersionCode != m.versionCode) throw UpdateError(context.getString(R.string.update_error_version))
        val mine = installedInfo().signingInfo
        val theirs = info.signingInfo
        // When Android doesn't report the certificates, PackageInstaller's own check still applies at install.
        if (mine != null && theirs != null && !signers(mine).accepts(signers(theirs))) {
            throw UpdateError(context.getString(R.string.update_error_key))
        }
    }

    private suspend fun install(userAsked: Boolean) = mutex.withLock {
        val m = (_state.value as? UpdateState.Ready)?.manifest ?: return@withLock
        val apk = apkFile(m)
        // Re-check the stored file: it may have sat in the cache for a while.
        if (apk.length() != m.size || hex(sha256(apk)) != m.sha256) {
            discard()
            _state.value = UpdateState.Idle
            return@withLock
        }
        this.userAsked = userAsked
        _state.value = UpdateState.Installing(m)
        // Written synchronously: a silent self-update ends this process within seconds.
        prefs.edit()
            .putLong(KEY_PENDING_CODE, m.versionCode)
            .putString(KEY_PENDING_NAME, m.versionName)
            .putString(KEY_PENDING_NOTES, m.notes)
            .commit()
        UpdateNotifications.cancelReady(context)
        try {
            commit(apk)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(context.getString(R.string.update_error_install_start, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_STATUS)
            // Mutable: PackageInstaller fills in the result.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
        }
    }

    /** PackageInstaller's verdict, from [UpdateReceiver]. */
    fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        val m = when (val s = _state.value) {
            is UpdateState.Installing -> s.manifest
            is UpdateState.Ready -> s.manifest
            else -> readyManifest()
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's "update this app?" screen, or its "allow this source" screen first.
                val shown = confirm != null && userAsked && foreground &&
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (!shown && m != null) {
                    _state.value = UpdateState.Ready(m)
                    UpdateNotifications.ready(context, m)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                discard()
                _state.value = UpdateState.UpToDate
            }
            // Dismissed the confirmation (or the session was replaced): offer it again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = m?.let { UpdateState.Ready(it) } ?: UpdateState.Idle
            else -> _state.value = UpdateState.Failed(describeFailure(status, message))
        }
    }

    override fun onPackageReplaced() {
        val code = prefs.getLong(KEY_PENDING_CODE, -1L)
        if (code in 1..BuildConfig.VERSION_CODE.toLong()) {
            UpdateNotifications.updated(context, BuildConfig.VERSION_NAME, prefs.getString(KEY_PENDING_NOTES, null).orEmpty())
        }
        prefs.edit()
            .remove(KEY_PENDING_CODE)
            .remove(KEY_PENDING_NAME)
            .remove(KEY_PENDING_NOTES)
            .remove(KEY_NOTIFIED)
            .apply()
        discard()
        UpdateNotifications.cancelReady(context)
        _state.value = UpdateState.Idle
    }

    private fun notifyOnce(m: UpdateManifest) {
        if (prefs.getLong(KEY_NOTIFIED, -1L) == m.versionCode) return
        prefs.edit().putLong(KEY_NOTIFIED, m.versionCode).apply()
        UpdateNotifications.ready(context, m)
    }

    /** After a restart: a verified download that's still newer than this build is ready to go. */
    private fun restore(): UpdateState {
        val m = readyManifest() ?: return UpdateState.Idle
        return if (m.versionCode > BuildConfig.VERSION_CODE && apkFile(m).length() == m.size) UpdateState.Ready(m) else UpdateState.Idle
    }

    private fun readyManifest(): UpdateManifest? =
        prefs.getString(KEY_READY, null)?.let { runCatching { UpdateManifest.parse(it, context.packageName, allowHttp) }.getOrNull() }

    private fun discard() {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit().remove(KEY_READY).apply()
    }

    private fun apkFile(m: UpdateManifest) = File(dir, "PureEQ-${m.versionCode}.apk")

    private fun open(address: String): HttpURLConnection = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        useCaches = false
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("User-Agent", "PureEQ/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(apk: File): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    } else {
        context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    /** The certificates as [Signers] digests; the rotation history only exists for a single signer. */
    private fun signers(info: SigningInfo) = Signers(
        current = info.apkContentsSigners.mapTo(HashSet()) { hex(digest(it.toByteArray())) },
        history = if (info.hasMultipleSigners()) emptyList() else info.signingCertificateHistory.orEmpty().map { hex(digest(it.toByteArray())) },
    )

    private fun describe(e: Exception): String = when (e) {
        is UpdateError -> e.message.orEmpty()
        is NoticeRefused -> when (e.problem) {
            NoticeRefused.Problem.OTHER_APP -> context.getString(R.string.update_error_notice_other_app, e.detail)
            NoticeRefused.Problem.NO_VERSION -> context.getString(R.string.update_error_notice_no_version)
            NoticeRefused.Problem.NO_CHECKSUM -> context.getString(R.string.update_error_notice_no_checksum)
            NoticeRefused.Problem.IMPOSSIBLE_SIZE -> context.getString(R.string.update_error_notice_bad_size)
            NoticeRefused.Problem.NOT_HTTPS -> context.getString(R.string.update_error_notice_not_https)
        }
        is IllegalArgumentException -> e.message ?: context.getString(R.string.update_error_notice_invalid)
        is JSONException -> context.getString(R.string.update_error_notice_unreadable)
        is UnknownHostException -> context.getString(R.string.update_error_offline)
        is SocketTimeoutException -> context.getString(R.string.update_error_timeout)
        is SSLException -> context.getString(R.string.update_error_insecure)
        is IOException -> context.getString(R.string.update_error_unreachable, e.message ?: e.javaClass.simpleName)
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun describeFailure(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> context.getString(R.string.update_error_blocked)
        PackageInstaller.STATUS_FAILURE_CONFLICT -> context.getString(R.string.update_error_conflict)
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> context.getString(R.string.update_error_incompatible)
        PackageInstaller.STATUS_FAILURE_INVALID -> context.getString(R.string.update_error_damaged)
        PackageInstaller.STATUS_FAILURE_STORAGE -> context.getString(R.string.update_error_storage)
        else -> message?.let { context.getString(R.string.update_error_not_installed_because, it) } ?: context.getString(R.string.update_error_not_installed)
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException(context.getString(R.string.update_problem_notice_too_large))
        }
        return out.toByteArray()
    }

    companion object {
        private const val RECHECK_MS = 30_000L
        private const val MAX_NOTICE_BYTES = 64 * 1024
        private const val KEY_LAST_CHECK = "lastCheck"
        private const val KEY_READY = "ready"
        private const val KEY_NOTIFIED = "notified"
        private const val KEY_PENDING_CODE = "pendingCode"
        private const val KEY_PENDING_NAME = "pendingName"
        private const val KEY_PENDING_NOTES = "pendingNotes"
        private const val KEY_PROMPT_DISMISSED = "promptDismissed"

        private fun sha256(f: File): ByteArray {
            val d = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    d.update(buf, 0, n)
                }
            }
            return d.digest()
        }

        private fun digest(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
