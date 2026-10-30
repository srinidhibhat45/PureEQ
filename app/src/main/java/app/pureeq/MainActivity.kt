package app.pureeq

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.pureeq.ui.AppViewModel
import app.pureeq.ui.PureEqRoot
import app.pureeq.ui.theme.PureEqTheme
import app.pureeq.update.Updater

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val updater get() = (application as PureEqApp).updater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Hardware volume keys always adjust media volume here, even between beeps.
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            PureEqTheme {
                PureEqRoot(vm, onExit = { finish() })
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Update ready" notification tapped, or Fine-tune chosen in the tile's long-press menu. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Updater.ACTION_INSTALL) updater.installNow()
        intent?.getStringExtra(EXTRA_FINE_TUNE)?.let { vm.openFineTune(it) }
    }

    override fun onStart() {
        super.onStart()
        updater.foreground = true
        updater.checkSoon()
        vm.onAppStarted()
    }

    override fun onStop() {
        vm.onAppStopped()
        updater.foreground = false
        updater.onAppHidden()
        super.onStop()
    }

    companion object {
        /** A profile id: open its Fine-tune screen. */
        const val EXTRA_FINE_TUNE = "app.pureeq.extra.FINE_TUNE"
    }
}
