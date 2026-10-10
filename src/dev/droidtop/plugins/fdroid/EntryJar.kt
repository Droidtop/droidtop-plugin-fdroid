package dev.droidtop.plugins.fdroid

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.jar.JarInputStream

/**
 * Verifies an index v2 `entry.jar` held in memory, with the same rules as F-Droid's
 * `org.fdroid.index.v2.EntryVerifier` / `JarIndexVerifier` (gitlab.com/fdroid/fdroidclient `libs/index`,
 * Apache-2.0): `entry.json` must be signed by exactly one signer with exactly one certificate of at least 256 bytes,
 * its manifest attributes may not use MD5 or SHA-1 digests, and the certificate must be the one already pinned for
 * the repository, or match the fingerprint a link named; with neither, it is trusted on first use and its
 * fingerprint is shown to the person before anything is added. Upstream's verifier opens a `java.io.File`, which a
 * contained plugin cannot do (it has no paths), so this reads a [JarInputStream], which verifies each entry as it is
 * read; a stream variant is the change to offer upstream.
 */
internal object EntryJar {
    class Verified(val certificateHex: String, val fingerprint: String, val entryJson: String)

    class Refused(message: String) : Exception(message)

    private val FORBIDDEN_DIGESTS = setOf("MD5-Digest", "SHA1-Digest")

    fun verify(bytes: ByteArray, expectedCertificate: String?, expectedFingerprint: String?): Verified {
        JarInputStream(ByteArrayInputStream(bytes), true).use { jar ->
            val manifest = jar.manifest ?: throw Refused("entry.jar has no manifest")
            while (true) {
                val entry = jar.nextJarEntry ?: break
                if (entry.name != "entry.json") continue
                val json = jar.readBytes()
                manifest.getAttributes("entry.json")?.keys?.forEach { key ->
                    if (key.toString() in FORBIDDEN_DIGESTS) throw Refused("entry.jar uses an unsupported digest ($key)")
                } ?: throw Refused("entry.json has no attributes in entry.jar's manifest")
                val signers = entry.codeSigners
                if (signers.isNullOrEmpty()) throw Refused("entry.jar is not signed")
                if (signers.size != 1) throw Refused("entry.jar must be signed by a single signer")
                val certs = signers[0].signerCertPath.certificates
                if (certs.size != 1) throw Refused("entry.jar's signer must have a single certificate")
                val cert = certs[0] as X509Certificate
                val certHex = hex(cert.encoded)
                if (certHex.length < 512) throw Refused("entry.jar's certificate is too short")
                val fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(cert.encoded))
                if (expectedCertificate != null && expectedCertificate != certHex) {
                    throw Refused("the repository is now signed by a different key than the one you added it with")
                }
                if (expectedFingerprint != null && expectedFingerprint != fingerprint) {
                    throw Refused("the repository's key does not match the fingerprint in the link")
                }
                return Verified(certHex, fingerprint, json.toString(Charsets.UTF_8))
            }
        }
        throw Refused("entry.jar has no entry.json")
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
