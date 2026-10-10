package dev.droidtop.plugins.fdroid

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.jar.Manifest
import java.util.zip.ZipInputStream

/**
 * Verifies an index v2 `entry.jar` held in memory, with the same rules as F-Droid's
 * `org.fdroid.index.v2.EntryVerifier` / `JarIndexVerifier` (gitlab.com/fdroid/fdroidclient `libs/index`,
 * Apache-2.0): `entry.json` must be signed by exactly one signer with exactly one certificate of at least 256 bytes,
 * its manifest attributes may not use MD5 or SHA-1 digests, and the certificate must be the one already pinned for
 * the repository, or match the fingerprint a link named; with neither, it is trusted on first use and its
 * fingerprint is shown to the person before anything is added.
 *
 * Upstream's verifier opens a `java.io.File` with `java.util.jar.JarFile`, which a contained plugin cannot do (it
 * has no paths). `JarInputStream` is no substitute: it exposes the manifest only when META-INF/MANIFEST.MF is the
 * jar's FIRST entry, and fdroidserver writes it last (the real entry.jar of F-Droid and IzzyOnDroid both do, which
 * is why every review said "entry.jar has no manifest", Droidtop/tracker#478), and Android's does not check
 * signatures at all. So the jar is unpacked with [ZipInputStream] and checked here, the way the JDK's JarVerifier
 * does it, with nothing but classes Android and the JVM share:
 *  1. `entry.json`'s bytes match a strong digest in its manifest section;
 *  2. the signature file (META-INF/X.SF) vouches for that manifest, either as a whole or for that section;
 *  3. the signature block (META-INF/X.RSA, .DSA or .EC, PKCS#7) is the signer's signature over the signature file.
 */
internal object EntryJar {
    class Verified(val certificateHex: String, val fingerprint: String, val entryJson: String)

    class Refused(message: String) : Exception(message)

    private val FORBIDDEN_DIGESTS = setOf("MD5-Digest", "SHA1-Digest")
    private const val MAX_UNPACKED = 16 * 1024 * 1024

    fun verify(bytes: ByteArray, expectedCertificate: String?, expectedFingerprint: String?): Verified {
        val files = unpack(bytes)
        val manifestBytes = files["META-INF/MANIFEST.MF"] ?: throw Refused("entry.jar has no manifest")
        val json = files["entry.json"] ?: throw Refused("entry.jar has no entry.json")

        // 1. entry.json against its manifest section.
        val manifest = try { Manifest(ByteArrayInputStream(manifestBytes)) } catch (e: Exception) { throw Refused("entry.jar's manifest is unreadable") }
        val attributes = manifest.getAttributes("entry.json") ?: throw Refused("entry.json has no attributes in entry.jar's manifest")
        var checked = 0
        for (key in attributes.keys) {
            val name = key.toString()
            if (name in FORBIDDEN_DIGESTS) throw Refused("entry.jar uses an unsupported digest ($name)")
            val algorithm = strongDigest(name.removeSuffix("-Digest").takeIf { name.endsWith("-Digest") } ?: continue) ?: continue
            val want = attributes.getValue(name)?.trim().orEmpty()
            if (want != Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(json))) {
                throw Refused("entry.json does not match the digest in entry.jar's manifest")
            }
            checked++
        }
        if (checked == 0) throw Refused("entry.json has no SHA-256 digest in entry.jar's manifest")

        // 2. the signature file vouches for the manifest.
        val signatureFiles = files.keys.filter { it.startsWith("META-INF/") && it.count { c -> c == '/' } == 1 && it.endsWith(".SF", ignoreCase = true) }
        if (signatureFiles.isEmpty()) throw Refused("entry.jar is not signed")
        if (signatureFiles.size != 1) throw Refused("entry.jar must be signed by a single signer")
        val sfName = signatureFiles[0]
        val sf = files.getValue(sfName)
        val base = sfName.dropLast(3)
        val blockName = listOf(".RSA", ".DSA", ".EC").map { base + it }.firstOrNull { files.containsKey(it) }
            ?: throw Refused("entry.jar's signature file has no signature block")
        if (!signatureFileVouches(sf, manifestBytes)) throw Refused("entry.jar's signature file does not match its manifest")

