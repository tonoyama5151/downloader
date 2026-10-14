package com.tonoyama.downloader.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tonoyama.downloader.R
import com.tonoyama.downloader.Settings
import com.tonoyama.downloader.databinding.ActivityMainBinding
import com.tonoyama.downloader.detect.DetectedMedia
import com.tonoyama.downloader.detect.MediaDetector
import com.tonoyama.downloader.detect.MediaKind
import com.tonoyama.downloader.download.DownloadRepository
import com.tonoyama.downloader.download.Exporter
import com.tonoyama.downloader.download.Http
import com.tonoyama.downloader.hls.HlsMaster
import com.tonoyama.downloader.hls.HlsParser
import com.tonoyama.downloader.hls.HlsVariant
import com.tonoyama.downloader.hls.UnsupportedStreamException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val detected = LinkedHashMap<String, DetectedMedia>()
    private var pageUrl = ""
    private var pageTitle = ""
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    private val scanner = object : Runnable {
        override fun run() {
            scanVideoElements()
            handler.postDelayed(this, 3_000)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                Settings.setSaveTreeUri(this, uri)
                toast("保存先：${Settings.saveLocationLabel(this)}")
            } catch (e: Exception) {
                toast("このフォルダは保存先に設定できません")
            }
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            b.pageProgress.progress = newProgress
            b.pageProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            pageTitle = title.orEmpty()
        }

        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            b.fullscreen.addView(view, FrameLayout.LayoutParams(-1, -1))
            b.fullscreen.visibility = View.VISIBLE
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            b.fullscreen.removeView(view)
            b.fullscreen.visibility = View.GONE
            customView = null
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
        }
    }

    private val webClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // 通信を横取りはせず、動画らしいURLを記録するだけ
            val url = request.url.toString()
            val kind = MediaDetector.classify(url)
            if (kind != null) {
                val referer = request.requestHeaders.entries
                    .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                handler.post { addDetected(url, kind, referer) }
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") return false
            // アプリ連携用のリンクなどは外部に渡す
            try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
            } catch (_: Exception) {
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url != pageUrl) {
                detected.clear()
                refreshFab()
            }
            pageUrl = url
            if (!b.urlBar.hasFocus()) b.urlBar.setText(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            CookieManager.getInstance().flush()
            pageTitle = view.title.orEmpty()
            if (!b.urlBar.hasFocus()) b.urlBar.setText(url)
            Settings.setLastUrl(this@MainActivity, url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        val margin = resources.getDimensionPixelSize(R.dimen.fab_margin)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            b.content.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            b.fab.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = bars.bottom + margin
                rightMargin = bars.right + margin
            }
            insets
        }

        b.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(b.webView, true)
        b.webView.webViewClient = webClient
        b.webView.webChromeClient = chromeClient
        b.webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            onPageDownload(url, contentDisposition, mimetype)
        }

        b.urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigate(v.text.toString())
                true
            } else {
                false
            }
        }
        b.menuButton.setOnClickListener { showMenu(it) }
        b.fab.setOnClickListener { showDetected() }

        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> chromeClient.onHideCustomView()
                b.webView.canGoBack() -> b.webView.goBack()
                else -> finish()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val shared = sharedUrl(intent)
        when {
            shared != null -> navigate(shared)
            savedInstanceState != null -> b.webView.restoreState(savedInstanceState)
            else -> navigate(Settings.lastUrl(this) ?: "https://www.google.com/")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUrl(intent)?.let { navigate(it) }
    }

    override fun onResume() {
        super.onResume()
        b.webView.onResume()
        handler.post(scanner)
    }

    override fun onPause() {
        handler.removeCallbacks(scanner)
        b.webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.webView.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        b.webView.destroy()
        super.onDestroy()
    }

    // ---- ページ移動 ----

    private fun sharedUrl(intent: Intent?): String? {
        intent ?: return null
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        return Regex("""https?://\S+""").find(text)?.value
    }

    private fun navigate(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        b.webView.loadUrl(url)
        b.urlBar.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.urlBar.windowToken, 0)
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.menu.findItem(R.id.action_reset_folder).isVisible = Settings.saveTreeUri(this) != null
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_downloads -> startActivity(Intent(this, DownloadsActivity::class.java))
                R.id.action_reload -> b.webView.reload()
                R.id.action_pick_folder -> folderPicker.launch(null)
                R.id.action_reset_folder -> {
                    Settings.setSaveTreeUri(this, null)
                    toast("保存先：${Settings.saveLocationLabel(this)}")
                }
                R.id.action_about -> showAbout()
            }
            true
        }
        popup.show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("使い方と注意")
            .setMessage(
                "・ページで動画を再生すると、右下に検出した動画の件数が出ます。タップして保存できます。\n" +
                    "・対応：MP4などの動画ファイル、暗号化されていないHLS（m3u8）\n" +
                    "・非対応：DRM保護（有料配信など）、暗号化されたHLS、配信中のライブ\n" +
                    "・保存先：${Settings.saveLocationLabel(this)}\n\n" +
                    "保存してよいのは、ご自身の動画や、保存が許可されている動画です。" +
                    "違法にアップロードされたものと知りながら保存することは法律で禁止されています。" +
                    "各サイトの利用規約にも従ってください。"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ---- 動画の検出 ----

    private fun buildHeaders(referer: String?): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = b.webView.settings.userAgentString
        val ref = referer ?: pageUrl.takeIf { it.isNotEmpty() }
        if (ref != null) {
            headers["Referer"] = ref
            MediaDetector.originOf(ref)?.let { headers["Origin"] = it }
        }
        return headers
    }

    private fun addDetected(url: String, kind: MediaKind, referer: String?) {
        if (detected.containsKey(url)) return
        detected[url] = DetectedMedia(url, kind, pageUrl, pageTitle, buildHeaders(referer))
        refreshFab()
    }

    private fun refreshFab() {
        if (detected.isEmpty()) {
            b.fab.visibility = View.GONE
        } else {
            b.fab.text = "動画 ${detected.size}件"
            b.fab.visibility = View.VISIBLE
        }
    }

    /** ページ内の &lt;video&gt; 要素から直接リンクを拾う */
    private fun scanVideoElements() {
        val js = """
            (function(){
              var r=[];
              document.querySelectorAll('video, video source').forEach(function(e){
                var s=e.currentSrc||e.src;
                if(s&&s.indexOf('http')===0) r.push(s);
              });
              return JSON.stringify(r);
            })()
        """.trimIndent()
        b.webView.evaluateJavascript(js) { result ->
            try {
                val json = JSONTokener(result).nextValue() as? String ?: return@evaluateJavascript
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val src = arr.getString(i)
                    val kind = MediaDetector.classifyElementSource(src) ?: continue
                    addDetected(src, kind, pageUrl)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun showDetected() {
        val items = detected.values.sortedBy { if (it.kind == MediaKind.HLS) 0 else 1 }
        if (items.isEmpty()) return
        val labels = items.map { m ->
            val tag = if (m.kind == MediaKind.HLS) "HLS" else MediaDetector.extensionOf(
                m.url.substringBefore('?').lowercase()
            ).uppercase().ifEmpty { "動画" }
            "[$tag] ${MediaDetector.displayName(m.url)}\n${MediaDetector.hostOf(m.url)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("検出した動画")
            .setItems(labels) { _, which -> onMediaSelected(items[which]) }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun defaultName(media: DetectedMedia): String {
        val title = media.pageTitle.ifBlank { pageTitle }
        if (title.isNotBlank()) return Exporter.sanitize(title)
        return Exporter.sanitize(MediaDetector.displayName(media.url).substringBeforeLast('.'))
    }

    private fun onMediaSelected(media: DetectedMedia) {
        when (media.kind) {
            MediaKind.FILE -> askFileName(defaultName(media)) { name ->
                DownloadRepository.enqueue(MediaKind.FILE, media.url, null, media.headers, name)
                toast("ダウンロードを開始しました")
            }
            MediaKind.HLS -> inspectHls(media)
        }
    }

    // ---- HLS の解析 ----

    private fun inspectHls(media: DetectedMedia) {
        val progress = MaterialAlertDialogBuilder(this)
            .setMessage("動画の情報を確認しています…")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = Http.fetchText(media.url, media.headers)
                    if (!HlsParser.isPlaylist(text)) throw UnsupportedStreamException("HLSのプレイリストではありませんでした")
                    if (HlsParser.isMaster(text)) {
                        HlsParser.parseMaster(text, media.url)
                    } else {
                        HlsParser.validate(HlsParser.parseMedia(text, media.url))
                        null
                    }
                }
            }
            progress.dismiss()
            result.onSuccess { master ->
                if (master == null) {
                    askFileName(defaultName(media)) { name ->
                        DownloadRepository.enqueue(MediaKind.HLS, media.url, null, media.headers, name)
                        toast("ダウンロードを開始しました")
                    }
                } else {
                    chooseVariant(media, master)
                }
            }.onFailure { showError(it) }
        }
    }

    private fun chooseVariant(media: DetectedMedia, master: HlsMaster) {
        val variants = master.sortedVariants()
        if (variants.isEmpty()) {
            showError(UnsupportedStreamException("画質の情報が見つかりませんでした"))
            return
        }
        if (variants.size == 1) {
            confirmVariant(media, master, variants[0])
            return
        }
        val labels = variants.map { v ->
            val mbps = String.format(Locale.US, "%.1f Mbps", v.bandwidth / 1_000_000.0)
            val res = v.resolution ?: "解像度不明"
            val audio = if (master.audioUrlFor(v) != null) "（音声は別配信）" else ""
            "$res · $mbps$audio"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("画質を選択")
            .setItems(labels) { _, which -> confirmVariant(media, master, variants[which]) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun confirmVariant(media: DetectedMedia, master: HlsMaster, variant: HlsVariant) {
        val audioUrl = master.audioUrlFor(variant)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    HlsParser.validate(HlsParser.parseMedia(Http.fetchText(variant.url, media.headers), variant.url))
                    audioUrl?.let { HlsParser.validate(HlsParser.parseMedia(Http.fetchText(it, media.headers), it)) }
                }
            }
            result.onSuccess {
                askFileName(defaultName(media)) { name ->
                    DownloadRepository.enqueue(MediaKind.HLS, variant.url, audioUrl, media.headers, name)
                    toast("ダウンロードを開始しました")
                }
            }.onFailure { showError(it) }
        }
    }

    // ---- ページからのダウンロード（ダウンロードリンクなど） ----

    private fun onPageDownload(url: String, contentDisposition: String?, mimetype: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("このファイルは保存できません")
            return
        }
        val guessed = URLUtil.guessFileName(url, contentDisposition, mimetype)
        askFileName(Exporter.sanitize(guessed.substringBeforeLast('.'))) { name ->
            DownloadRepository.enqueue(MediaKind.FILE, url, null, buildHeaders(pageUrl), name)
            toast("ダウンロードを開始しました")
        }
    }

    // ---- 共通 ----

    private fun askFileName(default: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(default)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val pad = resources.getDimensionPixelSize(R.dimen.dialog_padding)
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("ファイル名")
            .setMessage("保存先：${Settings.saveLocationLabel(this)}")
            .setView(container)
            .setPositiveButton("保存") { _, _ -> onOk(input.text.toString().ifBlank { default }) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showError(e: Throwable) {
        val message = when (e) {
            is UnsupportedStreamException -> e.message
            is com.tonoyama.downloader.download.HttpStatusException ->
                "動画の情報を取得できませんでした（HTTP ${e.code}）。ページを再読み込みしてからお試しください。"
            else -> "動画の情報を取得できませんでした：${e.message ?: e.javaClass.simpleName}"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("保存できません")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
