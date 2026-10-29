package app.pureeq.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestTest {

    private val sha = "3f".repeat(32)

    /** What ota/publish.py writes. */
    private fun notice(
        pkg: String = "app.pureeq",
        url: String = "https://github.com/someone/pureeq-updates/releases/download/v1.1.1/PureEQ-1.1.1.apk",
        sha256: String = sha,
        size: Long = 2_700_000,
    ) = """
        {
          "package": "$pkg",
          "versionCode": 3,
          "versionName": "1.1.1",
          "url": "$url",
          "sha256": "$sha256",
          "size": $size,
          "minSdk": 28,
          "notes": "- Reset button\n- Faster test",
          "published": "2026-10-29T12:00:00Z"
        }
    """.trimIndent()

    @Test
    fun readsWhatThePublishScriptWrites() {
        val m = UpdateManifest.parse(notice(sha256 = sha.uppercase()), "app.pureeq")
        assertEquals(3L, m.versionCode)
        assertEquals("1.1.1", m.versionName)
        assertEquals(sha, m.sha256)
        assertEquals(2_700_000L, m.size)
        assertEquals(28, m.minSdk)
        assertEquals("- Reset button\n- Faster test", m.notes)
    }

    @Test
    fun survivesBeingStoredAndReadBack() {
        val m = UpdateManifest.parse(notice(), "app.pureeq")
        assertEquals(m, UpdateManifest.parse(m.toJson().toString(), "app.pureeq"))
    }

    @Test
    fun refusesAnotherAppsUpdate() {
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(notice(pkg = "com.example"), "app.pureeq") }
    }

    @Test
    fun refusesPlainHttpOutsideDebugBuilds() {
        val http = notice(url = "http://10.0.2.2:8765/PureEQ-1.1.1.apk")
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(http, "app.pureeq") }
        assertEquals("http://10.0.2.2:8765/PureEQ-1.1.1.apk", UpdateManifest.parse(http, "app.pureeq", allowHttp = true).url)
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(notice(url = "file:///sdcard/x.apk"), "app.pureeq", allowHttp = true) }
    }

    @Test
    fun refusesMissingChecksumsAndImpossibleSizes() {
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(notice(sha256 = "abc"), "app.pureeq") }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(notice(size = 0), "app.pureeq") }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(notice(size = UpdateManifest.MAX_APK_BYTES + 1), "app.pureeq") }
    }
}
