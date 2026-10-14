package com.tonoyama.downloader.download

import com.tonoyama.downloader.hls.HlsMedia
import com.tonoyama.downloader.hls.HlsParser
import com.tonoyama.downloader.hls.HlsSegment
import com.tonoyama.downloader.hls.UnsupportedStreamException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * HLS（m3u8）を分割ファイルごとにダウンロードし、1本の動画にまとめる。
 * 暗号化されたストリームとライブ配信は対象外。
 */
object HlsDownloader {

    private const val PARALLEL = 4

    private class Track(val prefix: String, val media: HlsMedia)

    /** 結合したファイルの拡張子を記録しておく目印ファイル */
    private fun marker(dir: File, prefix: String) = File(dir, "$prefix.ok")

    suspend fun fetchMedia(url: String, headers: Map<String, String>): Pair<String, HlsMedia> {
        var playlistUrl = url
        var text = Http.fetchText(playlistUrl, headers)
        if (!HlsParser.isPlaylist(text)) throw UnsupportedStreamException("HLSのプレイリストではありませんでした")
        if (HlsParser.isMaster(text)) {
            // 画質を指定されていない場合は最高画質を選ぶ
            val best = HlsParser.parseMaster(text, playlistUrl).sortedVariants().firstOrNull()
                ?: throw UnsupportedStreamException("画質の情報が見つかりませんでした")
            playlistUrl = best.url
            text = Http.fetchText(playlistUrl, headers)
        }
        coroutineContext.ensureActive()
        val media = HlsParser.parseMedia(text, playlistUrl)
        HlsParser.validate(media)
        return playlistUrl to media
    }

    suspend fun run(
        task: DownloadTask,
        dir: File,
        onProgress: (segDone: Int, segTotal: Int, bytes: Long) -> Unit,
        onMerging: () -> Unit,
    ): DownloadOutput {
        dir.mkdirs()
        val tracks = mutableListOf<Track>()
        tracks += Track("video", fetchMedia(task.url, task.headers).second)
        task.audioUrl?.let { tracks += Track("audio", fetchMedia(it, task.headers).second) }

        val total = tracks.sumOf { t ->
            if (marker(dir, t.prefix).exists()) 0 else t.media.segments.size + (if (t.media.initSegment != null) 1 else 0)
        }
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0)
        var lastReport = 0L
        val report: () -> Unit = {
            val now = System.currentTimeMillis()
            if (now - lastReport > 300) {
                lastReport = now
                onProgress(done.get(), total, bytes.get())
            }
        }

        val joined = mutableListOf<Pair<File, String>>()
        for (track in tracks) {
            val mark = marker(dir, track.prefix)
            if (mark.exists()) {
                val ext = mark.readText().trim()
                joined += File(dir, "${track.prefix}.$ext") to ext
                continue
            }
            val segDir = File(dir, track.prefix).apply { mkdirs() }
            val items = buildList {
                track.media.initSegment?.let { add(it) }
                addAll(track.media.segments)
            }
            downloadAll(items, segDir, task.headers, done, bytes, report)
            onProgress(done.get(), total, bytes.get())

            val ext = when {
                track.media.initSegment != null -> "mp4"
                else -> sniffExtension(File(segDir, name(0)))
            }
            val out = File(dir, "${track.prefix}.$ext")
            FileOutputStream(out).use { os ->
                for (i in items.indices) {
                    coroutineContext.ensureActive()
                    File(segDir, name(i)).inputStream().use { it.copyTo(os, 256 * 1024) }
                }
            }
            mark.writeText(ext)
            segDir.deleteRecursively()
            joined += out to ext
        }

        onMerging()
        val output = File(dir, "output.mp4")
        return try {
            Remuxer.remux(joined.map { it.first }, output)
            DownloadOutput(output, "mp4", "video/mp4")
        } catch (e: Exception) {
            output.delete()
            if (joined.size == 1) {
                // MP4に変換できなかった場合は、結合したファイルをそのまま保存する
                val (file, ext) = joined[0]
                DownloadOutput(file, ext, mimeOf(ext))
            } else {
                throw IOException("映像と音声の結合に失敗しました", e)
            }
        }
    }

    private fun name(index: Int) = String.format(java.util.Locale.US, "%06d.seg", index)

    private suspend fun downloadAll(
        items: List<HlsSegment>,
        segDir: File,
        headers: Map<String, String>,
        done: AtomicInteger,
        bytes: AtomicLong,
        report: () -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(PARALLEL)
        items.mapIndexed { i, seg ->
            async {
                semaphore.withPermit {
                    val file = File(segDir, name(i))
                    if (!file.exists()) downloadSegment(seg, file, headers, bytes)
                    done.incrementAndGet()
                    report()
                }
            }
        }.awaitAll()
    }

    private suspend fun downloadSegment(
        seg: HlsSegment,
        file: File,
        headers: Map<String, String>,
        bytes: AtomicLong,
    ) {
        val tmp = File(file.path + ".tmp")
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            var written = 0L
            try {
                val conn = Http.open(seg.url, headers, seg.byteRange?.header)
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw HttpStatusException(code)
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(64 * 1024)
                            if (seg.byteRange != null && code == 200) {
                                // 範囲指定が無視された場合は、必要な部分だけ切り出す
                                skipFully(input, seg.byteRange.offset)
                                var remaining = seg.byteRange.length
                                while (remaining > 0) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    remaining -= n
                                    written += n
                                    bytes.addAndGet(n.toLong())
                                }
                            } else {
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    written += n
                                    bytes.addAndGet(n.toLong())
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (!tmp.renameTo(file)) throw IOException("一時ファイルの保存に失敗しました")
                return
            } catch (e: IOException) {
                bytes.addAndGet(-written)
                tmp.delete()
                if (e is HttpStatusException && e.isPermanent) throw e
                attempt++
                if (attempt >= 4) throw e
                delay(1_500L * attempt)
            }
        }
    }

    private fun skipFully(input: java.io.InputStream, count: Long) {
        var left = count
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("データが途中で終わっています")
            left -= n
        }
    }

    /** ファイル先頭のバイト列から形式を判定する */
    fun sniffExtension(file: File): String {
        val head = ByteArray(12)
        val n = try {
            file.inputStream().use { it.read(head) }
        } catch (e: Exception) {
            -1
        }
        if (n < 4) return "ts"
        val box = String(head, 4, 4, Charsets.ISO_8859_1)
        return when {
            head[0] == 0x47.toByte() -> "ts"
            box == "ftyp" || box == "styp" || box == "moof" || box == "sidx" -> "mp4"
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> "aac"
            head[0] == 0xFF.toByte() && (head[1].toInt() and 0xF6) == 0xF0 -> "aac"
            else -> "ts"
        }
    }

    fun mimeOf(ext: String): String = when (ext) {
        "mp4" -> "video/mp4"
        "aac" -> "audio/aac"
        "ts" -> "video/mp2t"
        else -> "application/octet-stream"
    }
}
