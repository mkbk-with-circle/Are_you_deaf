package com.nierduolong.morningbell.transfer

import android.content.Context
import com.nierduolong.morningbell.dailylog.lan.HttpByteRange
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 临时令牌保护的只读 HTTP 文件服务。原片直接从 ContentResolver 流向 socket；只有用户请求的
 * 图片预览会进入有上限的 cacheDir。对相机卡限制并发随机读，避免多预览把廉价读卡器拖死。
 */
class NearbyFileShareServer(
    context: Context,
    private val catalog: TransferSourceCatalog,
    private val token: String,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onMetrics(activeClients: Int, transferredBytes: Long)
        fun onSourceUnavailable(message: String)
    }

    private val server = runCatching { ServerSocket(PREFERRED_PORT) }.getOrElse { ServerSocket(0) }
    private val executor = ThreadPoolExecutor(
        MAX_CLIENTS,
        MAX_CLIENTS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_CLIENTS),
    ) { runnable -> Thread(runnable, "nearby-file-client").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val activeClients = AtomicInteger(0)
    private val transferredBytes = AtomicLong(0)
    private val clientsByAddress = ConcurrentHashMap<String, AtomicInteger>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val activeInputs = ConcurrentHashMap.newKeySet<InputStream>()
    private val sourceReadGate = Semaphore(if (catalog.removable) 2 else 4, true)
    private val zipGate = Semaphore(1, true)
    private val imageCache = TransferImageCache(context.cacheDir, sourceReadGate, ::trackedOpen)
    private var acceptThread: Thread? = null

    val port: Int get() = server.localPort

    fun start() {
        check(catalog.isReadable()) { "所选文件或存储已经无法读取" }
        acceptThread = Thread({ acceptLoop() }, "nearby-file-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun sharePath(): String = "/s/$token/"

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            if (!isLanAddress(socket.inetAddress)) {
                socket.close()
                continue
            }
            val address = socket.inetAddress.hostAddress.orEmpty()
            activeSockets += socket
            val perAddress = clientsByAddress.computeIfAbsent(address) { AtomicInteger() }
            if (perAddress.incrementAndGet() > MAX_CLIENTS_PER_ADDRESS) {
                perAddress.decrementAndGet()
                activeSockets -= socket
                socket.close()
                continue
            }
            try {
                executor.execute {
                    val active = activeClients.incrementAndGet()
                    notifyMetrics(active)
                    try {
                        handle(socket)
                    } finally {
                        runCatching { socket.close() }
                        activeSockets -= socket
                        val remaining = activeClients.decrementAndGet()
                        if (perAddress.decrementAndGet() <= 0) clientsByAddress.remove(address, perAddress)
                        notifyMetrics(remaining)
                    }
                }
            } catch (_: Exception) {
                perAddress.decrementAndGet()
                activeSockets -= socket
                socket.close()
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
        val output = BufferedOutputStream(socket.getOutputStream(), IO_BUFFER_BYTES)
        val request = readRequest(input) ?: return
        if (request.method == "OPTIONS") {
            writeHeaders(output, 204, mapOf("Content-Length" to "0"))
            output.flush()
            return
        }
        if (request.method != "GET" && request.method != "HEAD") {
            writeText(output, 405, "Method not allowed", request.method == "HEAD")
            output.flush()
            return
        }
        val prefix = "/s/$token"
        if (!TransferPathPolicy.authorizedRoute(request.path, prefix)) {
            writeText(output, 404, "Not found", request.method == "HEAD")
            output.flush()
            return
        }
        val relativeRoute = request.path.removePrefix(prefix).ifBlank { "/" }
        try {
            when {
                relativeRoute == "/" -> writeHtml(output, browserPage(), request.method == "HEAD")
                relativeRoute == "/_api/list" -> writeList(output, request, request.method == "HEAD")
                relativeRoute == "/_zip" -> writeZip(output, request, request.method == "HEAD")
                relativeRoute.startsWith("/file/") -> {
                    val path = decodeRelativePath(relativeRoute.removePrefix("/file/"))
                    if (path == null) writeText(output, 403, "Forbidden", request.method == "HEAD")
                    else writeFile(output, request, path, request.method == "HEAD")
                }
                else -> writeText(output, 404, "Not found", request.method == "HEAD")
            }
        } catch (_: SecurityException) {
            listener.onSourceUnavailable("存储授权已失效，请重新选择相机卡或文件")
            writeText(output, 403, "Storage permission expired", request.method == "HEAD")
        } catch (error: Exception) {
            if (!catalog.isReadable()) listener.onSourceUnavailable("相机卡、U 盘或所选文件已经断开")
            runCatching { writeText(output, 500, error.message ?: "Read failed", request.method == "HEAD") }
        } finally {
            runCatching { output.flush() }
        }
    }

    private fun writeList(output: OutputStream, request: Request, headOnly: Boolean) {
        val path = request.query["path"]?.firstOrNull().orEmpty()
        if (TransferSafTree.safePathParts(path) == null) {
            writeText(output, 403, "Forbidden", headOnly)
            return
        }
        val recursive = request.query["recursive"]?.firstOrNull() in setOf("1", "true")
        val (items, truncated) = if (recursive) catalog.listRecursive(path) else catalog.list(path) to false
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("path", item.path)
                    .put("name", item.name)
                    .put("size", item.size)
                    .put("modified", item.lastModified)
                    .put("mime", item.mimeType)
                    .put("kind", item.kind),
            )
        }
        val body = JSONObject().put("files", array).put("truncated", truncated).toString().toByteArray()
        writeHeaders(
            output,
            200,
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Cache-Control" to "no-store",
            ),
        )
        if (!headOnly) output.write(body)
    }

    private fun writeFile(output: OutputStream, request: Request, path: String, headOnly: Boolean) {
        val entry = catalog.resolveFile(path)
        if (entry == null) {
            writeText(output, 404, "File not found", headOnly)
            return
        }
        val quality = request.query["q"]?.firstOrNull()?.takeIf { it in QUALITY_VALUES }
            ?: if (request.query.containsKey("thumb")) "thumb" else "orig"
        if (quality != "orig" && TransferMime.isImage(entry.path)) {
            val preview = imageCache.getOrCreate(entry, quality)
            if (preview == null) {
                // 绝不把缩略图请求降级成整张原图；相机 JPEG 可能几十 MB，会浪费网络并挤爆浏览器。
                writeText(output, 503, "Preview is busy or unsupported; retry or download original", headOnly)
                return
            }
            val etag = weakEtag("${entry.etagSeed}|$quality|${preview.length()}")
            if (request.headers["if-none-match"] == etag) {
                writeHeaders(output, 304, mapOf("ETag" to etag, "Content-Length" to "0"))
                return
            }
            writeHeaders(
                output,
                200,
                mapOf(
                    "Content-Type" to "image/jpeg",
                    "Content-Length" to preview.length().toString(),
                    "ETag" to etag,
                    "Cache-Control" to "private, max-age=120",
                    "Content-Disposition" to contentDisposition(entry.path, request.query.containsKey("download"), quality),
                ),
            )
            if (!headOnly) preview.inputStream().use {
                TransferStreamRelay.copyExactly(it, output, preview.length(), ::addTransferred)
            }
            return
        }
        writeOriginal(output, request, entry, headOnly)
    }

    private fun writeOriginal(output: OutputStream, request: Request, entry: TransferReadableEntry, headOnly: Boolean) {
        val total = entry.size
        val etag = weakEtag(entry.etagSeed)
        val lastModified = httpDate(entry.lastModified)
        if (request.headers["if-none-match"] == etag && request.headers["range"].isNullOrBlank()) {
            writeHeaders(output, 304, mapOf("ETag" to etag, "Content-Length" to "0"))
            return
        }
        if (total <= 0) {
            writeHeaders(
                output,
                200,
                mapOf(
                    "Content-Type" to entry.mimeType,
                    "ETag" to etag,
                    "Last-Modified" to lastModified,
                    "Content-Disposition" to contentDisposition(entry.path, request.query.containsKey("download"), "orig"),
                ),
            )
            if (!headOnly) withSourcePermit {
                trackedOpen(entry, 0).use { TransferStreamRelay.copyUntilEnd(it, output, ::addTransferred) }
            }
            return
        }

        val ifRange = request.headers["if-range"]
        val rangeHeader = request.headers["range"].takeIf {
            ifRange.isNullOrBlank() || ifRange == etag || ifRange == lastModified
        }
        val parsed = HttpByteRange.parse(rangeHeader, total)
        if (parsed is HttpByteRange.Unsatisfiable) {
            writeHeaders(
                output,
                416,
                mapOf("Content-Range" to "bytes */$total", "Content-Length" to "0", "ETag" to etag),
            )
            return
        }
        val selected = when (parsed) {
            HttpByteRange.Full -> HttpByteRange.Partial(0, total - 1)
            is HttpByteRange.Partial -> parsed
            HttpByteRange.Unsatisfiable -> return
        }
        val status = if (parsed is HttpByteRange.Partial) 206 else 200
        val headers = linkedMapOf(
            "Content-Type" to entry.mimeType,
            "Content-Length" to selected.length.toString(),
            "Accept-Ranges" to "bytes",
            "ETag" to etag,
            "Last-Modified" to lastModified,
            "Content-Disposition" to contentDisposition(entry.path, request.query.containsKey("download"), "orig"),
        )
        if (status == 206) headers["Content-Range"] = "bytes ${selected.start}-${selected.endInclusive}/$total"
        writeHeaders(output, status, headers)
        if (!headOnly) withSourcePermit {
            trackedOpen(entry, selected.start).use {
                TransferStreamRelay.copyExactly(it, output, selected.length, ::addTransferred)
            }
        }
    }

    private fun writeZip(output: OutputStream, request: Request, headOnly: Boolean) {
        if (headOnly) {
            writeHeaders(output, 405, mapOf("Content-Length" to "0"))
            return
        }
        val requested = request.query["f"].orEmpty().distinct().take(MAX_ZIP_FILES + 1)
        if (requested.isEmpty() || requested.size > MAX_ZIP_FILES || requested.any { TransferSafTree.safePathParts(it) == null }) {
            writeText(output, 400, "Invalid zip selection", false)
            return
        }
        val entries = requested.mapNotNull(catalog::resolveFile)
        if (entries.size != requested.size) {
            writeText(output, 404, "Some files are unavailable", false)
            return
        }
        val knownTotal = entries.sumOf { it.size.coerceAtLeast(0) }
        if (knownTotal > MAX_ZIP_BYTES) {
            writeText(output, 413, "Zip is too large; use resumable individual downloads", false)
            return
        }
        if (!zipGate.tryAcquire()) {
            writeText(output, 429, "Another zip is running", false)
            return
        }
        try {
            writeHeaders(
                output,
                200,
                mapOf(
                    "Content-Type" to "application/zip",
                    "Content-Disposition" to "attachment; filename=nearby-transfer.zip",
                    "Cache-Control" to "no-store",
                ),
            )
            withSourcePermit {
                val zip = ZipOutputStream(NonClosingOutputStream(output)).apply { setLevel(0) }
                zip.use { value ->
                    val used = mutableSetOf<String>()
                    entries.forEach { entry ->
                        val name = uniqueZipName(entry.path, used)
                        value.putNextEntry(ZipEntry(name))
                        trackedOpen(entry, 0).use { TransferStreamRelay.copyUntilEnd(it, value, ::addTransferred) }
                        value.closeEntry()
                    }
                }
            }
        } finally {
            zipGate.release()
        }
    }

    private inline fun <T> withSourcePermit(block: () -> T): T {
        sourceReadGate.acquire()
        return try {
            block()
        } finally {
            sourceReadGate.release()
        }
    }

    private fun trackedOpen(entry: TransferReadableEntry, offset: Long): InputStream {
        val raw = entry.openAt(offset)
        lateinit var tracked: InputStream
        tracked = object : FilterInputStream(raw) {
            private val didClose = AtomicBoolean(false)
            override fun close() {
                if (!didClose.compareAndSet(false, true)) return
                activeInputs -= tracked
                super.close()
            }
        }
        activeInputs += tracked
        return tracked
    }

    private fun readRequest(input: BufferedInputStream): Request? {
        val first = readAsciiLine(input, MAX_HEADER_BYTES) ?: return null
        val pieces = first.split(' ')
        if (pieces.size < 2) return null
        val rawTarget = pieces[1].takeIf { it.length <= MAX_TARGET_CHARS } ?: return null
        val rawPath = rawTarget.substringBefore('?')
        val headers = linkedMapOf<String, String>()
        var consumed = first.length
        while (consumed < MAX_HEADER_BYTES) {
            val line = readAsciiLine(input, MAX_HEADER_BYTES - consumed) ?: return null
            consumed += line.length + 2
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
        }
        return Request(
            method = pieces[0].uppercase(Locale.ROOT),
            path = rawPath,
            query = parseQuery(rawTarget.substringAfter('?', "")),
            headers = headers,
        )
    }

    private fun parseQuery(raw: String): Map<String, List<String>> {
        val output = linkedMapOf<String, MutableList<String>>()
        raw.split('&').filter(String::isNotBlank).take(MAX_QUERY_VALUES).forEach { pair ->
            val key = URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8.name())
            output.getOrPut(key) { mutableListOf() }.add(value)
        }
        return output
    }

    private fun readAsciiLine(input: InputStream, maxBytes: Int): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < maxBytes) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.ISO_8859_1.name())
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        if (bytes.size() >= maxBytes) return null
        return bytes.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun writeText(output: OutputStream, status: Int, message: String, headOnly: Boolean) {
        val body = message.toByteArray()
        writeHeaders(output, status, mapOf("Content-Type" to "text/plain; charset=utf-8", "Content-Length" to body.size.toString()))
        if (!headOnly) output.write(body)
    }

    private fun writeHtml(output: OutputStream, html: String, headOnly: Boolean) {
        val body = html.toByteArray()
        writeHeaders(
            output,
            200,
            mapOf(
                "Content-Type" to "text/html; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Cache-Control" to "no-store",
                "Content-Security-Policy" to "default-src 'self'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'",
                "Referrer-Policy" to "no-referrer",
                "X-Content-Type-Options" to "nosniff",
            ),
        )
        if (!headOnly) output.write(body)
    }

    private fun writeHeaders(output: OutputStream, status: Int, headers: Map<String, String>) {
        val reason = STATUS_REASONS[status] ?: "Status"
        val text = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Connection: close\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            headers.forEach { (name, value) ->
                if (value.isNotBlank()) append(name).append(": ").append(value.replace("\r", "").replace("\n", "")).append("\r\n")
            }
            append("\r\n")
        }
        output.write(text.toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun decodeRelativePath(encoded: String): String? {
        val decoded = encoded.split('/').filter(String::isNotEmpty).joinToString("/") {
            URLDecoder.decode(it, StandardCharsets.UTF_8.name())
        }
        return decoded.takeIf { TransferPathPolicy.split(it) != null }
    }

    private fun contentDisposition(path: String, download: Boolean, quality: String): String {
        if (!download) return "inline"
        val raw = path.substringAfterLast('/').replace('"', '_').replace('\r', '_').replace('\n', '_')
        val name = if (quality == "orig") raw else "${raw.substringBeforeLast('.', raw)}-$quality.jpg"
        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return "attachment; filename=download; filename*=UTF-8''$encoded"
    }

    private fun uniqueZipName(path: String, used: MutableSet<String>): String {
        var candidate = path.trim('/').replace("../", "_").ifBlank { "shared.bin" }
        if (used.add(candidate)) return candidate
        val dot = candidate.lastIndexOf('.')
        val stem = if (dot > 0) candidate.substring(0, dot) else candidate
        val extension = if (dot > 0) candidate.substring(dot) else ""
        var number = 2
        while (!used.add("$stem-$number$extension")) number++
        return "$stem-$number$extension"
    }

    private fun weakEtag(seed: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return "W/\"${bytes.take(12).joinToString("") { "%02x".format(it) }}\""
    }

    private fun httpDate(timeMs: Long): String {
        val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return format.format(java.util.Date(timeMs.takeIf { it > 0 } ?: 0L))
    }

    private fun notifyMetrics(active: Int = activeClients.get()) {
        listener.onMetrics(active, transferredBytes.get())
    }

    private fun addTransferred(bytes: Int) {
        val total = transferredBytes.addAndGet(bytes.toLong())
        if (total / METRICS_STEP_BYTES != (total - bytes) / METRICS_STEP_BYTES) notifyMetrics()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        activeSockets.toList().forEach { runCatching { it.close() } }
        activeInputs.toList().forEach { runCatching { it.close() } }
        activeSockets.clear()
        activeInputs.clear()
        executor.shutdownNow()
        acceptThread?.interrupt()
        acceptThread = null
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, List<String>>,
        val headers: Map<String, String>,
    )

    private class NonClosingOutputStream(delegate: OutputStream) : java.io.FilterOutputStream(delegate) {
        override fun close() {
            flush()
        }
    }

    private fun browserPage(): String = BROWSER_HTML

    companion object {
        const val PREFERRED_PORT = 8765
        private const val MAX_CLIENTS = 6
        // Chromium/Safari 常同时开约 6 条连接加载缩略图；真正的相机卡读取仍由 sourceReadGate 限制为 2。
        private const val MAX_CLIENTS_PER_ADDRESS = 6
        private const val MAX_QUEUED_CLIENTS = 16
        private const val MAX_ZIP_FILES = 80
        private const val MAX_QUERY_VALUES = 256
        private const val MAX_TARGET_CHARS = 24_000
        private const val MAX_HEADER_BYTES = 24 * 1024
        private const val SOCKET_TIMEOUT_MS = 120_000
        private const val IO_BUFFER_BYTES = TransferStreamRelay.BUFFER_BYTES
        private const val MAX_ZIP_BYTES = 6L * 1024 * 1024 * 1024
        private const val METRICS_STEP_BYTES = 4L * 1024 * 1024
        private val QUALITY_VALUES = setOf("orig", "thumb", "low", "mid", "high")
        private val STATUS_REASONS = mapOf(
            200 to "OK",
            204 to "No Content",
            206 to "Partial Content",
            304 to "Not Modified",
            400 to "Bad Request",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            413 to "Content Too Large",
            416 to "Range Not Satisfiable",
            429 to "Too Many Requests",
            500 to "Internal Server Error",
            503 to "Service Unavailable",
        )

        private fun isLanAddress(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
            val bytes = address.address
            return address is Inet6Address && bytes.isNotEmpty() && (bytes[0].toInt() and 0xfe) == 0xfc
        }

        private val BROWSER_HTML = """
<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>你尔多龙吗 · 附近快传</title><style>
:root{--bg:#0d1210;--panel:#18201c;--ink:#edf5f1;--muted:#93a99e;--line:#2b3932;--accent:#63d8aa}*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font-family:system-ui,-apple-system,"Segoe UI",sans-serif}header{position:sticky;top:0;z-index:4;background:#0d1210ee;border-bottom:1px solid var(--line);padding:14px 16px}h1{font-size:1.25rem;margin:0}.sub{color:var(--muted);font-size:.84rem;margin-top:5px}.tools{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.btn,select{border:1px solid var(--line);border-radius:11px;background:#233029;color:var(--ink);padding:9px 12px;font:inherit}.btn.primary{background:#25694f}.btn:disabled{opacity:.45}.crumb{padding:12px 16px 0;color:var(--muted);word-break:break-all}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(145px,1fr));gap:11px;padding:16px}.card{position:relative;background:var(--panel);border:1px solid var(--line);border-radius:14px;overflow:hidden}.card.on{outline:2px solid var(--accent)}.pick{position:absolute;z-index:2;top:8px;left:8px;width:20px;height:20px}.thumb{aspect-ratio:1;display:flex;align-items:center;justify-content:center;background:#101713;color:var(--muted);overflow:hidden}.thumb img{width:100%;height:100%;object-fit:cover}.meta{padding:8px;font-size:.78rem}.name{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.size{color:var(--muted);margin-top:3px}.light{display:none;position:fixed;inset:0;z-index:9;background:#000d;align-items:center;justify-content:center;flex-direction:column;gap:12px;padding:20px}.light.show{display:flex}.light img,.light video{max-width:100%;max-height:72vh;border-radius:12px}.empty{padding:40px 16px;color:var(--muted);text-align:center}
</style></head><body><header><h1>附近快传</h1><div class="sub" id="summary">正在读取…</div><div class="tools"><button class="btn" id="back">上一级</button><button class="btn" id="all">全选</button><button class="btn" id="none">清空</button><label class="sub">预览清晰度 <select id="quality"><option value="high">高清 1920</option><option value="mid" selected>标清 1280</option><option value="low">流畅 720</option></select></label><button class="btn primary" id="zip">打包所选</button></div><div class="sub">网格只按需加载 320px 缩略图，点开默认使用 1280px 预览；只有明确选择“下载原图”才传完整文件。大文件建议逐个下载以便断点续传，ZIP 不支持续传。</div></header><div class="crumb" id="crumb"></div><div class="grid" id="grid"></div><div class="light" id="light"><div id="stage"></div><div class="tools"><button class="btn" id="close">关闭</button><button class="btn" id="downloadPreview">下载预览图</button><button class="btn primary" id="downloadOriginal">下载原图 / 原文件</button></div></div>
<script>
const prefix=location.pathname.replace(/\/$/,'');const qs=new URLSearchParams(location.search);let dir=qs.get('dir')||'';let files=[];const selected=new Set();const el=id=>document.getElementById(id);const enc=p=>p.split('/').map(encodeURIComponent).join('/');const fileUrl=(f,extra={})=>{const q=new URLSearchParams(extra);return prefix+'/file/'+enc(f.path)+(q.toString()?'?'+q:'')};
function human(n){if(n<0)return '大小未知';let x=n;const u=['B','KB','MB','GB','TB'];let i=0;while(x>=1024&&i<u.length-1){x/=1024;i++}return(i?x.toFixed(2):x)+' '+u[i]}
async function load(){const r=await fetch(prefix+'/_api/list?path='+encodeURIComponent(dir));if(!r.ok)throw Error(await r.text());const d=await r.json();files=d.files||[];el('crumb').textContent=dir?'当前：/'+dir:'分享根目录';el('back').disabled=!dir;el('summary').textContent='共 '+files.length+' 项'+(d.truncated?' · 列表已截断':'');render()}
function render(){selected.clear();const g=el('grid');g.innerHTML='';if(!files.length){g.innerHTML='<div class="empty">这个文件夹为空</div>';return}for(const f of files){const c=document.createElement('div');c.className='card';const p=document.createElement('input');p.type='checkbox';p.className='pick';if(f.kind==='dir')p.hidden=true;p.onclick=e=>{e.stopPropagation();p.checked?selected.add(f.path):selected.delete(f.path);c.classList.toggle('on',p.checked)};const t=document.createElement('div');t.className='thumb';if(f.kind==='image'){const im=document.createElement('img');im.loading='lazy';im.decoding='async';im.src=fileUrl(f,{thumb:1});im.onerror=()=>t.textContent='图片';t.appendChild(im)}else t.textContent=f.kind==='dir'?'📁 文件夹':f.kind==='video'?'▶ 视频':f.kind==='audio'?'♪ 音频':f.kind==='raw'?'RAW':'文件';const m=document.createElement('div');m.className='meta';const n=document.createElement('div');n.className='name';n.textContent=f.name+(f.kind==='dir'?'/':'');const s=document.createElement('div');s.className='size';s.textContent=f.kind==='dir'?'进入':human(f.size);m.append(n,s);c.append(p,t,m);c.onclick=()=>f.kind==='dir'?go(f.path):preview(f);g.appendChild(c)}}
function go(path){dir=path;history.pushState({},'',location.pathname+(dir?'?dir='+encodeURIComponent(dir):''));load().catch(showError)}function preview(f){window.current=f;const st=el('stage');st.innerHTML='';el('downloadPreview').hidden=f.kind!=='image';if(f.kind==='image'){const im=document.createElement('img');im.src=fileUrl(f,{q:el('quality').value});st.appendChild(im)}else if(f.kind==='video'||f.kind==='audio'){const v=document.createElement(f.kind==='audio'?'audio':'video');v.controls=true;v.autoplay=true;v.src=fileUrl(f);st.appendChild(v)}else{st.textContent='该格式无法在线预览，可直接下载原文件'}el('light').classList.add('show')}
function showError(e){el('grid').innerHTML='<div class="empty">读取失败：'+String(e.message||e)+'</div>'}el('back').onclick=()=>{dir=dir.split('/').slice(0,-1).join('/');history.pushState({},'',location.pathname+(dir?'?dir='+encodeURIComponent(dir):''));load().catch(showError)};el('all').onclick=()=>{document.querySelectorAll('.card').forEach((c,i)=>{const f=files[i],p=c.querySelector('.pick');if(f.kind!=='dir'){p.checked=true;selected.add(f.path);c.classList.add('on')}})};el('none').onclick=()=>{selected.clear();document.querySelectorAll('.card').forEach(c=>{c.classList.remove('on');c.querySelector('.pick').checked=false})};el('zip').onclick=()=>{if(!selected.size)return alert('请先选择文件');const q=new URLSearchParams();selected.forEach(v=>q.append('f',v));location.href=prefix+'/_zip?'+q};el('close').onclick=()=>{el('light').classList.remove('show');el('stage').innerHTML=''};el('quality').onchange=()=>{if(window.current?.kind==='image'&&el('light').classList.contains('show'))preview(window.current)};el('downloadPreview').onclick=()=>{if(window.current?.kind==='image')location.href=fileUrl(window.current,{q:el('quality').value,download:1})};el('downloadOriginal').onclick=()=>{if(window.current)location.href=fileUrl(window.current,{download:1})};window.onpopstate=()=>{dir=new URLSearchParams(location.search).get('dir')||'';load().catch(showError)};load().catch(showError);
</script></body></html>
""".trimIndent()
    }
}
