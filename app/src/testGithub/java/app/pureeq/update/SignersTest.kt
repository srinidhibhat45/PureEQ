package app.pureeq.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignersTest {

    private val debug = "d0".repeat(32)
    private val release = "a1".repeat(32)
    private val release2 = "b2".repeat(32)
    private val stranger = "c3".repeat(32)

    /** One signer: the last certificate, with [certs] as its rotation history, oldest first. */
    private fun signedBy(vararg certs: String) = Signers(setOf(certs.last()), certs.toList())

    private fun multipleSigners(vararg certs: String) = Signers(certs.toSet(), emptyList())

    @Test
    fun acceptsTheSameKey() {
        assertTrue(signedBy(debug).accepts(signedBy(debug)))
    }

    @Test
    fun refusesAnotherKey() {
        assertFalse(signedBy(debug).accepts(signedBy(release)))
    }

    @Test
    fun acceptsTheReleaseKeyWhenItsLineageStartsAtTheDebugKey() {
        assertTrue(signedBy(debug).accepts(signedBy(debug, release)))
    }

    @Test
    fun keepsAcceptingUpdatesAfterTheRotation() {
        val rotated = signedBy(debug, release)
        assertTrue(rotated.accepts(signedBy(debug, release)))
        assertTrue(rotated.accepts(signedBy(release)))
        assertTrue(rotated.accepts(signedBy(debug, release, release2)))
        assertTrue(rotated.accepts(signedBy(release, release2)))
    }

    @Test
    fun refusesGoingBackToAnOlderKey() {
        assertFalse(signedBy(debug, release).accepts(signedBy(debug)))
        assertFalse(signedBy(debug, release, release2).accepts(signedBy(debug, release)))
    }

    @Test
    fun refusesALineageWithoutThisCopysKey() {
        assertFalse(signedBy(debug).accepts(signedBy(stranger, release)))
        assertFalse(signedBy(debug, release).accepts(signedBy(debug, stranger)))
    }

    @Test
    fun multipleSignersMustMatchExactly() {
        assertTrue(multipleSigners(debug, release).accepts(multipleSigners(release, debug)))
        assertFalse(multipleSigners(debug, release).accepts(multipleSigners(debug)))
        assertFalse(multipleSigners(debug, release).accepts(signedBy(debug, stranger)))
        assertFalse(signedBy(debug).accepts(multipleSigners(debug, release)))
    }

    @Test
    fun refusesWhenACertificateIsMissing() {
        val none = Signers(emptySet(), emptyList())
        assertFalse(none.accepts(signedBy(debug)))
        assertFalse(signedBy(debug).accepts(none))
        assertFalse(none.accepts(none))
    }
}