        // 3. the signer signed the signature file.
        val cert = signerOf(files.getValue(blockName), sf)
        val certBytes = cert.encoded
        if (certBytes.size < 256) throw Refused("entry.jar's certificate is too short")
        val certHex = hex(certBytes)
        val fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(certBytes))
        if (expectedCertificate != null && expectedCertificate != certHex) {
            throw Refused("the repository is now signed by a different key than the one you added it with")
        }
        if (expectedFingerprint != null && expectedFingerprint != fingerprint) {
            throw Refused("the repository's key does not match the fingerprint in the link")
        }
        return Verified(certHex, fingerprint, json.toString(Charsets.UTF_8))
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------
    // The zip.
    // ------------------------------------------------------------------

    private fun unpack(bytes: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNPACKED) throw Refused("entry.jar is too large when unpacked")
                        out.write(buffer, 0, n)
                    }
                    files[entry.name] = out.toByteArray()
                }
            }
        } catch (e: Refused) {
            throw e
        } catch (e: Exception) {
            throw Refused("entry.jar is not a readable zip file (${describe(bytes)})")
        }
        if (files.isEmpty()) throw Refused("entry.jar is not a zip file (${describe(bytes)})")
        return files
    }

    /** What arrived, for a refusal that is not about the jar's contents: an HTML error page looks like this. */
    private fun describe(bytes: ByteArray) =
        "${bytes.size} bytes, starting " + bytes.take(8).joinToString(" ") { "%02x".format(it) }

    // ------------------------------------------------------------------
    // The signature file.
    // ------------------------------------------------------------------

    private fun strongDigest(name: String): String? = when (name.uppercase()) {
        "SHA-256", "SHA256" -> "SHA-256"
        "SHA-384", "SHA384" -> "SHA-384"
        "SHA-512", "SHA512" -> "SHA-512"
        else -> null
    }

    /** The digests a signature file may use: upstream's rule only forbids MD5 and SHA-1 for the manifest's own entries. */
    private fun sfDigest(name: String): String? = strongDigest(name) ?: if (name.uppercase() in setOf("SHA-1", "SHA1", "SHA")) "SHA-1" else null

    private fun signatureFileVouches(sf: ByteArray, manifest: ByteArray): Boolean {
        val parsed = try { Manifest(ByteArrayInputStream(sf)) } catch (e: Exception) { return false }
        // The whole manifest.
        for (key in parsed.mainAttributes.keys) {
            val name = key.toString()
            if (!name.endsWith("-Digest-Manifest", ignoreCase = true)) continue
            val algorithm = sfDigest(name.dropLast("-Digest-Manifest".length)) ?: continue
            if (parsed.mainAttributes.getValue(name)?.trim() == Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(manifest))) return true
        }
        // Or the one section entry.json is in.
        val section = sections(manifest)["entry.json"] ?: return false
        val attributes = parsed.getAttributes("entry.json") ?: return false
        for (key in attributes.keys) {
            val name = key.toString()
            if (!name.endsWith("-Digest", ignoreCase = true)) continue
            val algorithm = sfDigest(name.dropLast("-Digest".length)) ?: continue
            if (attributes.getValue(name)?.trim() == Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(section))) return true
        }
        return false
    }

    /**
     * The manifest's named sections as raw bytes, each with its blank line: what the signature file's per-entry
     * digests cover (java.util.jar.Manifest keeps these bytes private).
     */
    private fun sections(manifest: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        var start = 0
        var i = 0
        fun endOfLine(from: Int): Int {
            var j = from
            while (j < manifest.size && manifest[j] != '\n'.code.toByte() && manifest[j] != '\r'.code.toByte()) j++
            if (j < manifest.size) {
                if (manifest[j] == '\r'.code.toByte() && j + 1 < manifest.size && manifest[j + 1] == '\n'.code.toByte()) j++
                j++
            }
            return j
        }
        while (i < manifest.size) {
            val next = endOfLine(i)
            val blank = next - i <= 2 && manifest[i].let { it == '\n'.code.toByte() || it == '\r'.code.toByte() }
            i = next
            if (blank || i >= manifest.size) {
                val section = manifest.copyOfRange(start, i)
                val text = String(section, Charsets.UTF_8).replace("\r\n ", "").replace("\n ", "").replace("\r ", "")
                val name = text.lineSequence().firstOrNull { it.startsWith("Name: ") }?.removePrefix("Name: ")?.trim()
                if (name != null) result[name] = section
                start = i
            }
        }
        return result
    }

    // ------------------------------------------------------------------
    // The signature block: a PKCS#7 SignedData with one signer.
    // ------------------------------------------------------------------

    private fun signerOf(block: ByteArray, signedFile: ByteArray): X509Certificate {
        try {
            val top = Der(block).sequence()
            top.next()
            val signedData = Der(top.next().value()).sequence()
            signedData.next() // version
            signedData.next() // digest algorithms
            signedData.next() // the content, absent for a detached signature
            var certificates: Der.Tlv? = null
            var signerInfos: Der.Tlv? = null
            while (signedData.more()) {
                val tlv = signedData.next()
                when (tlv.tag) {
                    0xA0 -> certificates = tlv
                    0x31 -> signerInfos = tlv
                }
            }
            if (certificates == null || signerInfos == null) throw Refused("entry.jar's signature block has no certificate or no signer")
            val factory = CertificateFactory.getInstance("X.509")
            val certList = Der(certificates.value()).let { d -> buildList { while (d.more()) add(d.next()) } }
            val certs = certList.map { factory.generateCertificate(ByteArrayInputStream(it.raw())) as X509Certificate }
            if (certs.size != 1) throw Refused("entry.jar's signer must have a single certificate")

            val signers = Der(signerInfos.value()).let { d -> buildList { while (d.more()) add(d.next()) } }
            if (signers.size != 1) throw Refused("entry.jar must be signed by a single signer")
            val info = Der(signers[0].value())
            info.next() // version
            val issuerAndSerial = Der(info.next().value())
            val issuer = issuerAndSerial.next().raw()
            val serial = BigInteger(issuerAndSerial.next().value())
            val digestOid = oid(Der(info.next().value()).next().value())
            var attributes: Der.Tlv? = null
            var tlv = info.next()
            if (tlv.tag == 0xA0) {
                attributes = tlv
                tlv = info.next()
            }
            val signatureOid = oid(Der(tlv.value()).next().value())
            val signature = info.next().value()

            val cert = certs[0]
            if (!cert.issuerX500Principal.encoded.contentEquals(issuer) || cert.serialNumber != serial) {
                throw Refused("entry.jar's signer is not the certificate in its signature block")
            }
            val digest = when (digestOid) {
                "1.3.14.3.2.26" -> "SHA-1"
                "2.16.840.1.101.3.4.2.1" -> "SHA-256"
                "2.16.840.1.101.3.4.2.2" -> "SHA-384"
                "2.16.840.1.101.3.4.2.3" -> "SHA-512"
                else -> throw Refused("entry.jar's signature uses an unsupported digest ($digestOid)")
            }
            if (signatureOid == "1.2.840.113549.1.1.10") throw Refused("entry.jar's signature uses RSASSA-PSS, which is not supported")
            val signed = if (attributes == null) {
                signedFile
            } else {
                // The signature covers the attributes, re-tagged as a SET; one of them is the digest of the file.
                var messageDigest: ByteArray? = null
                val list = Der(attributes.value())
                while (list.more()) {
                    val attribute = Der(list.next().value())
                    if (oid(attribute.next().value()) == "1.2.840.113549.1.9.4") {
                        messageDigest = Der(attribute.next().value()).next().value()
                    }
                }
                if (messageDigest == null || !messageDigest.contentEquals(MessageDigest.getInstance(digest).digest(signedFile))) {
                    throw Refused("entry.jar's signature does not match its signature file")
                }
                attributes.raw().also { it[0] = 0x31 }
            }
            val keyKind = when (cert.publicKey.algorithm.uppercase()) {
                "EC", "ECDSA" -> "ECDSA"
                "DSA" -> "DSA"
                else -> "RSA"
            }
            val verifier = Signature.getInstance("${digest.replace("-", "")}with$keyKind")
            verifier.initVerify(cert.publicKey)
            verifier.update(signed)
            if (!verifier.verify(signature)) throw Refused("entry.jar's signature is not valid")
            return cert
        } catch (e: Refused) {
            throw e
        } catch (e: Exception) {
            throw Refused("entry.jar's signature block is unreadable (${e.javaClass.simpleName})")
        }
    }

    private fun oid(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val parts = ArrayList<String>()
        parts += (bytes[0].toInt() and 0xFF).let { "${it / 40}.${it % 40}" }
        var value = 0L
        for (i in 1 until bytes.size) {
            value = (value shl 7) or (bytes[i].toLong() and 0x7F)
            if (bytes[i].toInt() and 0x80 == 0) {
                parts += value.toString()
                value = 0
            }
        }
        return parts.joinToString(".")
    }

    /** The few DER shapes PKCS#7 needs: definite lengths only, which is what jarsigner and apksigner write. */
    private class Der(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
        inner class Tlv(val tag: Int, val start: Int, val valueStart: Int, val end: Int) {
            fun value(): ByteArray = data.copyOfRange(valueStart, end)
            fun raw(): ByteArray = data.copyOfRange(start, end)
        }

        fun more() = pos < end

        fun next(): Tlv {
            val start = pos
            if (pos + 2 > end) throw IllegalArgumentException("truncated")
            val tag = data[pos++].toInt() and 0xFF
            var length = data[pos++].toInt() and 0xFF
            if (length and 0x80 != 0) {
                val count = length and 0x7F
                if (count == 0 || count > 4 || pos + count > end) throw IllegalArgumentException("unsupported length")
                length = 0
                repeat(count) { length = (length shl 8) or (data[pos++].toInt() and 0xFF) }
            }
            if (length < 0 || pos + length > end) throw IllegalArgumentException("truncated")
            val tlv = Tlv(tag, start, pos, pos + length)
            pos += length
            return tlv
        }

        /** This stream's one outer SEQUENCE, read as a stream of its own. */
        fun sequence(): Der {
            val tlv = next()
            if (tlv.tag != 0x30) throw IllegalArgumentException("not a sequence")
            return Der(data, tlv.valueStart, tlv.end)
        }

    }
}
