package dev.archdock.installer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Resumable HTTP(S) download with manual redirect handling (http↔https). */
class Downloader(private val userAgent: String = "ArchDock/0.1 (Android)") {

    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val conn = open(url, 0)
        conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    /**
     * Downloads [url] to [dest]. If [dest] already holds a partial download it
     * is resumed with a Range request. Returns the final size.
     */
    suspend fun download(url: String, dest: File, onProgress: (done: Long, total: Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            var have = if (dest.exists()) dest.length() else 0L
            val conn = open(url, have)
            val code = conn.responseCode
            if (code == 416) { // already complete
                conn.disconnect(); return@withContext have
            }
            if (code == HttpURLConnection.HTTP_OK && have > 0) have = 0 // server ignored Range
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL)
                throw IOException("HTTP $code for $url")
            val total = conn.getHeaderFieldLong("Content-Length", -1).let { if (it > 0) it + have else -1 }
            RandomAccessFile(dest, "rw").use { raf ->
                raf.setLength(have)
                raf.seek(have)
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 18)
                    var done = have
                    var last = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val r = input.read(buf)
                        if (r < 0) break
                        raf.write(buf, 0, r)
                        done += r
                        if (done - last > (1 shl 20)) { onProgress(done, total); last = done }
                    }
                    onProgress(done, total)
                }
            }
            conn.disconnect()
            dest.length()
        }

    private fun open(url: String, rangeStart: Long): HttpURLConnection {
        var current = URL(url)
        repeat(8) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", userAgent)
            if (rangeStart > 0) c.setRequestProperty("Range", "bytes=$rangeStart-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                current = URL(current, loc)
                c.disconnect()
            } else return c
        }
        throw IOException("too many redirects for $url")
    }
}
