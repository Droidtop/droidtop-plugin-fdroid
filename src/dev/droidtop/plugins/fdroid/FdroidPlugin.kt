package dev.droidtop.plugins.fdroid

import dev.droidtop.pluginhost.DroidtopPlugin
import dev.droidtop.pluginhost.PluginArgs
import dev.droidtop.pluginhost.PluginCall
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginContext
import dev.droidtop.pluginhost.PluginErrorCode
import dev.droidtop.pluginhost.PluginJobProgress
import dev.droidtop.pluginhost.PluginReply
import dev.droidtop.pluginhost.PluginResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * F-Droid repositories for droidtop: an app catalog (`apps.catalog@1`, droidtop docs/plugin-api.md 3 A12). droidtop
 * draws every screen, downloads, checks and installs; this plugin keeps the repositories the person added, fetches
 * and verifies their signed indexes, and answers what is in them. No repository is configured by default (owner,
 * 2026-10-01); the person adds them by link (fdroidrepos://, an https repository link), QR code, address and
 * fingerprint, or from the lists F-Droid's own client carries ([KNOWN]).
 */
class FdroidPlugin : DroidtopPlugin {
    private lateinit var host: Host
    private lateinit var store: Store
    private lateinit var indexer: Indexer
    private lateinit var catalog: Catalog
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    override fun onLoad(context: PluginContext) {
        host = Host(context)
        store = Store(host)
        indexer = Indexer(host, store)
        catalog = Catalog(store)
    }

    override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult =
        PluginResult.failure("this plugin answers only contract 2 calls")

    override fun handle(call: PluginCall): PluginReply {
        if (call.point != POINT) return PluginReply.error(PluginErrorCode.UNSUPPORTED, "unsupported point ${call.point}")
        val args = call.args
        val device = Device.of(args)
        return try {
            when (call.op) {
                "sources" -> PluginReply.ok(sources())
                "open_link" -> review(args.optString("link"))
                "remove_source" -> {
                    val repo = store.repos().firstOrNull { it.id == args.optString("source") }
                        ?: return PluginReply.error(PluginErrorCode.NOT_FOUND, "no such repository")
                    store.remove(repo.id)
                    PluginReply.ok(JSONObject().put("message", "Removed ${repo.name}"))
                }
                "set_source_enabled" -> {
                    val repo = store.repos().firstOrNull { it.id == args.optString("source") }
                        ?: return PluginReply.error(PluginErrorCode.NOT_FOUND, "no such repository")
                    val enabled = args.optBoolean("enabled", true)
                    store.update(repo.copy(enabled = enabled))
                    PluginReply.ok(JSONObject().put("message", if (enabled) "${repo.name} is used again" else "${repo.name} is switched off"))
                }
                "search" -> PluginReply.ok(catalog.search(args.optString("query"), args.optInt("limit", 200), device))
                "app" -> catalog.app(args.optString("id"), device)?.let { PluginReply.ok(it) }
                    ?: PluginReply.error(PluginErrorCode.NOT_FOUND, "no repository you added lists ${args.optString("id")} for this device")
                "latest" -> PluginReply.ok(catalog.latest(args.optString("after").takeIf { it.isNotBlank() }, args.optInt("limit", 500), device))
                else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "unsupported op ${call.op}")
            }
        } catch (refused: EntryJar.Refused) {
            PluginReply.error(PluginErrorCode.FAILED, refused.message ?: "the repository was refused")
        } catch (failure: java.io.IOException) {
            PluginReply.error(PluginErrorCode.FAILED, failure.message ?: "the repository could not be read")
        }
    }

    private fun sources(): JSONObject {
        val repos = store.repos()
        val sources = JSONArray(repos.map { r ->
            JSONObject().put("id", r.id).put("name", r.name).put("address", r.address).put("enabled", r.enabled)
                .put("fingerprint", r.fingerprint).put("apps", r.apps)
                .apply { if (r.updated > 0) put("updated", r.updated) }
                .apply { r.description?.let { put("note", it.take(300)) } }
        })
        val added = repos.map { it.address.trimEnd('/') }.toSet()
        val known = JSONArray(KNOWN.filter { it.address !in added }.map { k ->
            JSONObject().put("name", k.name).put("address", "${k.address}?fingerprint=${k.fingerprint}").put("about", k.about)
        })
        return JSONObject().put("sources", sources).put("known", known)
    }

    /** `open_link`: fetches and verifies what a link names, for the person's review; nothing is stored but the review. */
    private fun review(link: String): PluginReply {
        val parsed = RepoLinks.parse(link) ?: return PluginReply.error(PluginErrorCode.INVALID_ARGS, "That is not an F-Droid repository address or link")
        if (RepoLinks.isArchive(parsed.address)) {
            return PluginReply.error(PluginErrorCode.INVALID_ARGS, "That is a repository's archive; add the repository itself (its address ending in /repo)")
        }
        val known = KNOWN.firstOrNull { it.address == parsed.address }
        if (known != null && parsed.fingerprint != null && parsed.fingerprint != known.fingerprint) {
            return PluginReply.error(PluginErrorCode.FAILED, "The link names another key than ${known.name}'s known one; nothing was added")
        }
        val fingerprint = parsed.fingerprint ?: known?.fingerprint
        val preview = indexer.preview(parsed.address, fingerprint)
        val existing = store.repos().any { it.address.trimEnd('/') == parsed.address || it.certificate == preview.verified.certificateHex }
        val token = UUID.randomUUID().toString()
        val name = preview.name ?: known?.name ?: parsed.address.removePrefix("https://").substringBefore('/')
        store.putPending(
            Pending(token, parsed.address, preview.verified.certificateHex, preview.verified.fingerprint, fingerprint != null, name,
                preview.description, preview.apps, System.currentTimeMillis()),
        )
        return PluginReply.ok(
            JSONObject().put(
                "review",
                JSONObject().put("token", token).put("name", name).put("address", parsed.address)
                    .put("fingerprint", preview.verified.fingerprint).put("fingerprintFromLink", fingerprint != null)
                    .put("description", preview.description ?: "").put("apps", preview.apps).put("existing", existing)
                    .apply {
                        if (fingerprint == null) put("note", "The link named no fingerprint. droidtop trusts this key from now on: compare it with the repository's own page first")
                    },
            ),
        )
    }

    override fun startJob(jobId: String, capability: PluginCapability, args: PluginArgs, progress: PluginJobProgress) {
        val call = args.string("call")?.let { PluginCall.fromJson(it) }
        if (call == null || call.point != POINT) {
            progress.complete(PluginResult.failure("unsupported job"))
            return
        }
        Thread({
            val result = try {
                runJob(jobId, call, progress)
            } catch (refused: EntryJar.Refused) {
                PluginResult.failure(refused.message ?: "the repository was refused")
            } catch (failure: Exception) {
                PluginResult.failure(failure.message ?: failure.javaClass.simpleName)
            } finally {
                cancelled.remove(jobId)
            }
            progress.complete(result)
        }, "fdroid-$jobId").start()
    }

    private fun runJob(jobId: String, call: PluginCall, progress: PluginJobProgress): PluginResult {
        val device = Device.of(call.args)
        val isCancelled = { jobId in cancelled }
        return when (call.op) {
            "add_source" -> {
                val pending = store.pending()[call.args.optString("token")] ?: return PluginResult.failure("That review has expired; open the link again")
                if (store.repos().any { it.address.trimEnd('/') == pending.address }) return PluginResult.success(mapOf("message" to "${pending.name} is already added"))
                var repo = Repo(Repo.idFor(pending.address), pending.address, pending.name, pending.description, pending.certificate, pending.fingerprint)
                repo = indexer.refresh(repo, device.locale, isCancelled) { percent, line -> progress.report(percent, line) }
                store.update(repo)
                store.dropPending(pending.token)
                PluginResult.success(mapOf("message" to "Added ${repo.name}: ${repo.apps} apps"))
            }
            "refresh" -> {
                val only = call.args.optString("source").takeIf { it.isNotBlank() }
                val repos = store.repos().filter { it.enabled && (only == null || it.id == only) }
                val lines = repos.map { repo ->
                    runCatching { indexer.refresh(repo, device.locale, isCancelled) { percent, line -> progress.report(percent, line) } }
                        .fold(
                            onSuccess = { store.update(it); "${it.name}: ${it.apps} apps" },
                            onFailure = { "${repo.name}: ${it.message ?: "failed"}" },
                        )
                }
                PluginResult.success(mapOf("message" to if (lines.isEmpty()) "No repository to refresh" else lines.joinToString(". ")))
            }
            "acquire" -> catalog.acquire(call.args.optString("id"), call.args.optLong("versionCode", 0L), device)
                ?.let { PluginResult.success(it) }
                ?: PluginResult.failure("No repository you added has that version for this device")
            else -> PluginResult.failure("unsupported job ${call.op}")
        }
    }

    override fun cancelJob(jobId: String) {
        cancelled += jobId
    }

    /** A repository F-Droid's own client lists: its address and the fingerprint F-Droid pins for it. */
    private class Known(val name: String, val address: String, val fingerprint: String, val about: String)

    companion object {
        const val POINT = "apps.catalog"

        /**
         * The repositories F-Droid's own client knows (gitlab.com/fdroid/fdroidclient: app/src/main/assets/
         * default_repos.json for F-Droid itself, fingerprint computed from the certificate there; and
         * libs/database org.fdroid.repo.KnownRepos for the others, address and fingerprint as written there).
         * Listed for a press; none is added until the person accepts its review.
         */
        private val KNOWN = listOf(
            Known("F-Droid", "https://f-droid.org/repo", "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab",
                "The official F-Droid Free Software repository, built from source"),
            Known("IzzyOnDroid", "https://apt.izzysoft.de/fdroid/repo", "3bf0d6abfeae2f401707b6d966be743bf0eee49c2561b9ba39073711f628937a",
                "Apps from their developers' own releases"),
            Known("Guardian Project", "https://guardianproject.info/fdroid/repo", "b7c2eefd8dac7806af67dfcd92eb18126bc08312a7f2d6f3862e46013c7a6135",
                "The Guardian Project's apps"),
            Known("microG", "https://microg.org/fdroid/repo", "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165",
                "microG's own builds"),
            Known("NewPipe", "https://archive.newpipe.net/fdroid/repo", "e2402c78f9b97c6c89e97db914a2751fda1d02fe2039cc0897a462bdb57e7501",
                "NewPipe's own builds"),
            Known("Briar", "https://briarproject.org/fdroid/repo", "1fb874bee7276d28ecb2c9b06e8a122ec4bcb4008161436ce474c257cbf49bd6",
                "Briar's own builds"),
        )
    }
}
