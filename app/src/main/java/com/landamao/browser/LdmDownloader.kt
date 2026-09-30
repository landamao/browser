package com.landamao.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * LDM 自研下载引擎(纯 HttpURLConnection,零第三方库):
 * - 断点续传:内容先写应用缓存区的 .part 临时文件;暂停/中断后继续时带
 *   Range: bytes=offset- 和 If-Range(强 ETag 优先,否则 Last-Modified)让服务器
 *   校验文件没变 —— 206 从断点追加,200 说明服务器不支持续传或文件已变,
 *   推倒重来;416 且断点已下满按完成处理。没有校验器就不续传(防止服务器
 *   文件已变时把两份内容拼成损坏文件)。
 * - 并发:最多同时 3 个在传,其余按提交顺序排队(等待中)。
 * - 重试:网络错误自动退避重试(2s/4s/8s),期间有进展就重新计数;
 *   4xx(除 408/429)是确定性失败不重试。失败保留断点,可手动继续。
 * - 状态持久化:任务记录写 SharedPreferences,进程被杀后重启时把
 *   下载中/等待中的任务标为已暂停(主流浏览器的「已中断」语义),可继续。
 * - 落盘:完成后提交到目标位置 —— 用户在设置里选的目录(SAF),未选则
 *   Android 10+ 写入 MediaStore「下载」集合,更早版本写应用专属下载目录。
 * - 通知:进度与完成通知(通知权限未授予时静默,只在下载管理页显示进度)。
 *
 * 并发模型:所有状态迁移(状态字段落库/启动/收尾)都集中在引擎锁内;
 * worker 线程只写 volatile 的进度字段(done/speed),结构性变更不碰,
 * 避免与 UI 线程竞态。锁内不做任何网络 I/O。
 */
