package dev.droidtop.plugins.fdroid

import android.os.ParcelFileDescriptor
import dev.droidtop.pluginhost.PluginContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import org.json.JSONObject

/**
 * Everything this plugin asks of droidtop, through the broker (docs/plugin-api.md 1.4): it runs contained, with no
 * socket and no file path of its own, so the network is `net.http` (small answers) and `net.download` (an index, as a
 * job into the plugin's own data), and its files are `data.open` descriptors.
 */
internal class Host(private val context: PluginContext) {
    /** One broker call; the reply's `data`, or an [IOException] with the broker's reason. */
    fun call(api: String, op: String, args: JSONObject = JSONObject()): JSONObject {
        val reply = JSONObject(context.call(api, 1, op, args.toString()))
        if (!reply.optBoolean("ok")) {
            val error = reply.optJSONObject("error")
            throw IOException(error?.optString("message")?.takeIf { it.isNotBlank() } ?: "droidtop refused $api.$op")
        }
        return reply.optJSONObject("data") ?: JSONObject()
    }

    /** GET [url], at most 128 KiB, as bytes; [IOException] for anything but a 2xx answer that fits. */
    fun get(url: String): ByteArray {
        val answer = call("net", "http", JSONObject().put("url", url).put("as", "base64").put("timeoutMs", 30_000))
        val status = answer.optInt("status")
        if (status == 404) throw NotFound(url)
        if (status !in 200..299) throw IOException("HTTP $status from $url")
        if (answer.optBoolean("truncated")) throw IOException("$url is larger than droidtop hands over in one answer")
        return Base64.getDecoder().decode(answer.optString("bodyBase64"))
    }

    /** The first part of [url] as text (at most 128 KiB), whether or not there is more. */
    fun head(url: String): String {
        val answer = call("net", "http", JSONObject().put("url", url).put("timeoutMs", 30_000))
        val status = answer.optInt("status")
        if (status !in 200..299) throw IOException("HTTP $status from $url")
        return answer.optString("body")
    }

    /**
     * Downloads [url] into the plugin's data as [name], as a droidtop download job, and waits for it, telling
     * [onProgress] how far it is. [IOException] when it fails or is cancelled ([cancelled] asks each second).
     */
    fun download(url: String, name: String, cancelled: () -> Boolean, onProgress: (Int, String) -> Unit) {
        val jobId = call("net", "download", JSONObject().put("url", url).put("name", name)).optString("jobId")
        while (true) {
            if (cancelled()) {
                runCatching { call("plugins", "job_cancel", JSONObject().put("jobId", jobId)) }
                throw IOException("cancelled")
            }
            val status = call("plugins", "job_status", JSONObject().put("jobId", jobId))
            if (status.optBoolean("done")) {
                if (!status.optBoolean("ok")) throw IOException(status.optString("message").ifBlank { "the download of $url failed" })
                return
            }
            onProgress(status.optInt("percent", -1), status.optString("status"))
            Thread.sleep(1_000)
        }
    }

    fun openRead(name: String): InputStream = ParcelFileDescriptor.AutoCloseInputStream(open(name, "r"))

    fun openWrite(name: String): OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(open(name, "w"))

    private fun open(name: String, mode: String): ParcelFileDescriptor {
        val reply = context.openFile("data", 1, "open", JSONObject().put("name", name).put("mode", mode).toString())
        return reply.fd ?: throw if (JSONObject(reply.reply).optJSONObject("error")?.optString("code") == "NOT_FOUND") {
            NotFound(name)
        } else {
            IOException("droidtop did not open $name: " + (JSONObject(reply.reply).optJSONObject("error")?.optString("message") ?: "refused"))
        }
    }

    /** The text of data file [name], or null when there is none. */
    fun readText(name: String): String? = try {
        openRead(name).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: NotFound) {
        null
    }

    /** Replaces data file [name] whole: written beside it, then moved over it. */
    fun writeText(name: String, text: String) {
        openWrite("$name.tmp").use { it.write(text.toByteArray(Charsets.UTF_8)) }
        call("data", "move", JSONObject().put("from", "$name.tmp").put("to", name))
    }

    fun delete(name: String) {
        runCatching { call("data", "delete", JSONObject().put("name", name)) }
    }

    fun move(from: String, to: String) {
        call("data", "move", JSONObject().put("from", from).put("to", to))
    }

    class NotFound(what: String) : IOException("$what was not found")
}
