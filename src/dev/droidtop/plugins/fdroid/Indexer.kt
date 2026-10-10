package dev.droidtop.plugins.fdroid

import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import org.fdroid.index.IndexParser
import org.fdroid.index.v2.IndexV2FullStreamProcessor
import org.fdroid.index.v2.IndexV2StreamReceiver
import org.fdroid.index.v2.PackageV2
import org.fdroid.index.v2.RepoV2
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches and verifies a repository's index v2 the way F-Droid does (upstream: gitlab.com/fdroid/fdroidclient
 * `libs/index`, the `org.fdroid:index` library this plugin bundles): `entry.jar` is fetched and its signature checked
 * against the repository's certificate ([EntryJar]); `entry.json` names the index file and its SHA-256; the index is
 * downloaded as a droidtop job, its SHA-256 checked while it is parsed by upstream's [IndexV2FullStreamProcessor],
 * and an index older than the one already held is refused (a replayed old index cannot roll a repository back).
 * What is kept is a compact copy per repository: each app's texts in the person's language, its anti-features, and
 * its newest [MAX_VERSIONS] versions with file, digest, size, signing keys, SDK range, ABIs and release channels.
 * Diff indexes are not used yet: a changed index is fetched whole.
 */
internal class Indexer(private val host: Host, private val store: Store) {
    /** What a review fetch found: the verified certificate and what `entry.json` and the head of the index say. */
    class Preview(val verified: EntryJar.Verified, val name: String?, val description: String?, val apps: Int)

    fun preview(address: String, fingerprint: String?): Preview {
        val verified = EntryJar.verify(host.get("$address/entry.jar"), null, fingerprint)
        val entry = IndexParser.parseEntry(verified.entryJson)
        // The repository's name and description are in the index itself, whose "repo" block comes first; the head of
        // it is enough, and a full index can be tens of megabytes. Display text only: nothing is trusted from it.
        val head = runCatching { host.head(address + entry.index.name) }.getOrNull().orEmpty()
        return Preview(verified, firstLocalized(head, "name"), firstLocalized(head, "description"), entry.index.numPackages)
    }

