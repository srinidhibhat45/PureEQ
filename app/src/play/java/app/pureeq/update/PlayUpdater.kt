package app.pureeq.update

import android.content.Context
import app.pureeq.profile.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Google Play build: Play installs the updates, so PureEQ never checks for, downloads or installs one. */
fun createUpdater(context: Context, store: ProfileStore): Updater = PlayUpdater

private object PlayUpdater : Updater {
    override val enabled = false
    override val source = ""
    override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Disabled)
    override val lastChecked: StateFlow<Long> = MutableStateFlow(0L)
    override val promptDismissed: StateFlow<Boolean> = MutableStateFlow(true)
    override var foreground = false
    override var busy = false

    override fun installAllowed() = false
    override fun installsSilently() = false
    override fun dismissPrompt() = Unit
    override fun checkSoon() = Unit
    override fun checkNow() = Unit
    override fun installNow() = Unit
    override fun onAppHidden() = Unit
    override fun onPackageReplaced() = Unit
}
