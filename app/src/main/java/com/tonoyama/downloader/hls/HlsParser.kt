package com.tonoyama.downloader.hls

import java.net.URL

data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val resolution: String?,
    val codecs: String?,
    val audioGroup: String?,
)

data class HlsRendition(
    val type: String,
    val groupId: String,
    val name: String?,
    val isDefault: Boolean,
    val url: String?,
)

data class HlsMaster(
    val variants: List<HlsVariant>,
    val renditions: List<HlsRendition>,
) {
    /** 画質に対応する別音声の URL（映像と音声が別配信の場合のみ） */
    fun audioUrlFor(variant: HlsVariant): String? {
        val group = variant.audioGroup ?: return null
        val candidates = renditions.filter { it.type == "AUDIO" && it.groupId == group && it.url != null }
        return (candidates.firstOrNull { it.isDefault } ?: candidates.firstOrNull())?.url
    }

    /** 画質の高い順 */
    fun sortedVariants(): List<HlsVariant> = variants.sortedByDescending { it.bandwidth }
}

data class ByteRange(val length: Long, val offset: Long) {
    val header: String get() = "bytes=$offset-${offset + length - 1}"
}

data class HlsSegment(
    val url: String,
    val duration: Double,
    val byteRange: ByteRange?,
)

data class HlsMedia(
    val segments: List<HlsSegment>,
    val initSegment: HlsSegment?,
    val encrypted: Boolean,
    val ended: Boolean,
) {
    val totalDuration: Double get() = segments.sumOf { it.duration }
}

class UnsupportedStreamException(message: String) : Exception(message)

object HlsParser {

    private val attributeRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun isPlaylist(text: String): Boolean = text.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U")

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun parseAttributes(value: String): Map<String, String> =
        attributeRegex.findAll(value).associate { it.groupValues[1] to it.groupValues[2].trim().removeSurrounding("\"") }

    fun resolve(baseUrl: String, ref: String): String = try {
        URL(URL(baseUrl), ref).toString()
    } catch (e: Exception) {
        ref
    }

    fun parseMaster(text: String, baseUrl: String): HlsMaster {
        val variants = mutableListOf<HlsVariant>()
        val renditions = mutableListOf<HlsRendition>()
        var pending: Map<String, String>? = null

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = parseAttributes(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = parseAttributes(line.substringAfter(':'))
                    renditions += HlsRendition(
                        type = a["TYPE"] ?: "",
                        groupId = a["GROUP-ID"] ?: "",
                        name = a["NAME"],
                        isDefault = a["DEFAULT"] == "YES",
                        url = a["URI"]?.let { resolve(baseUrl, it) },
                    )
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val a = pending ?: continue
                    variants += HlsVariant(
                        url = resolve(baseUrl, line),
                        bandwidth = (a["AVERAGE-BANDWIDTH"] ?: a["BANDWIDTH"])?.toLongOrNull() ?: 0L,
                        resolution = a["RESOLUTION"],
                        codecs = a["CODECS"],
                        audioGroup = a["AUDIO"],
                    )
                    pending = null
                }
            }
        }
        return HlsMaster(variants, renditions)
    }

    fun parseMedia(text: String, baseUrl: String): HlsMedia {
        val segments = mutableListOf<HlsSegment>()
        var init: HlsSegment? = null
        var encrypted = false
        var ended = false
        var duration = 0.0
        var rangeSpec: String? = null
        val lastRangeEnd = HashMap<String, Long>()

        fun parseRange(spec: String?, url: String): ByteRange? {
            if (spec.isNullOrBlank()) return null
            val length = spec.substringBefore('@').trim().toLongOrNull() ?: return null
            val offset = if (spec.contains('@')) {
                spec.substringAfter('@').trim().toLongOrNull() ?: 0L
            } else {
                lastRangeEnd[url] ?: 0L
            }
            lastRangeEnd[url] = offset + length
            return ByteRange(length, offset)
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") ->
                    duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE:") -> rangeSpec = line.substringAfter(':')
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = parseAttributes(line.substringAfter(':'))["METHOD"]
                    if (method != null && method != "NONE") encrypted = true
                }
                line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    val method = parseAttributes(line.substringAfter(':'))["METHOD"]
                    if (method != null && method != "NONE") encrypted = true
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = parseAttributes(line.substringAfter(':'))
                    val uri = a["URI"]
                    if (uri != null) {
                        val url = resolve(baseUrl, uri)
                        init = HlsSegment(url, 0.0, parseRange(a["BYTERANGE"], url))
                    }
                }
                line == "#EXT-X-ENDLIST" -> ended = true
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") ->
                    if (line.substringAfter(':').trim() == "VOD") ended = true
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val url = resolve(baseUrl, line)
                    segments += HlsSegment(url, duration, parseRange(rangeSpec, url))
                    duration = 0.0
                    rangeSpec = null
                }
            }
        }
        return HlsMedia(segments, init, encrypted, ended)
    }

    /** ダウンロードできるかを確認し、できない場合は理由つきの例外を投げる */
    fun validate(media: HlsMedia) {
        if (media.encrypted) throw UnsupportedStreamException("暗号化されたストリームには対応していません")
        if (!media.ended) throw UnsupportedStreamException("配信中のライブには対応していません（終了したアーカイブのみ対応）")
        if (media.segments.isEmpty()) throw UnsupportedStreamException("動画データが見つかりませんでした")
    }
}
