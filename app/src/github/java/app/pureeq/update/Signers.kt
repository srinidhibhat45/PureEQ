package app.pureeq.update

/**
 * Who signed an APK, as lowercase hex SHA-256 digests of its signing certificates (from Android's
 * SigningInfo). [current] signs the contents: one certificate, or several for an APK with multiple
 * signers. [history] is the APK Signature Scheme v3 proof of key rotation, oldest first and ending
 * with the current certificate (just that one when the key never changed); empty for multiple
 * signers, which can't rotate.
 */
data class Signers(val current: Set<String>, val history: List<String>) {

    /**
     * Whether Android lets an APK signed by [update] replace a copy signed by these: the same
     * certificates, or, after a key rotation, a single-signer update whose history contains this
     * copy's one certificate. A key from this copy's own past doesn't count: that would be a
     * rollback, which Android refuses unless the lineage explicitly allows it.
     */
    fun accepts(update: Signers): Boolean = when {
        current.isEmpty() || update.current.isEmpty() -> false
        update.current == current -> true
        else -> current.size == 1 && update.current.size == 1 && current.single() in update.history
    }
}
