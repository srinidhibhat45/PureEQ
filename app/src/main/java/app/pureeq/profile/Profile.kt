package app.pureeq.profile

import app.pureeq.audio.ListeningLevel
import app.pureeq.dsp.Ear
import app.pureeq.dsp.FilterSpec
import app.pureeq.dsp.FilterType
import app.pureeq.dsp.FreqGrid
import app.pureeq.headphones.HeadphoneModel
import app.pureeq.hearing.HeadphoneType
import app.pureeq.hearing.HearingTestResult
import app.pureeq.hearing.ThresholdFlag
import app.pureeq.hearing.ThresholdPoint
import app.pureeq.hearing.TrialRecord
import app.pureeq.taste.TasteSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved sound profile: one person with one pair of headphones.
 * Only the measurements and choices are stored; every curve and filter is recomputed by [ProfileEq].
 */
data class Profile(
    val id: String,
    val name: String,
    val createdAt: Long,
    /** Output device the test was taken on (e.g. "Galaxy Buds2 Pro"), used for auto-switching. */
    val deviceName: String? = null,
    val deviceType: Int? = null,
    val hearing: HearingTestResult? = null,
    /** 0..1: how much of the measured difference to correct (see [HearingCompensation]). */
    val hearingStrength: Double = HearingCompensation.DEFAULT_STRENGTH,
    val taste: TasteSettings = TasteSettings(),
    /** Positive turns the left side down, negative the right (dB). */
    val balanceDb: Double = 0.0,
    /**
     * The taste test's result before any manual change, so Reset can go back to it. Null for
     * profiles saved by PureEQ 1.0, which didn't keep it.
     */
    val recommendedTaste: TasteSettings? = null,
    /** The headphones' measured correction from AutoEq, if the listener picked their model. */
    val headphoneModel: HeadphoneModel? = null,
    /** Whether [headphoneModel]'s correction is applied (it is by default). */
    val headphoneCorrection: Boolean = true,
    /** Over-ears: let bass below 50 Hz follow the settings fully instead of [BassGuard]'s limit. */
    val fullSubBass: Boolean = false,
    /** Media volume during the listening check. */
    val checkLevel: ListeningLevel? = null,
    /**
     * Media volume when the taste test ended: the level this profile's balance was chosen at, and
     * the reference for anything that follows the volume. Null when the test was skipped.
     */
    val tasteLevel: ListeningLevel? = null,
    /** Below the taste test's volume the system-wide EQ adds bass ([VolumeBass]); on by default. */
    val volumeBass: Boolean = true,
) {
    /** This profile with corrections, taste and balance as the tests recommended; name and results unchanged. */
    fun recommended(): Profile = copy(
        hearingStrength = if (hearing != null) HearingCompensation.DEFAULT_STRENGTH else hearingStrength,
        taste = recommendedTaste ?: taste,
        balanceDb = 0.0,
        headphoneCorrection = true,
        fullSubBass = false,
        volumeBass = true,
    )

    val isRecommended: Boolean get() = recommended() == this

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("fit", FIT_VERSION)
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        deviceName?.let { put("deviceName", it) }
        deviceType?.let { put("deviceType", it) }
        put("hearingStrength", hearingStrength)
        put("balanceDb", balanceDb)
        put("taste", taste.toJson())
        recommendedTaste?.let { put("recommendedTaste", it.toJson()) }
        headphoneModel?.let { put("headphoneModel", modelJson(it)) }
        put("headphoneCorrection", headphoneCorrection)
        if (fullSubBass) put("fullSubBass", true)
        if (!volumeBass) put("volumeBass", false)
        if (checkLevel != null || tasteLevel != null) {
            put("levels", JSONObject().apply {
                checkLevel?.let { put("check", levelJson(it)) }
                tasteLevel?.let { put("taste", levelJson(it)) }
            })
        }
        hearing?.let { h ->
            put("hearing", JSONObject().apply {
                put("catchTrials", h.catchTrials)
                put("falseAlarms", h.falseAlarms)
                h.retestDiffLeft?.let { put("retestDiffLeft", it) }
                h.retestDiffRight?.let { put("retestDiffRight", it) }
                put("trials", h.trials)
                put("durationSec", h.durationSec)
                put("warble", h.warble)
                put("calibrationDb", h.calibrationDb)
                h.headphones?.let { put("headphones", it.name) }
                put("inconsistent", h.inconsistent)
                // Compact: [Hz, 0 = left / 1 = right, dBFS, 1 = heard].
                put("trialLog", JSONArray().apply {
                    h.trialLog.forEach { t ->
                        put(JSONArray().apply {
                            put(t.freq)
                            put(if (t.ear == Ear.LEFT) 0 else 1)
                            put(t.levelDb)
                            put(if (t.heard) 1 else 0)
                            if (t.looks > 1) put(t.looks)
                        })
                    }
                })
                put("points", JSONArray().apply {
                    h.points.forEach { p ->
                        put(JSONObject().apply {
                            put("freq", p.freq)
                            put("ear", p.ear.name)
                            put("db", p.db)
                            put("sd", p.sd)
                            put("flag", p.flag.name)
                        })
                    }
                })
            })
        }
    }

    companion object {
        /**
         * 2 = PureEQ 1.2's Bayesian test and smooth fitting; 3 = 1.5's: anchored on 500 Hz-1.5 kHz,
         * a gentler slope beyond 6 dB, each ear's own level. Results refit from their trial logs.
         */
        const val FIT_VERSION = 3

        fun fromJson(o: JSONObject): Profile {
            val hearing = o.optJSONObject("hearing")?.let { h ->
                val pts = h.getJSONArray("points")
                HearingTestResult(
                    points = (0 until pts.length()).map { i ->
                        val p = pts.getJSONObject(i)
                        ThresholdPoint(
                            freq = p.getDouble("freq"),
                            ear = Ear.valueOf(p.getString("ear")),
                            db = p.getDouble("db"),
                            sd = p.optDouble("sd", 3.0),
                            flag = runCatching { ThresholdFlag.valueOf(p.getString("flag")) }.getOrDefault(ThresholdFlag.OK),
                        )
                    },
                    catchTrials = h.optInt("catchTrials"),
                    falseAlarms = h.optInt("falseAlarms"),
                    retestDiffLeft = if (h.has("retestDiffLeft")) h.getDouble("retestDiffLeft") else null,
                    retestDiffRight = if (h.has("retestDiffRight")) h.getDouble("retestDiffRight") else null,
                    trials = h.optInt("trials"),
                    durationSec = h.optInt("durationSec"),
                    warble = h.optBoolean("warble", true),
                    calibrationDb = h.optDouble("calibrationDb", -40.0),
                    trialLog = h.optJSONArray("trialLog")?.let { log ->
                        (0 until log.length()).map { i ->
                            val t = log.getJSONArray(i)
                            TrialRecord(t.getDouble(0), if (t.getInt(1) == 0) Ear.LEFT else Ear.RIGHT, t.getDouble(2), t.getInt(3) == 1, if (t.length() > 4) t.getInt(4) else 1)
                        }
                    } ?: emptyList(),
                    headphones = h.optString("headphones").let { n -> HeadphoneType.entries.firstOrNull { it.name == n } },
                    inconsistent = h.optInt("inconsistent"),
                )
            }
            // Before fit 2 (PureEQ 1.2) the default correction was 50 % of a noisier curve; the
            // untouched default moves to today's. A value the listener chose is kept.
            val strength = o.optDouble("hearingStrength", HearingCompensation.DEFAULT_STRENGTH)
                .let { if (o.optInt("fit", 1) < 2 && it == 0.5) HearingCompensation.DEFAULT_STRENGTH else it }
            return Profile(
                id = o.getString("id"),
                name = o.optString("name", "My sound"),
                createdAt = o.optLong("createdAt"),
                deviceName = if (o.has("deviceName")) o.getString("deviceName") else null,
                deviceType = if (o.has("deviceType")) o.getInt("deviceType") else null,
                hearing = hearing,
                hearingStrength = strength,
                taste = tasteFrom(o.optJSONObject("taste")),
                balanceDb = o.optDouble("balanceDb", 0.0),
                recommendedTaste = o.optJSONObject("recommendedTaste")?.let(::tasteFrom),
                headphoneModel = o.optJSONObject("headphoneModel")?.let(::modelFrom),
                headphoneCorrection = o.optBoolean("headphoneCorrection", true),
                fullSubBass = o.optBoolean("fullSubBass", false),
                checkLevel = o.optJSONObject("levels")?.optJSONObject("check")?.let(::levelFrom),
                tasteLevel = o.optJSONObject("levels")?.optJSONObject("taste")?.let(::levelFrom),
                volumeBass = o.optBoolean("volumeBass", true),
            )
        }

        /** A headphone model as stored in a profile (and in the per-device memory). */
        fun levelJson(l: ListeningLevel) = JSONObject().apply {
            put("index", l.index)
            put("max", l.maxIndex)
            l.db?.let { put("db", it) }
            put("type", l.deviceType)
        }

        fun levelFrom(o: JSONObject) = ListeningLevel(
            index = o.getInt("index"),
            maxIndex = o.getInt("max"),
            db = if (o.has("db")) o.getDouble("db") else null,
            deviceType = o.optInt("type"),
        )

        fun modelJson(m: HeadphoneModel) = JSONObject().apply {
            put("name", m.name)
            put("source", m.source)
            m.rig?.let { put("rig", it) }
            put("type", m.type.name)
            // Compact: [Equalizer APO type, Hz, dB, Q].
            put("filters", JSONArray().apply {
                m.filters.forEach { f -> put(JSONArray().apply { put(f.type.apoCode); put(f.freq); put(f.gainDb); put(f.q) }) }
            })
            m.path?.let { put("path", it) }
            m.target?.let { put("target", it) }
            m.curveDb?.let { c -> put("curve", JSONArray().apply { c.forEach { put(it) } }) }
            m.bassDb?.let { put("bassDb", it) }
        }

        fun modelFrom(m: JSONObject): HeadphoneModel {
            val fs = m.getJSONArray("filters")
            return HeadphoneModel(
                name = m.getString("name"),
                source = m.getString("source"),
                rig = if (m.has("rig")) m.getString("rig") else null,
                type = HeadphoneType.entries.firstOrNull { it.name == m.optString("type") } ?: HeadphoneType.IN_EAR,
                filters = (0 until fs.length()).map { i ->
                    val f = fs.getJSONArray(i)
                    FilterSpec(FilterType.entries.first { it.apoCode == f.getString(0) }, f.getDouble(1), f.getDouble(2), f.getDouble(3))
                },
                path = if (m.has("path")) m.getString("path") else null,
                target = if (m.has("target")) m.getString("target") else null,
                // A curve on another frequency grid would be misread, so it's dropped (the filters still apply).
                curveDb = m.optJSONArray("curve")?.takeIf { it.length() == FreqGrid.SIZE }?.let { c -> List(c.length()) { c.getDouble(it) } },
                bassDb = if (m.has("bassDb")) m.getDouble("bassDb") else null,
            )
        }

        private fun TasteSettings.toJson() = JSONObject().apply {
            put("bass", bass)
            put("warmth", warmth)
            put("vocals", vocals)
            put("treble", treble)
            if (air != 0.0) put("air", air)
            // 2 = the broad 2.5 kHz treble shelf of PureEQ 1.5; older profiles keep their 8 kHz one.
            put("shelf", if (legacyTreble) 1 else 2)
        }

        private fun tasteFrom(t: JSONObject?) = TasteSettings(
            bass = t?.optDouble("bass", 0.0) ?: 0.0,
            warmth = t?.optDouble("warmth", 0.0) ?: 0.0,
            vocals = t?.optDouble("vocals", 0.0) ?: 0.0,
            treble = t?.optDouble("treble", 0.0) ?: 0.0,
            air = t?.optDouble("air", 0.0) ?: 0.0,
            legacyTreble = t != null && t.optInt("shelf", 1) < 2,
        )
    }
}
