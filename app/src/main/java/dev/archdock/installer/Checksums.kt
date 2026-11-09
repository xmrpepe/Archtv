package dev.archdock.installer

import java.io.File
import java.security.MessageDigest

object Checksums {
    /** Parses `md5sum`/`sha256sum` output ("<hex>  <name>") and returns the hex digest. */
    fun parse(text: String, fileName: String? = null): String? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val match = lines.firstOrNull { fileName != null && it.endsWith(fileName) } ?: lines.firstOrNull()
        val hex = match?.split(Regex("\\s+"))?.firstOrNull()?.lowercase() ?: return null
        return hex.takeIf { it.matches(Regex("[0-9a-f]{32}|[0-9a-f]{64}")) }
    }

    fun digest(file: File, algorithm: String, onProgress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance(algorithm)
        val buf = ByteArray(1 shl 20)
        var done = 0L
        file.inputStream().use { input ->
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
                done += r
                onProgress(done)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun algorithmFor(hex: String) = if (hex.length == 64) "SHA-256" else "MD5"
}
