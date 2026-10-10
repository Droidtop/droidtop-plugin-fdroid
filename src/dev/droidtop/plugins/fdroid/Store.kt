package dev.droidtop.plugins.fdroid

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** One repository the person added: where it is, the certificate it was added with, and what its last index said. */
internal data class Repo(
    val id: String,
    val address: String,
    val name: String,
    val description: String?,
    val certificate: String,
    val fingerprint: String,
    val enabled: Boolean = true,
    val updated: Long = 0L,
    val timestamp: Long = 0L,
    val indexSha: String? = null,
    val apps: Int = 0,
    val mirrors: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("address", address).put("name", name).put("description", description ?: "")
        .put("certificate", certificate).put("fingerprint", fingerprint).put("enabled", enabled).put("updated", updated)
        .put("timestamp", timestamp).put("indexSha", indexSha ?: "").put("apps", apps).put("mirrors", JSONArray(mirrors))

    companion object {
        fun idFor(address: String): String =
            EntryJar.hex(MessageDigest.getInstance("SHA-256").digest(address.trimEnd('/').toByteArray())).take(16)

        fun fromJson(o: JSONObject): Repo? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            return Repo(
                id = id,
                address = o.optString("address"),
                name = o.optString("name"),
                description = o.optString("description").takeIf { it.isNotBlank() },
                certificate = o.optString("certificate"),
                fingerprint = o.optString("fingerprint"),
                enabled = o.optBoolean("enabled", true),
                updated = o.optLong("updated"),
                timestamp = o.optLong("timestamp"),
                indexSha = o.optString("indexSha").takeIf { it.isNotBlank() },
                apps = o.optInt("apps"),
                mirrors = o.optJSONArray("mirrors")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            )
        }
    }
}

/** A repository fetched for the person's review, kept until Accept (the process may end in between). */
internal data class Pending(
    val token: String,
    val address: String,
    val certificate: String,
    val fingerprint: String,
    val fromLink: Boolean,
    val name: String,
    val description: String?,
    val apps: Int?,
    val created: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("token", token).put("address", address).put("certificate", certificate)
        .put("fingerprint", fingerprint).put("fromLink", fromLink).put("name", name).put("description", description ?: "")
        .put("apps", apps ?: -1).put("created", created)

    companion object {
        fun fromJson(o: JSONObject) = Pending(
            o.optString("token"), o.optString("address"), o.optString("certificate"), o.optString("fingerprint"),
            o.optBoolean("fromLink"), o.optString("name"), o.optString("description").takeIf { it.isNotBlank() },
            o.optInt("apps", -1).takeIf { it >= 0 }, o.optLong("created"),
        )
    }
}

/**
 * The plugin's own state, in its data folder (docs/plugin-api.md 3 H1): `sources.json` (the repositories, in the
 * order added: the first that lists an app is the one it is offered from, as F-Droid's repository weight does),
 * `pending.json` (reviews awaiting Accept) and one compact index per repository, `repos/<id>.json` ([Indexer]).
 * Compact indexes are loaded once per plugin process and dropped when a repository changes.
 */
internal class Store(private val host: Host) {
    private val lock = Any()
    private val indexes = HashMap<String, JSONObject>()

    fun repos(): List<Repo> = synchronized(lock) {
        val text = host.readText(SOURCES) ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Repo::fromJson) }
    }

    fun saveRepos(repos: List<Repo>) = synchronized(lock) {
        host.writeText(SOURCES, JSONArray(repos.map { it.toJson() }).toString())
    }

    fun update(repo: Repo) = synchronized(lock) {
        saveRepos(repos().map { if (it.id == repo.id) repo else it }.let { list -> if (list.any { it.id == repo.id }) list else list + repo })
    }

    fun remove(id: String) = synchronized(lock) {
        saveRepos(repos().filterNot { it.id == id })
        host.delete(indexName(id))
        indexes.remove(id)
    }

    fun pending(): Map<String, Pending> = synchronized(lock) {
        val o = runCatching { JSONObject(host.readText(PENDING) ?: "{}") }.getOrDefault(JSONObject())
        o.keys().asSequence().associateWith { Pending.fromJson(o.getJSONObject(it)) }
            .filterValues { System.currentTimeMillis() - it.created < PENDING_FOR_MS }
    }

    fun putPending(p: Pending) = synchronized(lock) {
        val all = pending() + (p.token to p)
        host.writeText(PENDING, JSONObject().apply { all.forEach { (k, v) -> put(k, v.toJson()) } }.toString())
    }

    fun dropPending(token: String) = synchronized(lock) {
        val all = pending() - token
        host.writeText(PENDING, JSONObject().apply { all.forEach { (k, v) -> put(k, v.toJson()) } }.toString())
    }

    /** The compact index of [id], or null when it has none yet. */
    fun index(id: String): JSONObject? = synchronized(lock) {
        indexes[id] ?: host.readText(indexName(id))?.let { runCatching { JSONObject(it) }.getOrNull() }?.also { indexes[id] = it }
    }

    fun putIndex(id: String, index: JSONObject) = synchronized(lock) {
        host.writeText(indexName(id), index.toString())
        indexes[id] = index
    }

    companion object {
        const val SOURCES = "sources.json"
        const val PENDING = "pending.json"
        const val PENDING_FOR_MS = 24L * 60 * 60 * 1000

        fun indexName(id: String) = "repos/$id.json"
    }
}