    /** Refreshes [repo]; returns the updated record. [locale] picks the texts kept. */
    fun refresh(repo: Repo, locale: String, cancelled: () -> Boolean, progress: (Int, String) -> Unit): Repo {
        progress(-1, "Checking ${repo.name}")
        val verified = EntryJar.verify(host.get("${repo.address}/entry.jar"), repo.certificate, null)
        val entry = IndexParser.parseEntry(verified.entryJson)
        if (entry.timestamp < repo.timestamp) throw IOException("${repo.name} offered an older index than the one droidtop has; it was refused")
        if (entry.index.sha256.equals(repo.indexSha, ignoreCase = true) && store.index(repo.id) != null) {
            return repo.copy(updated = System.currentTimeMillis())
        }
        val download = "dl/${repo.id}.json"
        host.download(repo.address + entry.index.name, download, cancelled) { percent, line ->
            progress(percent, "Downloading ${repo.name}'s index" + if (line.isNotBlank()) ": $line" else "")
        }
        progress(-1, "Reading ${repo.name}'s index")
        val receiver = Compactor(locale)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestInputStream(host.openRead(download), digest).use { stream ->
                IndexV2FullStreamProcessor(receiver).process(entry.version, stream) { count ->
                    if (count % 500 == 0) progress(-1, "Reading ${repo.name}'s index: $count apps")
                }
                // Read to the end, so the digest covers every byte.
                stream.readBytes()
            }
        } finally {
            host.delete(download)
        }
        if (!EntryJar.hex(digest.digest()).equals(entry.index.sha256, ignoreCase = true)) {
            throw IOException("${repo.name}'s index does not match the digest its signed entry names; it was refused")
        }
        val repoV2 = receiver.repo ?: throw IOException("${repo.name}'s index has no repository block")
        if (repoV2.timestamp < repo.timestamp) throw IOException("${repo.name}'s index is older than the one droidtop has; it was refused")
        store.putIndex(repo.id, receiver.toJson())
        return repo.copy(
            name = pick(repoV2.name, locale) ?: repo.name,
            description = pick(repoV2.description, locale) ?: repo.description,
            updated = System.currentTimeMillis(),
            timestamp = repoV2.timestamp,
            indexSha = entry.index.sha256.lowercase(),
            apps = receiver.apps.length(),
            mirrors = repoV2.mirrors.map { it.url.trimEnd('/') }.filter { it.startsWith("https://") }.take(20),
        )
    }

    /** Receives upstream's parsed packages and keeps the compact copy. */
    private class Compactor(private val locale: String) : IndexV2StreamReceiver {
        var repo: RepoV2? = null
        val apps = JSONArray()

        override fun receive(repo: RepoV2, version: Long) {
            this.repo = repo
        }

        override fun receive(packageName: String, p: PackageV2) {
            val m = p.metadata
            val versions = p.versions.values.sortedByDescending { it.versionCode }.take(MAX_VERSIONS).map { v ->
                JSONObject()
                    .put("n", v.versionName).put("c", v.versionCode).put("f", v.file.name).put("h", v.file.sha256.lowercase())
                    .put("s", v.file.size ?: 0L)
                    .put("g", JSONArray(v.manifest.signer?.sha256.orEmpty().map { it.lowercase() }))
                    .put("min", v.manifest.usesSdk?.minSdkVersion ?: 1).put("max", v.manifest.maxSdkVersion ?: 0)
                    .put("abi", JSONArray(v.manifest.nativecode))
                    .put("af", JSONObject().apply { v.antiFeatures.forEach { (key, reason) -> put(key, pick(reason, locale) ?: "") } })
                    .put("ch", JSONArray(v.releaseChannels))
                    .put("a", v.added)
                    .put("w", pick(v.whatsNew, locale)?.take(2000) ?: "")
            }
            apps.put(
                JSONObject().put("id", packageName)
                    .put("name", pick(m.name, locale) ?: packageName)
                    .put("summary", pick(m.summary, locale) ?: "")
                    .put("description", pick(m.description, locale)?.take(6000) ?: "")
                    .put("license", m.license ?: "").put("web", m.webSite ?: "").put("code", m.sourceCode ?: "")
                    .put("author", m.authorName ?: "")
                    .put("updated", m.lastUpdated).put("added", m.added)
                    .put("v", JSONArray(versions)),
            )
        }

        override fun onStreamEnded() = Unit

        fun toJson(): JSONObject {
            val r = repo
            val anti = JSONObject()
            r?.antiFeatures?.forEach { (key, af) ->
                anti.put(key, JSONObject().put("name", pick(af.name, locale) ?: key).put("description", pick(af.description, locale) ?: ""))
            }
            return JSONObject().put("antiFeatures", anti).put("apps", apps)
        }
    }

    companion object {
        const val MAX_VERSIONS = 5

        /** The text for [locale] from a localized map: the tag, then its language, then English, then any. */
        fun pick(texts: Map<String, String>?, locale: String): String? {
            if (texts.isNullOrEmpty()) return null
            val language = locale.substringBefore('-')
            return texts[locale]
                ?: texts.entries.firstOrNull { it.key.substringBefore('-').equals(language, ignoreCase = true) }?.value
                ?: texts["en-US"] ?: texts["en"]
                ?: texts.values.first()
        }

        /** The first "<key>": {"en-US": "..."} in [json] (the repository block's), read without parsing the rest. */
        fun firstLocalized(json: String, key: String): String? {
            val at = Regex("\"" + key + "\"\\s*:\\s*\\{").find(json) ?: return null
            val block = json.substring(at.range.last + 1).substringBefore('}')
            val pairs = Regex("\"([A-Za-z-]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
            return pick(pairs, "en-US")?.replace("\\n", "\n")?.replace("\\\"", "\"")?.take(2000)
        }
    }
}
