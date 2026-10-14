package com.tonoyama.downloader.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.tonoyama.downloader.Settings
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 完成したファイルを、端末のDownloadフォルダ（または指定フォルダ）へ保存する */
object Exporter {

    data class Saved(val uri: Uri, val name: String)

    const val DEFAULT_SUBFOLDER = "Downloader"

    fun export(context: Context, file: File, baseName: String, ext: String, mime: String): Saved {
        val name = "${sanitize(baseName)}.$ext"
        val tree = Settings.saveTreeUri(context)
        if (tree != null) {
            try {
                return exportToTree(context, tree, file, name, mime)
            } catch (_: Exception) {
                // 指定フォルダに書けない場合（権限切れなど）は既定の場所に保存する
            }
        }
        return exportToDownloads(context, file, name, mime)
    }

    private fun exportToDownloads(context: Context, file: File, name: String, mime: String): Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_SUBFOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("保存先を作成できませんでした")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 1024 * 1024) }
            } ?: throw IOException("保存先を開けませんでした")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        val actualName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: name
        return Saved(uri, actualName)
    }

    private fun exportToTree(context: Context, tree: Uri, file: File, name: String, mime: String): Saved {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: throw IOException("保存先フォルダを開けませんでした")
        val doc = dir.createFile(mime, name) ?: throw IOException("ファイルを作成できませんでした")
        try {
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 1024 * 1024) }
            } ?: throw IOException("保存先を開けませんでした")
        } catch (e: Exception) {
            doc.delete()
            throw e
        }
        return Saved(doc.uri, doc.name ?: name)
    }

    /** 保存済みファイルを削除する */
    fun delete(context: Context, uri: Uri): Boolean = try {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    } catch (_: Exception) {
        false
    }

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_").trim().trim('.').take(100)
        return cleaned.ifBlank { defaultName() }
    }

    fun defaultName(): String = "video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
