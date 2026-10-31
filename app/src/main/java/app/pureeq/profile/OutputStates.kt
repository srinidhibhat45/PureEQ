package app.pureeq.profile

import app.pureeq.audio.OutputDevice
import org.json.JSONObject

/** PureEQ on one output: on or off, the profile it last played there, and the output's type (for its icon). */
data class OutputPref(val on: Boolean, val profileId: String? = null, val type: Int = 0)

/**
 * Where the system-wide EQ is on. A profile is tuned for one pair of headphones; on the phone
 * speaker it only makes the sound quieter and duller. So PureEQ keeps on or off for each output
 * instead of one switch for all (the phone speaker, each pair of headphones, a car or a Bluetooth
 * speaker), with the profile it last played there. Outputs are told apart by name, like
 * [Profile.deviceName].
 *
 * An output PureEQ hasn't played on starts off, the phone speaker included. The exception is
 * headphones a profile was made with: they start on while PureEQ is on for another output.
 */
data class OutputStates(val byKey: Map<String, OutputPref> = emptyMap()) {

    /** On for some output: the service runs, and waits while another output plays. */
    val armed: Boolean get() = byKey.values.any { it.on }

    fun isOn(out: OutputDevice, profiles: List<Profile>): Boolean =
        byKey[keyOf(out)]?.on ?: (armed && profiles.any { madeWith(it, out) })

    fun turned(out: OutputDevice, on: Boolean): OutputStates = put(out) { it.copy(on = on) }

    /** [out] played with [profileId] and PureEQ on: what it comes back to there. */
    fun used(out: OutputDevice, profileId: String): OutputStates = put(out) { it.copy(on = true, profileId = profileId) }

    fun allOff(): OutputStates = OutputStates(byKey.mapValues { it.value.copy(on = false) })

    /** Forgets a deleted profile; outputs that used it go back to the profile made with them. */
    fun without(profileId: String): OutputStates =
        OutputStates(byKey.mapValues { if (it.value.profileId == profileId) it.value.copy(profileId = null) else it.value })

    /**
     * The profile for [out] when it starts playing: the one last used there, else the active one if
     * it was made with these headphones (you may keep several for one pair), else the first made
     * with them. Null keeps the active profile.
     */
    fun profileFor(out: OutputDevice, profiles: List<Profile>, activeId: String?): Profile? {
        byKey[keyOf(out)]?.profileId?.let { id -> profiles.firstOrNull { it.id == id }?.let { return it } }
        profiles.firstOrNull { it.id == activeId && madeWith(it, out) }?.let { return it }
        return profiles.firstOrNull { madeWith(it, out) }
    }

    /** The other outputs PureEQ is on for, by name ([SPEAKER] for the phone speaker). */
    fun onElsewhere(out: OutputDevice, profiles: List<Profile>): List<String> {
        val here = keyOf(out)
        val remembered = byKey.filter { it.value.on && it.key != here }.keys
        val madeWith = if (armed) profiles.mapNotNull { it.deviceName }.filter { it != here && it != SPEAKER && it !in byKey } else emptyList()
        return (remembered + madeWith).distinct()
    }

    /**
     * Every output worth listing: [current] first, then the phone speaker, outputs PureEQ has
     * played on, and headphones a profile was made with.
     */
    fun known(profiles: List<Profile>, current: OutputDevice): List<OutputDevice> {
        val all = LinkedHashMap<String, OutputDevice>()
        fun add(d: OutputDevice) = all.putIfAbsent(keyOf(d), d)
        add(current)
        add(OutputDevice(SPEAKER, TYPE_BUILTIN_SPEAKER))
        byKey.forEach { (k, v) -> add(device(k, v.type)) }
        profiles.forEach { p -> p.deviceName?.let { add(device(it, p.deviceType)) } }
        return all.values.toList()
    }

    private fun put(out: OutputDevice, change: (OutputPref) -> OutputPref): OutputStates {
        val key = keyOf(out)
        val pref = change(byKey[key] ?: OutputPref(on = false)).copy(type = out.type)
        return if (byKey[key] == pref) this else OutputStates(byKey + (key to pref))
    }

    fun toJson(): JSONObject = JSONObject().apply {
        byKey.forEach { (k, v) ->
            put(k, JSONObject().apply {
                put("on", v.on)
                v.profileId?.let { put("profile", it) }
                if (v.type != 0) put("type", v.type)
            })
        }
    }

    companion object {
        const val SPEAKER = OutputDevice.SPEAKER_NAME

        // AudioDeviceInfo.TYPE_BUILTIN_SPEAKER and TYPE_BLUETOOTH_A2DP.
        private const val TYPE_BUILTIN_SPEAKER = 2
        private const val TYPE_BLUETOOTH_A2DP = 8

        /** One key for the phone's own speaker (and earpiece) whatever it reports as its name. */
        fun keyOf(out: OutputDevice): String = if (out.kind == OutputDevice.Kind.SPEAKER) SPEAKER else out.name

        /**
         * The output called [name], with [type] unless that's unknown or would make headphones the
         * phone speaker (a profile keeps the type it was made on, whatever its name).
         */
        private fun device(name: String, type: Int?): OutputDevice {
            if (name == SPEAKER) return OutputDevice(SPEAKER, TYPE_BUILTIN_SPEAKER)
            val d = OutputDevice(name, type ?: 0)
            return if (type == null || type == 0 || d.kind == OutputDevice.Kind.SPEAKER) OutputDevice(name, TYPE_BLUETOOTH_A2DP) else d
        }

        fun madeWith(p: Profile, out: OutputDevice): Boolean = out.isHeadphones && p.deviceName != null && p.deviceName == out.name

        fun fromJson(o: JSONObject): OutputStates = OutputStates(
            o.keys().asSequence().mapNotNull { k ->
                o.optJSONObject(k)?.let { v ->
                    k to OutputPref(v.optBoolean("on"), if (v.has("profile")) v.getString("profile") else null, v.optInt("type"))
                }
            }.toMap(),
        )

        /**
         * PureEQ 1.5 and earlier had one switch for every output. If it was on, it stays on for each
         * pair of headphones a profile was made with; everything else, the phone speaker too, starts off.
         */
        fun migrated(wasOn: Boolean, profiles: List<Profile>): OutputStates {
            if (!wasOn) return OutputStates()
            return OutputStates(
                profiles.filter { it.deviceName != null && it.deviceName != SPEAKER }
                    .associate { it.deviceName!! to OutputPref(on = true, type = device(it.deviceName!!, it.deviceType).type) },
            )
        }
    }
}
