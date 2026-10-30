package app.pureeq.profile

import android.content.Context
import app.pureeq.headphones.HeadphoneModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

enum class SystemEqMode(val label: String, val detail: String) {
    GLOBAL("All audio", "Attaches to Android's output mix, so every app is covered. Works on most phones."),
    PER_APP("Music apps", "Attaches to players that announce themselves (Spotify, YouTube Music, Samsung Music, Poweramp…). Use this if \"All audio\" has no effect on your phone."),
}

data class AppSettings(
    val systemEqEnabled: Boolean = false,
    val mode: SystemEqMode = SystemEqMode.GLOBAL,
    /** Switch to the profile made with the headphones that just connected. */
    val autoSwitch: Boolean = true,
    val startOnBoot: Boolean = true,
    /** Warble (±5 %) test tones; pure tones when off. */
    val warbleTones: Boolean = true,
    /** Install downloaded updates by themselves while the app is closed (Android 12+). */
    val autoUpdate: Boolean = true,
)

/** Profiles and settings, persisted as JSON in SharedPreferences. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("pureeq", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun profile(id: String?): Profile? = _profiles.value.firstOrNull { it.id == id }

    val active: Profile? get() = profile(_activeId.value)

    @Synchronized
    fun upsert(p: Profile) {
        _profiles.update { list ->
            val i = list.indexOfFirst { it.id == p.id }
            if (i >= 0) list.toMutableList().also { it[i] = p } else list + p
        }
        saveProfiles()
    }

    /** Deletes a profile. If it was the active one, the newest remaining profile takes over (none if it was the last). */
    @Synchronized
    fun delete(id: String) {
        val next = successorOf(id)
        _profiles.update { list -> list.filter { it.id != id } }
        if (_activeId.value == id) setActive(next?.id)
        saveProfiles()
    }

    /** The profile that becomes active when [id] is deleted while active: the newest of the others. */
    fun successorOf(id: String): Profile? = _profiles.value.filter { it.id != id }.maxByOrNull { it.createdAt }

    fun setActive(id: String?) {
        _activeId.value = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    /** The headphone model last picked for an output device, so the next profile for it starts with it. */
    fun rememberedModel(device: String): HeadphoneModel? = runCatching { models().optJSONObject(device)?.let(Profile::modelFrom) }.getOrNull()

    /** Remembers [model] for [device]; null forgets the device's model. */
    @Synchronized
    fun rememberModel(device: String, model: HeadphoneModel?) {
        val all = models()
        if (model == null) all.remove(device) else all.put(device, Profile.modelJson(model))
        prefs.edit().putString(KEY_MODELS, all.toString()).apply()
    }

    private fun models(): JSONObject = runCatching { JSONObject(prefs.getString(KEY_MODELS, null) ?: "{}") }.getOrDefault(JSONObject())

    @Synchronized
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        val s = _settings.value
        prefs.edit()
            .putBoolean("systemEqEnabled", s.systemEqEnabled)
            .putString("mode", s.mode.name)
            .putBoolean("autoSwitch", s.autoSwitch)
            .putBoolean("startOnBoot", s.startOnBoot)
            .putBoolean("warbleTones", s.warbleTones)
            .putBoolean("autoUpdate", s.autoUpdate)
            .apply()
    }

    private fun loadSettings() = AppSettings(
        systemEqEnabled = prefs.getBoolean("systemEqEnabled", false),
        mode = runCatching { SystemEqMode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(SystemEqMode.GLOBAL),
        autoSwitch = prefs.getBoolean("autoSwitch", true),
        startOnBoot = prefs.getBoolean("startOnBoot", true),
        warbleTones = prefs.getBoolean("warbleTones", true),
        autoUpdate = prefs.getBoolean("autoUpdate", true),
    )

    private fun loadProfiles(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> runCatching { Profile.fromJson(arr.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun saveProfiles() {
        val arr = JSONArray()
        _profiles.value.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active"
        private const val KEY_MODELS = "headphoneModels"

        /** Parses a profile exported with "PureEQ profile (.json)"; gives it a fresh id. */
        fun importJson(text: String, newId: String): Profile = Profile.fromJson(JSONObject(text)).copy(id = newId)
    }
}
