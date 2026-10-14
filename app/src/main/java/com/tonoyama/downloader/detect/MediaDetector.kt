package com.tonoyama.downloader.detect

import java.net.URL
import java.net.URLDecoder

enum class MediaKind { FILE, HLS }

/** ページ内で見つかった動画の候補 */
data class DetectedMedia(
    val url: String,
    val kind: MediaKind,
    val pageUrl: String,
    val pageTitle: String,
    val headers: Map<String, String>,
)

object MediaDetector {

    private val fileExtensions = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp")

    /**
     * 通信URLから動画かどうかを判定する。
     * MP4 などの単体ファイルは FILE、m3u8 は HLS。それ以外（分割ファイルや画像など）は null。
     */
    fun classify(url: String): MediaKind? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val noFragment = url.substringBefore('#')
        val path = noFragment.substringBefore('?').lowercase()
        val query = noFragment.substringAfter('?', "").lowercase()
        val ext = extensionOf(path)
        return when {
            ext == "m3u8" -> MediaKind.HLS
            ext in fileExtensions -> MediaKind.FILE
            ext.isEmpty() && query.contains("m3u8") -> MediaKind.HLS
            else -> null
        }
    }

    /** &lt;video&gt; 要素の src 用。拡張子が分からなくても動画として扱う */
    fun classifyElementSource(url: String): MediaKind? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return classify(url) ?: MediaKind.FILE
    }

    fun extensionOf(path: String): String {
        val last = path.substringAfterLast('/')
        if (!last.contains('.')) return ""
        return last.substringAfterLast('.').lowercase()
    }

    /** 一覧表示用のファイル名 */
    fun displayName(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        val last = path.trimEnd('/').substringAfterLast('/')
        val decoded = try {
            URLDecoder.decode(last, "UTF-8")
        } catch (e: Exception) {
            last
        }
        return decoded.ifBlank { hostOf(url) }
    }

    fun hostOf(url: String): String = try {
        URL(url).host
    } catch (e: Exception) {
        ""
    }

    fun originOf(url: String): String? = try {
        val u = URL(url)
        val port = if (u.port == -1) "" else ":${u.port}"
        "${u.protocol}://${u.host}$port"
    } catch (e: Exception) {
        null
    }
}
