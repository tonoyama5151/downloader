package com.tonoyama.downloader.download

import android.webkit.CookieManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpStatusException(val code: Int) : IOException("サーバーがエラーを返しました（HTTP $code）") {
    /** 再試行しても無駄なエラーかどうか */
    val isPermanent: Boolean get() = code in 400..499 && code != 408 && code != 429
}

object Http {

    /**
     * ブラウザで開いていたときと同じ条件（User-Agent・Referer・ログイン中のCookie）で接続する。
     */
    fun open(url: String, headers: Map<String, String>, range: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
        } catch (_: Exception) {
        }
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }

    fun fetchText(url: String, headers: Map<String, String>): String {
        val conn = open(url, headers)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /** "bytes 100-199/1000" から全体サイズを取り出す */
    fun totalFromContentRange(value: String?): Long {
        if (value == null) return -1
        return value.substringAfter('/', "").trim().toLongOrNull() ?: -1
    }
}
