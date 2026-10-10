package dev.droidtop.plugins.fdroid

import java.net.URI
import java.net.URLDecoder

/**
 * What a repository link, address or QR code names: the repository's address and, when it carries one, the
 * fingerprint of its signing certificate. The rules are F-Droid's own (`org.fdroid.repo.RepoUriGetter` in
 * gitlab.com/fdroid/fdroidclient `libs/database`, Apache-2.0, which is internal there and written against
 * android.net.Uri): fdroidrepos:// is https and fdroidrepo:// is http; https://fdroid.link/#<address> carries the
 * address after the #; an address without a scheme is https; the path gets /fdroid/repo added when it ends in
 * neither /repo nor /archive; the query (where the fingerprint is) and any user info are dropped from the address.
 * Archive repositories are not added, as in F-Droid.
 */
internal object RepoLinks {
    data class Parsed(val address: String, val fingerprint: String?)

    fun parse(link: String): Parsed? {
        var text = link.trim()
        if (text.isEmpty() || text.length > 2000) return null
        val lower = text.lowercase()
        text = when {
            lower.startsWith("fdroidrepos://") -> "https://" + text.substring("fdroidrepos://".length)
            lower.startsWith("fdroidrepo://") -> "http://" + text.substring("fdroidrepo://".length)
            lower.startsWith("https://fdroid.link/#") -> URLDecoder.decode(text.substringAfter('#'), "UTF-8")
            !lower.contains("://") -> "https://$text"
            else -> text
        }
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val fingerprint = queryParameter(uri, "fingerprint")?.lowercase()?.replace(":", "")?.replace(" ", "")?.trim()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.toMutableList()
        when {
            segments.size >= 2 && segments[segments.size - 2] == "fdroid" && segments.last() in setOf("repo", "archive") -> Unit
            segments.lastOrNull() in setOf("repo", "archive") -> Unit
            segments.lastOrNull() == "fdroid" -> segments += "repo"
            else -> {
                segments += "fdroid"
                segments += "repo"
            }
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val address = "$scheme://$host$port/" + segments.joinToString("/")
        return Parsed(address, fingerprint)
    }

    fun isArchive(address: String): Boolean = address.trimEnd('/').endsWith("/archive")

    private fun queryParameter(uri: URI, name: String): String? = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { pair ->
        if (pair.substringBefore('=').equals(name, ignoreCase = true)) {
            runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
        } else {
            null
        }
    }
}
