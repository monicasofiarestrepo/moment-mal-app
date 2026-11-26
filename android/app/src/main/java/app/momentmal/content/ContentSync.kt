package app.momentmal.content

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads manifest + decks from the private GitHub repo (Contents API, raw media type),
 * validates them by fully parsing the catalog, and installs them atomically into [targetDir].
 * Nothing is replaced unless the whole new content set is valid.
 */
class ContentSync(
    private val apiBase: String = "https://api.github.com",
    private val timeoutMs: Int = 15_000,
) {
    data class Result(val version: Int, val updated: Boolean, val notes: Int = 0)

    class SyncException(message: String) : Exception(message)

    fun sync(repo: String, branch: String, token: String, targetDir: File, currentVersion: Int, force: Boolean = false): Result {
        val manifestText = get(repo, branch, "manifest.json", token)
        val manifest = runCatching { Catalog.json.decodeFromString<Manifest>(manifestText) }
            .getOrElse { throw SyncException("manifest.json ist ungültig: ${it.message}") }
        if (!force && manifest.version <= currentVersion) return Result(manifest.version, updated = false)

        val files = linkedMapOf("manifest.json" to manifestText)
        for (d in manifest.decks) files[d.file] = get(repo, branch, d.file, token)

        val catalog = runCatching { Catalog.load { files[it] ?: throw SyncException("Datei fehlt: $it") } }
            .getOrElse { throw SyncException("Inhalt ungültig: ${it.message}") }
        if (catalog.byId.size != catalog.cards.size) throw SyncException("Doppelte Karten-IDs im Inhalt")
        if (catalog.cards.isEmpty()) throw SyncException("Keine Karten im Inhalt")

        install(files, targetDir)
        return Result(manifest.version, updated = true, notes = catalog.cards.map { it.noteId }.distinct().size)
    }

    private fun install(files: Map<String, String>, targetDir: File) {
        val parent = targetDir.absoluteFile.parentFile ?: throw IOException("no parent dir")
        val tmp = File(parent, targetDir.name + ".tmp").apply { deleteRecursively() }
        val old = File(parent, targetDir.name + ".old").apply { deleteRecursively() }
        for ((path, text) in files) {
            val f = File(tmp, path)
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
        if (targetDir.exists() && !targetDir.renameTo(old)) throw IOException("could not move old content")
        if (!tmp.renameTo(targetDir)) {
            old.renameTo(targetDir) // roll back
            throw IOException("could not install new content")
        }
        old.deleteRecursively()
    }

    private fun get(repo: String, branch: String, path: String, token: String): String {
        val url = URL("$apiBase/repos/$repo/contents/$path?ref=$branch")
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/vnd.github.raw+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "MomentMal")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            return when (val code = c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                401 -> throw SyncException("Token ungültig oder abgelaufen")
                403 -> throw SyncException("Kein Zugriff (Token-Rechte: Contents → Read)")
                404 -> throw SyncException("Nicht gefunden: $path (Repo-Name? Token für dieses Repo?)")
                else -> throw SyncException("GitHub antwortet $code für $path")
            }
        } catch (e: IOException) {
            throw SyncException("Keine Verbindung: ${e.message}")
        } finally {
            c.disconnect()
        }
    }
}
