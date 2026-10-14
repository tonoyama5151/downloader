package com.tonoyama.downloader.download

import com.tonoyama.downloader.detect.MediaKind
import org.json.JSONObject
import java.util.Locale

enum class TaskStatus { QUEUED, RUNNING, MERGING, PAUSED, COMPLETED, FAILED }

data class DownloadTask(
    val id: String,
    val kind: MediaKind,
    val url: String,
    val audioUrl: String?,
    val headers: Map<String, String>,
    val fileName: String,
    val status: TaskStatus,
    val createdAt: Long,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val segmentsDone: Int = 0,
    val segmentsTotal: Int = 0,
    val error: String? = null,
    val outputUri: String? = null,
    val outputName: String? = null,
) {
    val isActive: Boolean
        get() = status == TaskStatus.QUEUED || status == TaskStatus.RUNNING || status == TaskStatus.MERGING

    /** 0〜100。分からない場合は null */
    val progressPercent: Int?
        get() = when {
            status == TaskStatus.COMPLETED -> 100
            kind == MediaKind.FILE && totalBytes > 0 -> (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
            kind == MediaKind.HLS && segmentsTotal > 0 -> (segmentsDone * 100 / segmentsTotal).coerceIn(0, 100)
            else -> null
        }

    val title: String get() = outputName ?: fileName

    fun statusLabel(): String = when (status) {
        TaskStatus.QUEUED -> "待機中"
        TaskStatus.RUNNING -> when {
            kind == MediaKind.HLS && segmentsTotal > 0 ->
                "ダウンロード中 $segmentsDone/$segmentsTotal · ${formatBytes(downloadedBytes)}"
            totalBytes > 0 -> "ダウンロード中 ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
            downloadedBytes > 0 -> "ダウンロード中 ${formatBytes(downloadedBytes)}"
            else -> "接続中…"
        }
        TaskStatus.MERGING -> "結合・保存中…"
        TaskStatus.PAUSED -> "一時停止中" + if (downloadedBytes > 0) "（${formatBytes(downloadedBytes)}）" else ""
        TaskStatus.COMPLETED -> "保存済み"
        TaskStatus.FAILED -> "失敗：" + (error ?: "不明なエラー")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name)
        put("url", url)
        put("audioUrl", audioUrl ?: JSONObject.NULL)
        put("headers", JSONObject(headers))
        put("fileName", fileName)
        put("status", status.name)
        put("createdAt", createdAt)
        put("downloadedBytes", downloadedBytes)
        put("totalBytes", totalBytes)
        put("segmentsDone", segmentsDone)
        put("segmentsTotal", segmentsTotal)
        put("error", error ?: JSONObject.NULL)
        put("outputUri", outputUri ?: JSONObject.NULL)
        put("outputName", outputName ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadTask {
            val h = o.optJSONObject("headers")
            val headers = mutableMapOf<String, String>()
            h?.keys()?.forEach { k -> headers[k] = h.getString(k) }
            fun optStr(key: String): String? = if (o.isNull(key)) null else o.optString(key)
            return DownloadTask(
                id = o.getString("id"),
                kind = MediaKind.valueOf(o.getString("kind")),
                url = o.getString("url"),
                audioUrl = optStr("audioUrl"),
                headers = headers,
                fileName = o.getString("fileName"),
                status = TaskStatus.valueOf(o.getString("status")),
                createdAt = o.optLong("createdAt"),
                downloadedBytes = o.optLong("downloadedBytes"),
                totalBytes = o.optLong("totalBytes", -1),
                segmentsDone = o.optInt("segmentsDone"),
                segmentsTotal = o.optInt("segmentsTotal"),
                error = optStr("error"),
                outputUri = optStr("outputUri"),
                outputName = optStr("outputName"),
            )
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