internal class LdmDownloader private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: LdmDownloader? = null

        fun get(context: Context): LdmDownloader =
            instance ?: synchronized(this) {
                instance ?: LdmDownloader(context.applicationContext).also { instance = it }
            }

        /** 引擎自己的任务持久化文件(独立于浏览器主配置,便于整体清理) */
        private const val PREF_ENGINE = "ldm_downloader"
        private const val PREF_TASKS = "tasks"

        /** 设置的下载目录(SAF tree uri);存浏览器主配置,与其它设置同文件 */
        internal const val PREF_DL_DIR = "browser_dl_dir"

        /** 下载通知点击跳转:打开浏览器并聚焦下载管理页 */
        internal const val EXTRA_OPEN_DOWNLOADS = "ldm_open_downloads"

        private const val MAX_ACTIVE = 3
        private const val RETRY_TIMES = 3
        private val RETRY_BACKOFF_MS = longArrayOf(2000, 4000, 8000)
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 30000
        private const val MAX_REDIRECTS = 10
        private const val BUFFER_SIZE = 64 * 1024
        private const val NOTIFY_MIN_INTERVAL_MS = 1000L
        private const val FIRE_MIN_INTERVAL_MS = 400L
        private const val PERSIST_MIN_INTERVAL_MS = 3000L
        private const val CHANNEL_ID = "ldm_downloads"
        private const val TAG = "LdmDownloader"

        internal const val ST_WAITING = 0
        internal const val ST_RUNNING = 1
        internal const val ST_PAUSED = 2
        internal const val ST_FAILED = 3
        internal const val ST_DONE = 4
    }

    /** 下载完成后的落盘结果:内容地址 + 展示路径 + 最终文件名(去重后) */
    internal class SaveResult(
        val uri: String,   // content 地址(MediaStore/SAF);空串表示走 path
        val path: String,  // 展示用文件路径(MediaStore/SAF 为近真实路径)
        val name: String,
        val mime: String,
        val size: Long
    )

    /** 任务快照:下载管理页渲染一行 + 打开/复制所需的最小字段 */
    internal class Snap(
        val id: Long,
        val name: String,
        val kind: String,    // waiting|running|paused|failed|done
        val status: String,  // 状态文案(含进度/速度/大小)
        val pct: Int,        // 0-100;大小未知为 -1
        val time: Long,
        val url: String,     // 来源链接
        val path: String?,   // 已完成文件的展示路径(供复制)
        val uri: String?,    // 已完成文件的内容地址(供打开;空串=走 path)
        val mime: String?    // 已完成文件的类型(供打开)
    )

    /** 一个下载任务;带锁字段只允许在 [lock] 内改动 */
    private class Task(
        val id: Long,
        var url: String,          // 当前地址(重定向逐跳更新,最终为落地地址)
        val originalUrl: String,  // 用户点击的原始链接
        var name: String,
        var mime: String,
        val userAgent: String?,
        val referer: String?,
        var treeUri: String?,     // 下载目录快照(null = 默认);提交时用,中途改设置不影响进行中的任务
        var total: Long,          // 总字节;-1 未知
        @Volatile var done: Long, // 已下载字节(.part 文件逻辑长度)
        @Volatile var speed: Long,// 平滑速度 B/s
        var etag: String?,        // 续传校验器(强 ETag)
        var lastModified: String?,// 续传校验器(HTTP-date)
        var status: Int,
        val createdAt: Long,
        var doneAt: Long,
        var finalUri: String,     // 完成后:content 地址
        var finalPath: String,    // 完成后:展示路径
        var finalName: String,    // 完成后:去重后的最终文件名
        @Volatile var pauseRequested: Boolean,
        var pendingDelete: Boolean,
        var pendingDeleteKeepFile: Boolean, // 删除时是否保留已落盘文件(仅记记录)
        var active: Boolean       // worker 线程是否在跑
    )

    /** 主动停止(暂停/删除)与网络错误区分开,避免被当成失败去重试 */
    private class TaskStopped : RuntimeException()

    /** 4xx 确定性失败,不值得重试 */
    private class HttpError(val code: Int) : IOException("HTTP $code")

    private val lock = Any()
    private val tasks = ArrayList<Task>()       // lock 内改动;提交顺序即等待顺序
    private var runningCount = 0                // lock 内改动
    private val main = Handler(Looper.getMainLooper())
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val pool = Executors.newFixedThreadPool(MAX_ACTIVE) { r ->
        Thread(r, "ldm-download").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val lastFire = AtomicLong(0)
    private val lastNotif = HashMap<Long, Long>() // 每任务上次进度通知时间,锁内访问

    /** 状态/进度变化回调(任意线程调用,引擎已节流);UI 侧自行切主线程 */
    @Volatile
    var onUpdate: (() -> Unit)? = null

    init {
        synchronized(lock) { loadLocked() }
        cleanupStaleParts()
        ensureChannel()
    }

    // ---------- 对 UI 的接口 ----------

    /**
     * 提交一个 http(s) 下载。Cookie 不在此捕获 —— 每次连接(含重定向逐跳、
     * 断点续传重连)实时从 CookieManager 取,会话期间登录态变化也能跟上。
     */
    fun enqueue(url: String, userAgent: String?, referer: String?, name: String, mime: String?): Long {
        val prefs = context.getSharedPreferences(BrowserActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val treeUri = prefs.getString(PREF_DL_DIR, null)
        val id: Long
        synchronized(lock) {
            var next = System.currentTimeMillis()
            while (tasks.any { it.id == next }) next++
            id = next
            tasks.add(
                Task(
                    id = id,
                    url = url,
                    originalUrl = url,
                    name = sanitizeFileName(name),
                    mime = mime.orEmpty(),
                    userAgent = userAgent,
                    referer = referer,
                    treeUri = treeUri,
                    total = -1,
                    done = 0,
                    speed = 0,
                    etag = null,
                    lastModified = null,
                    status = ST_WAITING,
                    createdAt = System.currentTimeMillis(),
                    doneAt = 0,
                    finalUri = "",
                    finalPath = "",
                    finalName = "",
                    pauseRequested = false,
                    pendingDelete = false,
                    pendingDeleteKeepFile = false,
                    active = false
                )
            )
            tryStartLocked(tasks.last())
            persistLocked()
        }
        fireChanged(force = true)
        return id
    }

    /** 暂停:在传的由 worker 收尾成已暂停;排队中的直接改状态 */
    fun pause(id: Long) {
        synchronized(lock) {
            val t = tasks.firstOrNull { it.id == id } ?: return
            when {
                !t.active && t.status == ST_WAITING -> {
                    t.status = ST_PAUSED
                    persistLocked()
                }
                t.active && (t.status == ST_RUNNING || t.status == ST_WAITING) ->
                    t.pauseRequested = true
                else -> return
            }
        }
        fireChanged(force = true)
    }

    /** 继续:已暂停/失败的重新排队(断点续传);排队满则等待空位 */
    fun resume(id: Long) {
        synchronized(lock) {
            val t = tasks.firstOrNull { it.id == id } ?: return
            if (t.status != ST_PAUSED && t.status != ST_FAILED) return
            t.pauseRequested = false
            t.status = ST_WAITING
            t.speed = 0
            persistLocked()
            tryStartLocked(t)
        }
        fireChanged(force = true)
    }

    /**
     * 删除:去掉任务记录,.part 临时文件一并清掉;已落盘文件按 [deleteFile]
     * 决定去留(在传任务还没有落盘文件,勾选与否只影响完成后才会有的文件)。
     * 在传的置删除标记,worker 收尾时清理;已完成的直接清理。
     */
    fun delete(id: Long, deleteFile: Boolean = true) {
        var removed: Task?
        synchronized(lock) {
            removed = tasks.firstOrNull { it.id == id } ?: return
            tasks.remove(removed)
            if (removed!!.active) {
                removed!!.pendingDelete = true
                removed!!.pendingDeleteKeepFile = !deleteFile
                removed!!.pauseRequested = true
            } else {
                removed!!.deleteFiles(keepCommitted = !deleteFile)
                cancelNotif(removed!!)
            }
            persistLocked()
        }
        fireChanged(force = true)
    }

    /** 全部任务快照(锁内拷贝),供下载管理页渲染 */
    fun snapshot(): List<Snap> = synchronized(lock) {
        tasks.map { t ->
            val done = t.status == ST_DONE
            Snap(
                id = t.id,
                name = if (done && t.finalName.isNotBlank()) t.finalName else t.name,
                kind = kindOf(t.status),
                status = statusText(t),
                // 进度条只在还有进展可看时画;完成后由状态文案给出「100%」
                pct = if (t.status != ST_DONE && t.total > 0) {
                    ((t.done * 100) / t.total).toInt().coerceIn(0, 100)
                } else {
                    -1
                },
                time = t.createdAt,
                url = t.originalUrl,
                path = if (done) t.finalPath.takeIf { it.isNotBlank() } else null,
                uri = if (done) t.finalUri.takeIf { it.isNotBlank() } else null,
                mime = if (done) t.mime.takeIf { it.isNotBlank() } else null
            )
        }
    }

    // ---------- 状态机 ----------

    private fun kindOf(status: Int): String = when (status) {
        ST_WAITING -> "waiting"
        ST_RUNNING -> "running"
        ST_PAUSED -> "paused"
        ST_FAILED -> "failed"
        else -> "done"
    }

    /** 状态文案:下载中带百分比/大小/速度,与系统下载条目风格一致 */
    private fun statusText(t: Task): String {
        val sizePart = when {
            t.total > 0 -> formatBytes(t.done) + "/" + formatBytes(t.total)
            t.done > 0 -> formatBytes(t.done)
            else -> ""
        }
        val tail = when (t.status) {
            ST_RUNNING, ST_PAUSED -> {
                val parts = mutableListOf<String>()
                if (t.total > 0) parts.add(((t.done * 100) / t.total).toInt().coerceIn(0, 100).toString() + "%")
                if (sizePart.isNotEmpty()) parts.add(sizePart)
                if (t.status == ST_RUNNING && t.speed > 0) parts.add(formatBytes(t.speed) + "/s")
                parts.joinToString("·")
            }
            ST_DONE -> {
                val size = formatBytes(if (t.total > 0) t.total else t.done)
                if (size.isEmpty()) "100%" else "100%·$size"
            }
            else -> ""
        }
        val base = context.getString(
            when (t.status) {
                ST_WAITING -> R.string.browser_dl_status_waiting
                ST_RUNNING -> R.string.browser_dl_status_running
                ST_PAUSED -> R.string.browser_dl_status_paused
                ST_FAILED -> R.string.browser_dl_status_failed
                else -> R.string.browser_dl_status_done
            }
        )
        return if (tail.isEmpty()) base else "$base·$tail"
    }

    /** 有空位就把等待中的任务投给 worker(引擎的并发上限在此收口) */
    private fun tryStartLocked(task: Task) {
        if (task.active || runningCount >= MAX_ACTIVE) return
        if (task.status != ST_WAITING || task.pendingDelete) return
        task.active = true
        runningCount++
        pool.execute { runTask(task) }
    }

    /** worker 主循环:传输 → 重试退避 → 提交落盘;暂停/删除随时收敛退出 */
    private fun runTask(task: Task) {
        try {
            var attempt = 0
            var lastProgress = 0L
            var resetOnce = false
            while (true) {
                if (checkStop(task)) return
                synchronized(lock) {
                    task.status = ST_RUNNING
                    persistLocked()
                }
                fireChanged(force = true)
                try {
                    transfer(task, resetOnce)
                    resetOnce = false
                    if (checkStop(task)) return
                    commitCompleted(task)
                    return
                } catch (e: TaskStopped) {
                    checkStop(task)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "transfer failed (attempt $attempt, done=${task.done}/${task.total})", e)
                    if (checkStop(task)) return
                    // 有进展就把重试计数清零:长时间大文件不至于几次小抖动就失败
                    if (task.done > lastProgress) {
                        attempt = 0
                        lastProgress = task.done
                    }
                    if (e is HttpError && e.code !in intArrayOf(408, 429) && e.code in 400..499) attempt = RETRY_TIMES
                    if (attempt >= RETRY_TIMES) {
                        synchronized(lock) {
                            task.status = ST_FAILED
                            task.speed = 0
                            persistLocked()
                        }
                        fireChanged(force = true)
                        notifFinished(task, failed = true)
                        return
                    }
                    attempt++
                    var slept = 0L
                    while (slept < RETRY_BACKOFF_MS[attempt - 1]) {
                        Thread.sleep(500)
                        slept += 500
                        if (checkStop(task)) return
                    }
                }
            }
        } finally {
            workerExit(task)
        }
    }

    /**
     * 收敛暂停/删除:置终态、清理文件、发回调。返回 true 表示 worker 应退出。
     * 全程在锁内改状态;文件删除为本地快速操作,一并处理。
     */
    private fun checkStop(task: Task): Boolean {
        val stop: Boolean
        synchronized(lock) {
            stop = task.pauseRequested || task.pendingDelete
            if (stop) {
                if (task.pendingDelete) {
                    task.deleteFiles(keepCommitted = task.pendingDeleteKeepFile)
                    cancelNotif(task)
                } else {
                    task.pauseRequested = false
                    task.status = ST_PAUSED
                    task.speed = 0
                    persistLocked()
                    cancelNotif(task)
                }
            }
        }
        if (stop) fireChanged(force = true)
        return stop
    }

    /** worker 退出:腾出并发名额并拉起下一个等待中的任务 */
    private fun workerExit(task: Task) {
        synchronized(lock) {
            task.active = false
            if (runningCount > 0) runningCount--
            // 异常退出兜底:状态停在下载中则标失败,避免永久卡住
            if (task.status == ST_RUNNING && !task.pendingDelete) {
                task.status = ST_FAILED
                task.speed = 0
                persistLocked()
            }
            val next = tasks.firstOrNull { it.status == ST_WAITING && !it.active }
            if (next != null) tryStartLocked(next)
        }
        fireChanged(force = true)
    }

    // ---------- 传输 ----------

    /**
     * 单次完整传输(含重定向逐跳)。成功返回时文件已下完(或断点已满)。
     * 手动跟随重定向:HttpURLConnection 只自动跟同协议跳转,http↔https
     * 互跳会直接把 3xx 抛给调用方;逐跳手动跟还能逐跳刷新 Cookie。
     */
    private fun transfer(task: Task, resetOnce: Boolean) {
        var current = task.url
        var redirects = 0
        var didReset = resetOnce
        while (true) {
            if (++redirects > MAX_REDIRECTS) throw IOException("too many redirects")
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "*/*")
            // 明确不要压缩编码:保证 Content-Length 就是真实字节数,进度与断点才准
            conn.setRequestProperty("Accept-Encoding", "identity")
            task.userAgent?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("User-Agent", it) }
            task.referer?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Referer", it) }
            runCatching { CookieManager.getInstance().getCookie(current) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }

            val part = partFile(task)
            // 断点续传必须带校验器(If-Range),否则无法确认服务器文件没变,宁可重下
            if (part.length() < task.done) task.done = part.length()
            val canResume = task.done > 0 && (task.etag != null || task.lastModified != null)
            var offset = if (canResume) task.done else 0L
            if (offset > 0) {
                conn.setRequestProperty("Range", "bytes=$offset-")
                task.etag?.takeIf { !it.startsWith("W/") }?.let { conn.setRequestProperty("If-Range", it) }
                    ?: task.lastModified?.let { conn.setRequestProperty("If-Range", it) }
            }

            conn.connect()
            val code = conn.responseCode
            when {
                code in 300..399 -> {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) throw IOException("redirect without Location")
                    current = URL(URL(current), loc).toString()
                }
                code == 416 -> {
                    conn.disconnect()
                    if (task.total > 0 && task.done >= task.total) return
                    // 断点对不上(服务器文件变了/缓存被清):推倒重下一次,再不行才报错
                    if (!didReset) {
                        didReset = true
                        synchronized(lock) {
                            task.etag = null
                            task.lastModified = null
                            task.done = 0
                            task.total = -1
                            persistLocked()
                        }
                    } else {
                        throw HttpError(code)
                    }
                }
                code == 200 || code == 206 -> {
                    if (code == 200 && offset > 0) offset = 0 // 服务器不理 Range:从头来
                    readHeaders(conn, task, offset, code == 206)
                    readBody(conn, task, part, offset)
                    return
                }
                else -> {
                    conn.disconnect()
                    throw HttpError(code)
                }
            }
        }
    }

    /** 从响应头取大小/类型/续传校验器(ETag、Last-Modified) */
    private fun readHeaders(conn: HttpURLConnection, task: Task, offset: Long, partial: Boolean) {
        val len = conn.contentLengthLong
        val type = conn.contentType?.substringBefore(';')?.trim().orEmpty()
        if (type.isNotBlank() && type.lowercase(Locale.ROOT) != "application/octet-stream") task.mime = type
        conn.getHeaderField("ETag")?.let { task.etag = it }
        conn.getHeaderField("Last-Modified")?.let { task.lastModified = it }
        synchronized(lock) {
            task.total = when {
                partial && len >= 0 -> offset + len
                partial -> task.total
                else -> len
            }
            persistLocked()
        }
    }

    /** 读响应体写 .part;暂停/删除随时终止;速度按 1s 窗口平滑 */
    private fun readBody(conn: HttpURLConnection, task: Task, part: File, startOffset: Long) {
        val raf = RandomAccessFile(part, "rw")
        try {
            var offset = startOffset
            raf.setLength(offset) // 归一文件长度:200 重下清零,206 续传截掉多写的尾巴
            task.done = offset
            raf.seek(offset)
            val input = BufferedInputStream(conn.inputStream, BUFFER_SIZE)
            val buf = ByteArray(BUFFER_SIZE)
            var now = System.currentTimeMillis()
            var lastUiFire = now
            var lastPersist = now
            var lastNotifTick = now
            var windowStart = now
            var windowBytes = 0L
            while (true) {
                if (task.pauseRequested || task.pendingDelete) throw TaskStopped()
                val n = input.read(buf)
                if (n < 0) break
                raf.write(buf, 0, n)
                offset += n
                task.done = offset
                windowBytes += n
                now = System.currentTimeMillis()
                if (now - windowStart >= 1000) {
                    val inst = windowBytes * 1000 / (now - windowStart)
                    task.speed = if (task.speed > 0) (task.speed + inst) / 2 else inst
                    windowBytes = 0
                    windowStart = now
                }
                if (now - lastPersist >= PERSIST_MIN_INTERVAL_MS) {
                    synchronized(lock) { persistLocked() }
                    lastPersist = now
                }
                if (now - lastUiFire >= FIRE_MIN_INTERVAL_MS) {
                    fireChanged()
                    lastUiFire = now
                }
                if (now - lastNotifTick >= NOTIFY_MIN_INTERVAL_MS) {
                    notifProgress(task)
                    lastNotifTick = now
                }
            }
            task.speed = 0
            if (task.total > 0 && task.done < task.total) throw IOException("connection closed early")
        } finally {
            runCatching { raf.close() }
            runCatching { conn.disconnect() }
        }
    }

    /** 传输完成:提交到目标位置(自定义目录/系统下载集合),失败保留断点标失败 */
    private fun commitCompleted(task: Task) {
        if (task.pendingDelete) {
            checkStop(task)
            return
        }
        val part = partFile(task)
        val result = try {
            commitDownloadFile(context, part, task.name, task.mime.ifBlank { "application/octet-stream" }, task.treeUri)
        } catch (e: Exception) {
            Log.w(TAG, "commit failed: ${task.name}", e)
            synchronized(lock) {
                task.status = ST_FAILED
                task.speed = 0
                persistLocked()
            }
            fireChanged(force = true)
            notifFinished(task, failed = true)
            return
        }
        runCatching { part.delete() }
        synchronized(lock) {
            task.finalUri = result.uri
            task.finalPath = result.path
            task.finalName = result.name
            task.name = result.name
            if (task.total <= 0) task.total = result.size
            task.done = result.size
            task.status = ST_DONE
            task.doneAt = System.currentTimeMillis()
            task.speed = 0
            persistLocked()
        }
        fireChanged(force = true)
        notifFinished(task, failed = false)
    }

    // ---------- 持久化 ----------

    private fun persistLocked() {
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("url", t.url)
                    .put("orig", t.originalUrl)
                    .put("n", t.name)
                    .put("m", t.mime)
                    .put("ua", t.userAgent.orEmpty())
                    .put("ref", t.referer.orEmpty())
                    .put("tree", t.treeUri.orEmpty())
                    .put("t", t.total)
                    .put("d", t.done)
                    .put("e", t.etag.orEmpty())
                    .put("lm", t.lastModified.orEmpty())
                    .put("st", t.status)
                    .put("ts", t.createdAt)
                    .put("dt", t.doneAt)
                    .put("fu", t.finalUri)
                    .put("fp", t.finalPath)
                    .put("fn", t.finalName)
            )
        }
        context.getSharedPreferences(PREF_ENGINE, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_TASKS, arr.toString())
            .apply()
    }

    /** 启动时恢复任务;下载中/等待中的改为已暂停(worker 已随上个进程消失) */
    private fun loadLocked() {
        val raw = context.getSharedPreferences(PREF_ENGINE, Context.MODE_PRIVATE)
            .getString(PREF_TASKS, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val status = o.optInt("st", ST_FAILED)
                tasks.add(
                    Task(
                        id = o.optLong("id"),
                        url = o.optString("url"),
                        originalUrl = o.optString("orig", o.optString("url")),
                        name = o.optString("n", "download"),
                        mime = o.optString("m"),
                        userAgent = o.optString("ua").takeIf { it.isNotEmpty() },
                        referer = o.optString("ref").takeIf { it.isNotEmpty() },
                        treeUri = o.optString("tree").takeIf { it.isNotEmpty() },
                        total = o.optLong("t", -1L),
                        done = o.optLong("d", 0L),
                        speed = 0,
                        etag = o.optString("e").takeIf { it.isNotEmpty() },
                        lastModified = o.optString("lm").takeIf { it.isNotEmpty() },
                        status = if (status == ST_RUNNING || status == ST_WAITING) ST_PAUSED else status,
                        createdAt = o.optLong("ts", 0L),
                        doneAt = o.optLong("dt", 0L),
                        finalUri = o.optString("fu"),
                        finalPath = o.optString("fp"),
                        finalName = o.optString("fn"),
                        pauseRequested = false,
                        pendingDelete = false,
                        pendingDeleteKeepFile = false,
                        active = false
                    )
                )
            }
        }
    }

    /** 清掉没有任务引用的残留 .part(崩溃/删除遗漏的) */
    private fun cleanupStaleParts() {
        val dir = stagingDir() ?: return
        val ids = synchronized(lock) { tasks.map { it.id.toString() }.toHashSet() }
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".part") && f.name.removeSuffix(".part") !in ids) runCatching { f.delete() }
        }
    }

    private fun stagingDir(): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir ?: return null
        return File(base, "ldm_dl").apply { mkdirs() }
    }

    private fun partFile(task: Task): File = File(stagingDir(), "${task.id}.part")

    /** 删任务的文件:.part 总是删(只是临时中转),已落盘文件按 [keepCommitted] 保留 */
    private fun Task.deleteFiles(keepCommitted: Boolean = false) {
        runCatching { partFile(this@deleteFiles).delete() }
        if (keepCommitted) return
        if (finalUri.isNotEmpty() || finalPath.isNotEmpty()) {
            runCatching { deleteCommitted(context, finalUri, finalPath) }
        }
    }

    // ---------- 通知 ----------

    private fun ensureChannel() {
        if (nm == null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.browser_dl_notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun notifEnabled(): Boolean = nm?.areNotificationsEnabled() == true

    private fun notifId(task: Task): Int = ((task.id and 0x7fffffffL) % 100000L).toInt() + 1

    private fun openDownloadsPendingIntent(task: Task): PendingIntent? {
        val intent = Intent(context, BrowserActivity::class.java)
            .putExtra(EXTRA_OPEN_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, notifId(task), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifProgress(task: Task) {
        val manager = nm ?: return
        if (!notifEnabled()) return
        synchronized(lastNotif) {
            val last = lastNotif[task.id] ?: 0L
            val now = System.currentTimeMillis()
            if (now - last < NOTIFY_MIN_INTERVAL_MS) return
            lastNotif[task.id] = now
        }
        val text = buildString {
            if (task.total > 0) append(((task.done * 100) / task.total).toInt().coerceIn(0, 100)).append("%")
            if (task.speed > 0) {
                if (isNotEmpty()) append(" · ")
                append(formatBytes(task.speed)).append("/s")
            }
        }.ifEmpty { formatBytes(task.done) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.name)
            .setContentText(text)
            .setProgress(100, if (task.total > 0) ((task.done * 100) / task.total).toInt().coerceIn(0, 100) else 0, task.total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openDownloadsPendingIntent(task))
            .build()
        runCatching { manager.notify(notifId(task), notification) }
    }

    private fun notifFinished(task: Task, failed: Boolean) {
        val manager = nm ?: return
        runCatching { manager.cancel(notifId(task)) }
        if (!notifEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(task.name)
            .setContentText(
                context.getString(
                    if (failed) R.string.browser_dl_status_failed else R.string.browser_dl_notif_done
                )
            )
            .setAutoCancel(true)
            .setContentIntent(openDownloadsPendingIntent(task))
            .build()
        runCatching { manager.notify(notifId(task), notification) }
    }

    private fun cancelNotif(task: Task) {
        runCatching { nm?.cancel(notifId(task)) }
    }

    // ---------- 回调节流 ----------

    private fun fireChanged(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force) {
            val last = lastFire.get()
            if (now - last < FIRE_MIN_INTERVAL_MS) return
            if (!lastFire.compareAndSet(last, now)) return
        } else {
            lastFire.set(now)
        }
        onUpdate?.invoke()
    }

    // ---------- 工具 ----------

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val pattern = if (unit == 0) "%.0f%s" else "%.1f%s"
        return String.format(Locale.US, pattern, value, units[unit])
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download" }
}

/**
 * 把下载完成的文件提交到目标位置,返回内容地址与展示路径:
 * - [treeUri] 非空:写入用户在设置里选的目录(SAF),全版本可用,无需存储权限
 * - 否则 Android 10+:写入 MediaStore「下载」集合(公共下载目录,无需存储权限)
 * - 否则:写应用专属下载目录(无需权限),外部应用打开走 FileProvider
 * 重名去重:SAF/MediaStore 由系统改名,应用目录本地追加 (1)、(2)…。
 * 失败时清掉半截文件再抛异常,不留垃圾记录。
 */
internal fun commitDownloadFile(
    context: Context,
    src: File,
    name: String,
    mime: String,
    treeUri: String?
): LdmDownloader.SaveResult {
    if (!treeUri.isNullOrBlank()) return commitToTree(context, src, name, mime, treeUri)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // 提交时 _data 路径可能被孤儿记录占用(文件被文件管理器等绕过 MediaStore 删过),
        // IS_PENDING 清零时会撞唯一约束:清掉 pending 行,换去重名再试
        var attempt = name
        repeat(3) { i ->
            try {
                return commitToMediaStore(context, src, attempt, mime)
            } catch (e: Exception) {
                if (i == 2) throw e
                attempt = dedupFileName(attempt, i + 1)
            }
        }
    }
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    val dest = uniqueFileIn(dir, name)
    src.copyTo(dest, overwrite = false)
    return LdmDownloader.SaveResult("", dest.absolutePath, dest.name, mime, dest.length())
}

/** 写入 MediaStore「下载」集合:IS_PENDING 先写后公开,失败即删残留记录 */
private fun commitToMediaStore(context: Context, src: File, name: String, mime: String): LdmDownloader.SaveResult {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("MediaStore insert failed")
    try {
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(src).use { it.copyTo(out) }
        } ?: throw IOException("no output stream")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw e
    }
    val finalName = queryDisplayName(context, uri) ?: name
    return LdmDownloader.SaveResult(
        uri.toString(), publicDownloadPath(finalName), finalName, mime, src.length()
    )
}

/** 重名去重:「name.ext」→「name (1).ext」 */
private fun dedupFileName(name: String, n: Int): String {
    val base = name.substringBeforeLast('.', missingDelimiterValue = name)
    val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        .let { p -> if (p.isEmpty()) "" else ".$p" }
    return "$base ($n)$ext"
}

/** 写入 SAF 目录:先建文档(系统自动重名去重),再拷内容 */
private fun commitToTree(
    context: Context,
    src: File,
    name: String,
    mime: String,
    treeUri: String
): LdmDownloader.SaveResult {
    val tree = Uri.parse(treeUri)
    val resolver = context.contentResolver
    val parent = DocumentsContract.buildDocumentUriUsingTree(
        tree, DocumentsContract.getTreeDocumentId(tree)
    )
    val doc = DocumentsContract.createDocument(
        resolver, parent, mime.ifBlank { "application/octet-stream" }, name
    ) ?: throw IOException("SAF create failed")
    try {
        resolver.openOutputStream(doc)?.use { out ->
            FileInputStream(src).use { it.copyTo(out) }
        } ?: throw IOException("no output stream")
    } catch (e: Exception) {
        runCatching { DocumentsContract.deleteDocument(resolver, doc) }
        throw e
    }
    val finalName = queryDisplayName(context, doc) ?: name
    val display = treeDisplayPath(tree).let { if (it.isBlank()) finalName else "$it/$finalName" }
    return LdmDownloader.SaveResult(doc.toString(), display, finalName, mime, src.length())
}

/** 删除已落盘文件:MediaStore 记录、SAF 文档或本地文件 */
internal fun deleteCommitted(context: Context, uri: String, path: String): Boolean {
    val resolver = context.contentResolver
    return when {
        uri.startsWith("content://") -> runCatching {
            val u = Uri.parse(uri)
            if (DocumentsContract.isDocumentUri(context, u)) {
                DocumentsContract.deleteDocument(resolver, u)
            } else {
                resolver.delete(u, null, null) != 0
            }
        }.getOrDefault(false)
        path.isNotBlank() -> File(path).delete()
        else -> false
    }
}

/** 同名文件追加 (1)、(2)…(SAF/MediaStore 的重名去重由系统完成,这里只管本地目录) */
internal fun uniqueFileIn(dir: File, name: String): File {
    var dest = File(dir, name)
    if (!dest.exists()) return dest
    val base = name.substringBeforeLast('.', missingDelimiterValue = name)
    val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        .let { p -> if (p.isEmpty()) "" else ".$p" }
    var i = 1
    while (dest.exists()) {
        dest = File(dir, "$base ($i)$ext")
        i++
    }
    return dest
}

/** 查询内容地址的显示文件名(系统去重改名后与提交名可能不同) */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

/** 公共下载目录的展示路径(真实物理路径,Android 标准布局) */
@Suppress("DEPRECATION")
private fun publicDownloadPath(name: String): String =
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        .absolutePath.trimEnd('/') + "/" + name

/**
 * SAF 目录 tree uri → 近真实展示路径:primary 卷映射到内部存储标准挂载点,
 * 其余卷显示原始标识。仅用于展示与复制路径,不做文件系统访问。
 */
internal fun treeDisplayPath(treeUri: Uri): String {
    return runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val root = docId.substringBefore(':', docId)
        val rel = docId.substringAfter(':', "")
        val rootPath = when (root) {
            "primary" -> "/storage/emulated/0"
            else -> root
        }
        when {
            rel.isBlank() -> rootPath
            root == "primary" -> "$rootPath/$rel"
            else -> "$root:$rel"
        }
    }.getOrDefault("")
}

/** 设置里显示的下载目录名:默认「下载」,自定义显示所选路径 */
internal fun downloadDirLabel(context: Context): String {
    val treeUri = context.getSharedPreferences(BrowserActivity.PREFS_NAME, Context.MODE_PRIVATE)
        .getString(LdmDownloader.PREF_DL_DIR, null)
        ?.takeIf { it.isNotBlank() } ?: return context.getString(R.string.browser_dl_dir_default_name)
    return treeDisplayPath(Uri.parse(treeUri)).ifBlank {
        context.getString(R.string.browser_dl_dir_default_name)
    }
}

/**
 * 内容地址 → 真实物理路径(尽力而为):MediaStore 记录取 DATA 列;
 * 其它来源返回 null(调用方按需隐藏「复制路径」入口)。
 */
internal fun resolveMediaPath(context: Context, uri: Uri): String? {
    if (uri.scheme != "content") return null
    return runCatching {
        context.contentResolver.query(
            uri, arrayOf("_data"), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
}
