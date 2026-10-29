package app.pureeq.system

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import app.pureeq.MainActivity
import app.pureeq.PureEqApp
import app.pureeq.R

/**
 * Quick Settings tile: switches the system-wide EQ on and off from the swipe-down panel, the same
 * switch as "Apply to all audio" on the home screen. It shows the active profile's name, and opens
 * PureEQ when there is no profile to apply yet. Android binds it only while the panel is open, so
 * it costs nothing in the background.
 */
class EqTileService : TileService() {
    private val app get() = application as PureEqApp

    override fun onStartListening() = refresh()

    override fun onClick() {
        val store = app.store
        if (store.profiles.value.isEmpty()) {
            openApp()
            return
        }
        val on = !store.settings.value.systemEqEnabled
        store.updateSettings { it.copy(systemEqEnabled = on) }
        refresh()
        // Turning off needs nothing more: the running service sees the setting and stops itself.
        // Turning on from the background is refused on Android 12+; an invisible activity starts it instead.
        if (on && !startEq()) launch(Intent(this, TileTrampolineActivity::class.java))
    }

    private fun startEq(): Boolean = runCatching {
        ContextCompat.startForegroundService(this, Intent(this, EqService::class.java))
    }.isSuccess

    private fun refresh() {
        val tile = qsTile ?: return
        val store = app.store
        val profile = store.active ?: store.profiles.value.firstOrNull()
        val on = profile != null && store.settings.value.systemEqEnabled
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_eq)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                profile == null -> getString(R.string.tile_set_up)
                on -> profile.name
                else -> getString(R.string.tile_off)
            }
        }
        tile.updateTile()
    }

    private fun openApp() = launch(Intent(this, MainActivity::class.java))

    /** Starts [intent]'s activity from the panel and folds the panel away. */
    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, intent.component.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Whether Android can add the tile on request (13+); older versions add it by editing the panel. */
        val canRequestAdd: Boolean get() = Build.VERSION.SDK_INT >= 33

        /** Asks Android to add the tile to the panel; [done] gets true once it's there. */
        fun requestAdd(context: Context, done: (Boolean) -> Unit) {
            if (Build.VERSION.SDK_INT < 33) return done(false)
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, EqTileService::class.java),
                context.getString(R.string.tile_label),
                Icon.createWithResource(context, R.drawable.ic_stat_eq),
                context.mainExecutor,
            ) { result ->
                done(result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
            }
        }
    }
}
