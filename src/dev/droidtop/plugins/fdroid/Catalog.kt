package dev.droidtop.plugins.fdroid

import org.json.JSONArray
import org.json.JSONObject

/** The device a call is for (`device {sdk, abis, locale}`, docs/plugin-api.md A12). */
internal data class Device(val sdk: Int, val abis: List<String>, val locale: String) {
    companion object {
        fun of(args: JSONObject): Device {
            val d = args.optJSONObject("device") ?: JSONObject()
            val abis = d.optJSONArray("abis")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return Device(d.optInt("sdk", 26), abis, d.optString("locale", "en-US").ifBlank { "en-US" })
        }
    }
}

/**
 * Answers `search`, `get_app`, `list_latest` and `acquire` (docs/plugin-api.md 3 A12) from the compact indexes. A version is
 * offered when it is stable (no release channel), the device's Android version is in its SDK range and it has no
 * native code or code for one of the device's ABIs, the rules of F-Droid's `CompatibilityCheckerImpl` for these
 * fields. When several repositories list an app, the first one added that has a compatible version offers it.
 */
internal class Catalog(private val store: Store) {
    private class Listed(val repo: Repo, val app: JSONObject, val antiFeatures: JSONObject)

    private fun listed(): List<Listed> {
        val out = ArrayList<Listed>()
        val seen = HashSet<String>()
        for (repo in store.repos().filter { it.enabled }) {
            val index = store.index(repo.id) ?: continue
            val apps = index.optJSONArray("apps") ?: continue
            val anti = index.optJSONObject("antiFeatures") ?: JSONObject()
            for (i in 0 until apps.length()) {
                val app = apps.getJSONObject(i)
                if (seen.add(app.optString("id"))) out += Listed(repo, app, anti)
            }
        }
        return out
    }

    private fun compatible(v: JSONObject, device: Device): Boolean {
        if ((v.optJSONArray("ch")?.length() ?: 0) > 0) return false
        if (device.sdk < v.optInt("min", 1)) return false
        val max = v.optInt("max", 0)
        if (max in 1 until device.sdk) return false
        val abis = v.optJSONArray("abi")
        if (abis == null || abis.length() == 0) return true
        return (0 until abis.length()).any { abis.optString(it) in device.abis }
    }

    private fun versions(app: JSONObject, device: Device): List<JSONObject> {
        val v = app.optJSONArray("v") ?: return emptyList()
        return (0 until v.length()).map { v.getJSONObject(it) }.filter { compatible(it, device) }.sortedByDescending { it.optLong("c") }
    }

    fun search(query: String, limit: Int, device: Device): JSONObject {
        val q = query.trim().lowercase()
        val rows = listed().mapNotNull { l ->
            val best = versions(l.app, device).firstOrNull() ?: return@mapNotNull null
            val score = when {
                q.isEmpty() -> 0
                l.app.optString("name").lowercase().contains(q) -> 4
                l.app.optString("summary").lowercase().contains(q) -> 3
                l.app.optString("id").lowercase().contains(q) -> 2
                l.app.optString("description").lowercase().contains(q) -> 1
                else -> return@mapNotNull null
            }
            Triple(score, l, best)
        }.sortedWith(compareByDescending<Triple<Int, Listed, JSONObject>> { it.first }.thenByDescending { it.second.app.optLong("updated") })
            .take(limit.coerceIn(1, 200))
        return JSONObject().put(
            "apps",
            JSONArray(rows.map { (_, l, best) ->
                JSONObject().put("id", l.app.optString("id")).put("name", l.app.optString("name")).put("summary", l.app.optString("summary"))
                    .put("version", best.optString("n")).put("versionCode", best.optLong("c")).put("source", l.repo.name)
                    .put("antiFeatures", JSONArray(best.optJSONObject("af")?.keys()?.asSequence()?.toList().orEmpty()))
            }),
        )
    }

    fun app(id: String, device: Device): JSONObject? {
        val l = listed().firstOrNull { it.app.optString("id") == id } ?: return null
        val a = l.app
        return JSONObject().put(
            "app",
            JSONObject().put("id", id).put("name", a.optString("name")).put("summary", a.optString("summary"))
                .put("description", a.optString("description")).put("license", a.optString("license"))
                .put("website", a.optString("web")).put("sourceCode", a.optString("code")).put("author", a.optString("author"))
                .put("source", l.repo.name)
                .put(
                    "versions",
                    JSONArray(versions(a, device).map { v ->
                        val af = v.optJSONObject("af") ?: JSONObject()
                        JSONObject().put("version", v.optString("n")).put("versionCode", v.optLong("c")).put("size", v.optLong("s"))
                            .put("signers", v.optJSONArray("g") ?: JSONArray()).put("minSdk", v.optInt("min")).put("added", v.optLong("a"))
                            .put("whatsNew", v.optString("w"))
                            .put(
                                "antiFeatures",
                                JSONArray(af.keys().asSequence().map { key ->
                                    val def = l.antiFeatures.optJSONObject(key)
                                    JSONObject().put("key", key).put("label", def?.optString("name")?.ifBlank { null } ?: key)
                                        .put("reason", af.optString(key).ifBlank { def?.optString("description") ?: "" })
                                }.toList()),
                            )
                    }),
                ),
        )
    }

    fun latest(after: String?, limit: Int, device: Device): JSONObject {
        val rows = listed().filter { after == null || it.app.optString("id") > after }.sortedBy { it.app.optString("id") }
            .mapNotNull { l -> versions(l.app, device).firstOrNull()?.let { l to it } }
        val page = rows.take(limit.coerceIn(1, 500))
        val out = JSONObject().put(
            "apps",
            JSONArray(page.map { (l, v) ->
                JSONObject().put("id", l.app.optString("id")).put("name", l.app.optString("name")).put("version", v.optString("n"))
                    .put("versionCode", v.optLong("c")).put("signers", v.optJSONArray("g") ?: JSONArray()).put("source", l.repo.name)
            }),
        )
        if (rows.size > page.size) out.put("next", page.last().first.app.optString("id"))
        return out
    }

    /** The download for [id] at [versionCode] (0: the newest compatible), and what the APK must be. */
    fun acquire(id: String, versionCode: Long, device: Device): Map<String, String>? {
        val l = listed().firstOrNull { it.app.optString("id") == id } ?: return null
        val v = versions(l.app, device).firstOrNull { versionCode <= 0 || it.optLong("c") == versionCode } ?: return null
        val path = v.optString("f").let { if (it.startsWith("/")) it else "/$it" }
        val signers = v.optJSONArray("g")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val download = JSONObject().put("url", l.repo.address.trimEnd('/') + path)
            .put("fileName", "${id}_${v.optLong("c")}.apk").put("sha256", v.optString("h"))
        v.optLong("s").takeIf { it > 0 }?.let { download.put("size", it) }
        return mapOf(
            "download" to download.toString(),
            "packageName" to id,
            "versionCode" to v.optLong("c").toString(),
            "signers" to signers.joinToString(","),
            "summary" to "${l.app.optString("name")} ${v.optString("n")} from ${l.repo.name}",
        )
    }
}
