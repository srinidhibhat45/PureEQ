package app.pureeq.system

import android.app.Activity

/**
 * Starts the system EQ for the Quick Settings tile. Android 12+ only lets an app start its
 * foreground service from something the user has open, so turning the EQ on from the tile opens
 * this invisible activity, which starts the service and closes at once.
 */
class TileTrampolineActivity : Activity() {
    override fun onResume() {
        super.onResume()
        EqService.start(this)
        finish()
    }
}
