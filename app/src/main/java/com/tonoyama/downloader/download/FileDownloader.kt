package com.tonoyama.downloader.download

import android.webkit.MimeTypeMap
import com.tonoyama.downloader.detect.MediaDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** 保存前の一時ファイルと、その種類 */
data class DownloadOutput(val file: File, val ext: String, val mime: String)

/** MP4 などの単体ファイルを、途中再開（Rangeリクエスト）に対応してダウンロードする */
object FileDownloader {

    private val videoExtensions = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp")

    suspend fun run(
        task: DownloadTask,
        dir: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ): DownloadOutput {
        dir.mkdirs()
        val part = File(dir, "data.part")
        var attempt = 0
        while (true) {
            try {
                return downloadOnce(task, part, onProgress)
            } catch (e: IOException) {
                if (e is HttpStatusException && e.isPermanent) throw e
                attempt++
                if (attempt >= 5) throw e
                delay(2_000L * attempt)
            }
        }
    }

    private suspend fun downloadOnce(
        task: DownloadTask,
        part: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutput {
        val existing = if (part.exists()) part.length() else 0L
        val conn = Http.open(task.url, task.headers, if (existing > 0) "bytes=$existing-" else null)
        try {
            val code = conn.responseCode
            val contentType = conn.contentType
            if (code == 416 && existing > 0) {
                // 既に最後まで取得済み
                return output(task.url, contentType, part)
            }
            if (code !in 200..299) throw HttpStatusException(code)

            val append = code == 206 && existing > 0
            val total = if (code == 206) {
                Http.totalFromContentRange(conn.getHeaderField("Content-Range"))
            } else {
                conn.contentLengthLong
            }
            if (!append && part.exists()) part.delete()

            var done = if (append) existing else 0L
            var lastReport = 0L
            onProgress(done, total)
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 300) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            if (total > 0 && done < total) throw IOException("通信が途中で切れました")
            return output(task.url, contentType, part)
        } finally {
            conn.disconnect()
        }
    }

    private fun output(url: String, contentType: String?, part: File): DownloadOutput {
        val urlExt = MediaDetector.extensionOf(url.substringBefore('#').substringBefore('?').lowercase())
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        val mimeExt = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val ext = when {
            urlExt in videoExtensions -> urlExt
            mimeExt != null -> mimeExt
            urlExt.isNotEmpty() && urlExt.length <= 5 -> urlExt
            else -> "mp4"
        }
        val outMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: mime
            ?: "application/octet-stream"
        return DownloadOutput(part, ext, outMime)
    }
}
