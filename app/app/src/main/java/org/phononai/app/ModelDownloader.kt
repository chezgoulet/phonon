package org.phononai.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one GGUF into filesDir/models.
 *
 * Transport rules:
 *  - writes into `<name>.part` and renames only after the byte count matches
 *    the size the server declared;
 *  - resumes with an HTTP Range request when a `.part` exists (a server that
 *    ignores Range and answers 200 restarts the file — that is detected, not
 *    papered over);
 *  - no checksum is reported because none is computed.
 */
class ModelDownloader(private val context: Context) {

    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): File {
        dest.parentFile?.mkdirs()
        if (dest.isFile && dest.length() > 0 && !File(dest.path + PART_SUFFIX).exists()) {
            Log.i(TAG, "destination already exists, ${dest.length()} bytes: $dest")
            onProgress(dest.length(), dest.length())
            return dest
        }

        val part = File(dest.path + PART_SUFFIX)
        var offset = if (part.isFile) part.length() else 0L

        var conn = open(url, offset)
        try {
            if (conn.responseCode == 416 && offset > 0) {
                Log.w(TAG, "range not satisfiable at $offset; restarting from byte 0")
                conn.disconnect()
                part.delete()
                offset = 0
                conn = open(url, 0)
            }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code from $url")
            }

            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (append) {
                val rangeStart = parseContentRangeStart(conn.getHeaderField("Content-Range"))
                if (rangeStart != null && rangeStart != offset) {
                    throw IOException(
                        "server resumed at byte $rangeStart, requested $offset; refusing to corrupt the file",
                    )
                }
            }
            val contentLength = conn.contentLengthLong
            val expectedTotal: Long = if (append) {
                val rangeTotal = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                when {
                    rangeTotal != null -> rangeTotal
                    contentLength >= 0 -> offset + contentLength
                    else -> throw IOException("206 response without Content-Range or Content-Length; cannot verify size")
                }
            } else {
                if (contentLength < 0) {
                    throw IOException("server sent no Content-Length; refusing an unverifiable download")
                }
                contentLength
            }

            if (expectedTotal <= 0) throw IOException("server declared a zero-length body")
            if (!append) offset = 0

            onProgress(offset, expectedTotal)
            var written = offset
            val buffer = ByteArray(256 * 1024)
            var lastReport = 0L

            FileOutputStream(part, append).use { out ->
                val input: InputStream = conn.inputStream
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 250) {
                        lastReport = now
                        onProgress(written, expectedTotal)
                    }
                }
                out.flush()
                out.fd.sync()
            }
            onProgress(written, expectedTotal)

            val actual = part.length()
            if (actual != expectedTotal) {
                throw IOException(
                    "size mismatch: downloaded $actual bytes, server declared $expectedTotal" +
                        " — kept .part for resume",
                )
            }

            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            Log.i(TAG, "downloaded $url -> $dest ($actual bytes, size verified against Content-Length)")
            return dest
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, offset: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "phonon-app/0.1")
        // We need the file bytes on disk, not a transfer encoding.
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        return conn
    }

    private fun parseContentRangeStart(header: String?): Long? {
        if (header == null) return null
        // bytes 123-456/789
        val match = Regex("""bytes\s+(\d+)-""").find(header) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        if (header == null) return null
        val slash = header.lastIndexOf('/')
        if (slash < 0) return null
        return header.substring(slash + 1).trim().toLongOrNull()
    }

    private companion object {
        const val TAG = "Phonon"
        const val PART_SUFFIX = ".part"
    }
}
