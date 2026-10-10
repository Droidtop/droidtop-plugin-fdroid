package dev.droidtop.plugins.fdroid

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Checks EntryJar against the real entry.jar files of F-Droid and IzzyOnDroid (test/fixtures, copied from
 * their public repository indexes; both put META-INF/MANIFEST.MF last, which JarInputStream cannot read,
 * Droidtop/tracker#478). Run with test/run.sh. Exits non-zero on the first failed check.
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "test/fixtures" })
    val cases = listOf(
        "izzyondroid-entry.jar" to "3bf0d6abfeae2f401707b6d966be743bf0eee49c2561b9ba39073711f628937a",
        "fdroid-entry.jar" to "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab",
    )
    var failed = 0
    fun check(name: String, ok: Boolean) {
        println((if (ok) "ok   " else "FAIL ") + name)
        if (!ok) failed++
    }
    fun refusal(bytes: ByteArray, certificate: String? = null, fingerprint: String? = null): String? =
        try { EntryJar.verify(bytes, certificate, fingerprint); null } catch (e: EntryJar.Refused) { e.message }

    for ((file, fingerprint) in cases) {
        val bytes = File(dir, file).readBytes()
        val verified = try { EntryJar.verify(bytes, null, null) } catch (e: EntryJar.Refused) { null.also { println("  refused: ${e.message}") } }
        check("$file verifies on first use", verified != null)
        check("$file fingerprint", verified?.fingerprint == fingerprint)
        check("$file entry.json is the index description", verified?.entryJson?.contains("\"index\"") == true)
        check("$file pinned certificate", refusal(bytes, verified?.certificateHex, null) == null)
        check("$file fingerprint from a link", refusal(bytes, null, fingerprint) == null)
        check("$file wrong fingerprint refused", refusal(bytes, null, "00".repeat(32))?.contains("fingerprint") == true)
        check("$file other pinned key refused", refusal(bytes, "00".repeat(300), null)?.contains("different key") == true)

        // A byte of entry.json changed: the manifest digest no longer matches.
        check("$file tampered entry.json refused", refusal(rewrite(bytes) { name, data -> if (name == "entry.json") data.also { it[10] = (it[10] + 1).toByte() } else data })?.contains("digest") == true)
        // The signature file changed: the signature block no longer matches it.
        check("$file tampered signature file refused", refusal(rewrite(bytes) { name, data -> if (name.endsWith(".SF")) data.also { it[data.size - 4] = (it[data.size - 4] + 1).toByte() } else data }) != null)
        // The manifest changed (its entry.json digest too): the signature file no longer vouches for it.
        check("$file tampered manifest refused", refusal(rewrite(bytes) { name, data -> if (name.endsWith("MANIFEST.MF")) String(data).replace("Name: entry.json", "Name: entry.json\r\nX-Extra: 1").toByteArray() else data }) != null)
        // No signature at all.
        check("$file unsigned refused", refusal(rewrite(bytes, drop = { it.endsWith(".SF") || it.endsWith(".RSA") }))?.contains("not signed") == true)
        check("$file no manifest refused", refusal(rewrite(bytes, drop = { it.endsWith("MANIFEST.MF") }))?.contains("no manifest") == true)
        check("$file not a zip refused", refusal("<html>404</html>".toByteArray())?.contains("not a zip file") == true)
    }
    // One repository's signature block cannot vouch for the other's entry.json.
    val izzy = File(dir, cases[0].first).readBytes()
    val fdroid = File(dir, cases[1].first).readBytes()
    val crossed = rewrite(izzy) { name, data -> if (name.endsWith(".RSA")) entries(fdroid).entries.first { it.key.endsWith(".RSA") }.value else data }
    check("a signature block from another repository refused", refusal(crossed) != null)
    println(if (failed == 0) "all passed" else "$failed failed")
    if (failed != 0) kotlin.system.exitProcess(1)
}

private fun entries(jar: ByteArray): LinkedHashMap<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(jar.inputStream()).use { zip ->
        while (true) {
            val e = zip.nextEntry ?: break
            out[e.name] = zip.readBytes()
        }
    }
    return out
}

private fun rewrite(jar: ByteArray, drop: (String) -> Boolean = { false }, change: (String, ByteArray) -> ByteArray = { _, d -> d }): ByteArray {
    val sink = ByteArrayOutputStream()
    ZipOutputStream(sink).use { zip ->
        for ((name, data) in entries(jar)) {
            if (drop(name)) continue
            zip.putNextEntry(ZipEntry(name))
            zip.write(change(name, data.copyOf()))
            zip.closeEntry()
        }
    }
    return sink.toByteArray()
}

