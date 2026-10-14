package com.tonoyama.downloader.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 再エンコードせずに入れ物だけを MP4 に詰め替える（画質は変わらない）。
 * 映像と音声が別ファイルの場合は1本にまとめる。
 */
object Remuxer {

    private class Input(val extractor: MediaExtractor, val trackMap: MutableMap<Int, Int> = mutableMapOf())

    fun remux(inputs: List<File>, output: File) {
        output.delete()
        val extractors = inputs.map { file -> MediaExtractor().apply { setDataSource(file.path) } }
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var success = false
        try {
            val sources = extractors.map { Input(it) }

            // 映像：最初に見つかったもの / 音声：別ファイルがあればそちらを優先
            var videoChosen = false
            for (src in sources) {
                if (videoChosen) break
                for (i in 0 until src.extractor.trackCount) {
                    if (mimeOf(src.extractor, i).startsWith("video/")) {
                        src.trackMap[i] = -1
                        videoChosen = true
                        break
                    }
                }
            }
            var audioChosen = false
            for (src in sources.reversed()) {
                if (audioChosen) break
                for (i in 0 until src.extractor.trackCount) {
                    if (mimeOf(src.extractor, i).startsWith("audio/")) {
                        src.trackMap[i] = -1
                        audioChosen = true
                        break
                    }
                }
            }
            if (!videoChosen && !audioChosen) throw IOException("映像・音声が見つかりませんでした")

            for (src in sources) {
                for (track in src.trackMap.keys.toList()) {
                    src.extractor.selectTrack(track)
                    src.trackMap[track] = muxer.addTrack(src.extractor.getTrackFormat(track))
                }
            }
            val active = sources.filter { it.trackMap.isNotEmpty() }

            // 開始時刻のずれをそろえる
            val base = active.mapNotNull { s -> s.extractor.sampleTime.takeIf { it >= 0 } }.minOrNull() ?: 0L

            muxer.start()
            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val src = active.filter { it.extractor.sampleTime >= 0 }.minByOrNull { it.extractor.sampleTime } ?: break
                val ex = src.extractor
                val dst = src.trackMap[ex.sampleTrackIndex]
                if (dst == null) {
                    ex.advance()
                    continue
                }
                val size = ex.readSampleData(buffer, 0)
                if (size >= 0) {
                    val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    info.set(0, size, (ex.sampleTime - base).coerceAtLeast(0), flags)
                    muxer.writeSampleData(dst, buffer, info)
                }
                ex.advance()
            }
            muxer.stop()
            success = true
        } finally {
            try {
                muxer.release()
            } catch (_: Exception) {
            }
            extractors.forEach { it.release() }
            if (!success) output.delete()
        }
    }

    private fun mimeOf(extractor: MediaExtractor, index: Int): String =
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: ""
}
