package app.pureeq.system

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.pureeq.MainActivity
import app.pureeq.PureEqApp
import app.pureeq.ui.screens.TileMenuSheet
import app.pureeq.ui.theme.PureEqTheme

/**
 * Long-press on the Quick Settings tile. Android can't show an app's own menu inside the panel,
 * but it opens the activity that handles QS_TILE_PREFERENCES (without one, App info). This one is
 * a sheet over whatever is on screen: switch the system-wide EQ, pick the profile (the headphones)
 * it uses, or go to Fine-tune or the app.
 */
class TileMenuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PureEqApp
        setContent {
            PureEqTheme {
                TileMenuSheet(
                    app,
                    onFineTune = { id -> open(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FINE_TUNE, id)) },
                    onOpenApp = { open(Intent(this, MainActivity::class.java)) },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun open(intent: Intent) {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
