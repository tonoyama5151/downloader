package com.tonoyama.downloader.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.tonoyama.downloader.detect.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** ダウンロード一覧の管理と実行（同時実行は2件まで） */
object DownloadRepository {

    private const val MAX_PARALLEL = 2

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val saveLock = Any()
    private val jobs = HashMap<String, Job>()
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    @Volatile
    private var lastSave = 0L

    fun init(context: Context) {
        app = context.applicationContext
        load()
    }

    private val storeFile: File get() = File(app.filesDir, "tasks.json")

    private fun workDir(id: String) = File(app.filesDir, "work/$id")

    private fun load() {
        val loaded = try {
            if (!storeFile.exists()) emptyList() else {
                val arr = JSONArray(storeFile.readText())
                (0 until arr.length()).mapNotNull { i ->
                    try {
                        DownloadTask.fromJson(arr.getJSONObject(i))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        // アプリ終了で止まったものは「一時停止」として残す（再開できる）
        _tasks.value = loaded.map { if (it.isActive) it.copy(status = TaskStatus.PAUSED) else it }
    }

    private fun save() {
        synchronized(saveLock) {
            try {
                val arr = JSONArray()
                _tasks.value.forEach { arr.put(it.toJson()) }
                val tmp = File(storeFile.path + ".tmp")
                tmp.writeText(arr.toString())
                tmp.renameTo(storeFile)
            } catch (_: Exception) {
            }
        }
    }

    fun get(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    private fun update(id: String, persist: Boolean = false, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
        val now = System.currentTimeMillis()
        if (persist || now - lastSave > 3_000) {
            lastSave = now
            save()
        }
    }

    fun enqueue(kind: MediaKind, url: String, audioUrl: String?, headers: Map<String, String>, fileName: String) {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            kind = kind,
            url = url,
            audioUrl = audioUrl,
            headers = headers,
            fileName = Exporter.sanitize(fileName),
            status = TaskStatus.QUEUED,
            createdAt = System.currentTimeMillis(),
        )
        _tasks.update { listOf(task) + it }
        save()
        startService()
        schedule()
    }

    fun pause(id: String) {
        synchronized(lock) {
            val t = get(id) ?: return
            if (t.status != TaskStatus.QUEUED && t.status != TaskStatus.RUNNING) return
            update(id, persist = true) { it.copy(status = TaskStatus.PAUSED) }
            jobs[id]?.cancel()
        }
    }

    fun resume(id: String) {
        val t = get(id) ?: return
        if (t.status != TaskStatus.PAUSED && t.status != TaskStatus.FAILED) return
        update(id, persist = true) { it.copy(status = TaskStatus.QUEUED, error = null) }
        startService()
        schedule()
    }

    /** 一覧から削除する。deleteFile=true なら保存済みファイルも消す */
    fun remove(id: String, deleteFile: Boolean) {
        val task = get(id) ?: return
        val job: Job?
        synchronized(lock) {
            job = jobs.remove(id)
            _tasks.update { list -> list.filterNot { it.id == id } }
        }
        save()
        scope.launch {
            job?.cancelAndJoin()
            workDir(id).deleteRecursively()
            if (deleteFile) task.outputUri?.let { Exporter.delete(app, Uri.parse(it)) }
        }
        schedule()
    }

    fun clearCompleted() {
        _tasks.update { list -> list.filterNot { it.status == TaskStatus.COMPLETED } }
        save()
    }

    private fun startService() {
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
    }

    /** 時間制限などでサービスが止められるときに、実行中のものを一時停止にする */
    fun pauseAll() {
        _tasks.value.filter { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING }
            .forEach { pause(it.id) }
    }

    private fun schedule() {
        synchronized(lock) {
            val slots = MAX_PARALLEL - jobs.size
            if (slots <= 0) return
            val next = _tasks.value
                .filter { it.status == TaskStatus.QUEUED && !jobs.containsKey(it.id) }
                .sortedBy { it.createdAt }
                .take(slots)
            for (t in next) {
                update(t.id, persist = true) { it.copy(status = TaskStatus.RUNNING, error = null) }
                val job = scope.launch(start = CoroutineStart.LAZY) { runTask(t.id) }
                jobs[t.id] = job
                job.invokeOnCompletion {
                    synchronized(lock) {
                        if (jobs[t.id] === job) jobs.remove(t.id)
                    }
                    schedule()
                }
                job.start()
            }
        }
    }

    private suspend fun runTask(id: String) {
        val task = get(id) ?: return
        val dir = workDir(id)
        try {
            val output = when (task.kind) {
                MediaKind.FILE -> FileDownloader.run(task, dir) { done, total ->
                    update(id) { it.copy(downloadedBytes = done, totalBytes = total) }
                }
                MediaKind.HLS -> HlsDownloader.run(
                    task,
                    dir,
                    onProgress = { segDone, segTotal, bytes ->
                        update(id) { it.copy(segmentsDone = segDone, segmentsTotal = segTotal, downloadedBytes = bytes) }
                    },
                    onMerging = { update(id, persist = true) { it.copy(status = TaskStatus.MERGING) } },
                )
            }
            if (get(id)?.status == TaskStatus.PAUSED) return
            update(id, persist = true) { it.copy(status = TaskStatus.MERGING) }
            val saved = Exporter.export(app, output.file, task.fileName, output.ext, output.mime)
            dir.deleteRecursively()
            update(id, persist = true) {
                it.copy(status = TaskStatus.COMPLETED, outputUri = saved.uri.toString(), outputName = saved.name)
            }
            get(id)?.let { Notifications.finished(app, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val current = get(id) ?: return
            if (current.status == TaskStatus.PAUSED) return
            update(id, persist = true) { it.copy(status = TaskStatus.FAILED, error = friendlyMessage(e)) }
            get(id)?.let { Notifications.finished(app, it) }
        }
    }

    private fun friendlyMessage(e: Exception): String = when (e) {
        is HttpStatusException -> when (e.code) {
            401, 403 -> "アクセスが拒否されました（HTTP ${e.code}）。ページを開き直してから再度お試しください"
            404 -> "動画が見つかりませんでした（HTTP 404）"
            else -> e.message ?: "通信エラー"
        }
        is java.net.UnknownHostException -> "サーバーに接続できませんでした"
        is java.net.SocketTimeoutException -> "通信がタイムアウトしました"
        else -> e.message ?: e.javaClass.simpleName
    }
}
