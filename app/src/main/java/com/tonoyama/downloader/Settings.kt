package com.tonoyama.downloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object Settings {
    private const val PREFS = "settings"
    private const val KEY_TREE = "save_tree_uri"
    private const val KEY_LAST_URL = "last_url"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveTreeUri(context: Context): Uri? = prefs(context).getString(KEY_TREE, null)?.let(Uri::parse)

    fun setSaveTreeUri(context: Context, uri: Uri?) {
        prefs(context).edit().apply {
            if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString())
        }.apply()
    }

    /** 画面表示用の保存先 */
    fun saveLocationLabel(context: Context): String {
        val tree = saveTreeUri(context) ?: return "Download/Downloader（既定）"
        return DocumentFile.fromTreeUri(context, tree)?.name ?: tree.toString()
    }

    fun lastUrl(context: Context): String? = prefs(context).getString(KEY_LAST_URL, null)

    fun setLastUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_LAST_URL, url).apply()
    }
}
