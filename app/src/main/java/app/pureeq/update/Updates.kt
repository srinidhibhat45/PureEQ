package app.pureeq.update

import kotlinx.coroutines.flow.StateFlow

sealed interface UpdateState {
    /** This build has no update address, so it can't update itself. */
    data object Disabled : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val manifest: UpdateManifest, val fraction: Float) : UpdateState
    /** Downloaded and verified; waiting for a good moment, or a tap, to install. */
    data class Ready(val manifest: UpdateManifest) : UpdateState
    data class Installing(val manifest: UpdateManifest) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * How this build updates itself, as the app and the UI see it. Each distribution flavour supplies
 * `createUpdater(context, store)`: the GitHub build's over-the-air updater (src/github), or the
 * Google Play build's stand-in, which is always [UpdateState.Disabled] and never goes online
 * (src/play), since Play installs the updates there.
 */
interface Updater {
    val enabled: Boolean

    /** Where updates come from, for Settings: "github.com/owner/repo". */
    val source: String
    val state: StateFlow<UpdateState>
    val lastChecked: StateFlow<Long>
    val promptDismissed: StateFlow<Boolean>

    /** Set by the activity: installing now would close the app under the user. */
    var foreground: Boolean

    /** Set while a test or an unsaved new profile is open: installing would throw it away. */
    var busy: Boolean

    /** Whether the user lets PureEQ install apps ("Install unknown apps"). */
    fun installAllowed(): Boolean

    /** Android 12+ lets an app that may install apps update itself without asking. */
    fun installsSilently(): Boolean

    fun dismissPrompt()

    /** App opened: check, unless a check has only just run. */
    fun checkSoon()

    fun checkNow()

    /** From the Install button or the "ready" notification; Android may show its confirmation. */
    fun installNow()

    /** The app went to the background. */
    fun onAppHidden()

    /** This app was just replaced, by an update or a manual install. */
    fun onPackageReplaced()

    companion object {
        /** The "update ready" notification opens the app with this action. */
        const val ACTION_INSTALL = "app.pureeq.INSTALL_UPDATE"
    }
}
