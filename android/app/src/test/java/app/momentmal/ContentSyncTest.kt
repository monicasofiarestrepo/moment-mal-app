package app.momentmal

import app.momentmal.content.Catalog
import app.momentmal.content.ContentSync
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.nio.file.Files

/** Fake GitHub Contents API serving the real ../../content files. */
class ContentSyncTest {
    private val root = File("../../content")
    private lateinit var server: ServerSocket
    private val overrides = mutableMapOf<String, String>()
    private var requiredToken = "secret"
    private lateinit var dir: File

    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                    val auth = headers.firstOrNull { it.startsWith("Authorization:", true) }?.substringAfter(":")?.trim()
                    val path = requestLine.split(" ")[1].substringBefore('?').removePrefix("/repos/me/content/contents/")
                    val (code, body) = when {
                        auth != "Bearer $requiredToken" -> 401 to "{}"
                        path in overrides -> 200 to overrides.getValue(path)
                        File(root, path).isFile -> 200 to File(root, path).readText()
                        else -> 404 to "{}"
                    }
                    val bytes = body.toByteArray()
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
        dir = File(Files.createTempDirectory("mm").toFile(), "content")
    }

    @After fun stop() = server.close()

    private fun sync(current: Int = 0, force: Boolean = false, token: String = "secret") =
        ContentSync("http://127.0.0.1:${server.localPort}").sync("me/content", "main", token, dir, current, force)

    @Test fun downloadsValidatesAndInstalls() {
        val r = sync()
        assertTrue(r.updated)
        assertEquals(300, r.notes)
        val installed = Catalog.load { File(dir, it).readText() }
        assertEquals(3, installed.decks.size)
    }

    @Test fun skipsWhenVersionNotNewer() {
        val r = sync(current = 99)
        assertFalse(r.updated)
        assertFalse(dir.exists())
    }

    @Test fun badTokenGivesClearError() {
        try { sync(token = "wrong"); fail() } catch (e: ContentSync.SyncException) {
            assertTrue(e.message!!.contains("Token"))
        }
    }

    @Test fun invalidContentKeepsPreviousVersion() {
        sync()
        val before = File(dir, "decks/deutsch.json").readText()
        overrides["decks/deutsch.json"] = """{"deck":"deutsch","notes":[{"id":"x","type":"nomen"}]}"""
        try { sync(force = true); fail() } catch (e: ContentSync.SyncException) {
            assertTrue(e.message!!.startsWith("Inhalt ungültig"))
        }
        assertEquals(before, File(dir, "decks/deutsch.json").readText())
    }
}
