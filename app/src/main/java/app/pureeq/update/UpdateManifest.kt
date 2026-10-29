package app.pureeq.update

import org.json.JSONObject
import java.net.URI

/**
 * The update notice (`update.json`) that ota/publish.py publishes with every release: which build
 * is newest, where its APK is, and how to tell that the download arrived intact.
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val minSdk: Int = 1,
    val notes: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("url", url)
        put("sha256", sha256)
        put("size", size)
        put("minSdk", minSdk)
        put("notes", notes)
    }

    companion object {
        const val MAX_APK_BYTES = 200L shl 20
        private val HEX64 = Regex("[0-9a-f]{64}")

        /**
         * Parses and checks a notice meant for [packageName]. Plain http is refused unless
         * [allowHttp] (debug builds pointed at a test server on this computer).
         */
        fun parse(text: String, packageName: String, allowHttp: Boolean = false): UpdateManifest {
            val o = JSONObject(text)
            val pkg = o.optString("package", packageName)
            require(pkg == packageName) { "The update notice is for another app ($pkg)." }
            val m = UpdateManifest(
                versionCode = o.getLong("versionCode"),
                versionName = o.getString("versionName"),
                url = o.getString("url"),
                sha256 = o.getString("sha256").lowercase(),
                size = o.getLong("size"),
                minSdk = o.optInt("minSdk", 1),
                notes = o.optString("notes", "").trim(),
            )
            require(m.versionCode > 0) { "The update notice has no version." }
            require(HEX64.matches(m.sha256)) { "The update notice has no valid checksum." }
            require(m.size in 1..MAX_APK_BYTES) { "The update notice gives an impossible size." }
            val scheme = runCatching { URI(m.url).scheme?.lowercase() }.getOrNull()
            require(scheme == "https" || (allowHttp && scheme == "http")) { "Updates must be downloaded over HTTPS." }
            return m
        }
    }
}
