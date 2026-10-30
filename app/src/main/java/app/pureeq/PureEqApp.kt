package app.pureeq

import android.app.Application
import app.pureeq.audio.AudioEngine
import app.pureeq.audio.AudioRoute
import app.pureeq.audio.MusicLibrary
import app.pureeq.profile.ProfileStore
import app.pureeq.update.Updater
import app.pureeq.update.createUpdater

/** Process-wide singletons shared by the UI and the equalizer service. */
class PureEqApp : Application() {
    lateinit var store: ProfileStore
        private set
    lateinit var engine: AudioEngine
        private set
    lateinit var route: AudioRoute
        private set
    lateinit var library: MusicLibrary
        private set
    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        store = ProfileStore(this)
        engine = AudioEngine(this)
        route = AudioRoute(this)
        library = MusicLibrary(this)
        // GitHub build: the over-the-air updater and its background checks. Play build: never updates itself.
        updater = createUpdater(this, store)
    }
}
