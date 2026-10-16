package com.petertroll.pdfcraftmobile

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LoopbackServer(
    private val context: Context,
    private val repository: DocumentRepository,
    private val listener: Listener
) : AutoCloseable {
    interface Listener {
        /** Called after the recovery file has been durably written. */
        fun onSaved(saveAs: Boolean, artifact: SavedArtifact)
        fun onEvent(json: String)
    }
    private val running = AtomicBoolean(false)
    private val random = SecureRandom()
    val token: String = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    // A single worker serializes current-document reads, imports, and save publication.
    private val workers = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4), ThreadPoolExecutor.AbortPolicy())
    val port: Int get() = socket?.localPort ?: 0
    val url: String get() = "http://127.0.0.1:$port/$token/host.html"

    fun start() {
        check(running.compareAndSet(false, true))
        socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        acceptThread = Thread({ acceptLoop() }, "pdfcraft-loopback").also { it.isDaemon = true; it.start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = try { socket?.accept() ?: break } catch (_: Exception) { break }
            try { workers.execute { handle(client) } } catch (_: Exception) { client.close() }
        }
    }

    private fun handle(client: Socket) = client.use { s ->
        try {
            s.soTimeout = 15_000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            val requestLine = readLine(input, 4096) ?: return@use
            val request = requestLine.split(' ')
            if (request.size != 3 || request[2] != "HTTP/1.1") return send(output, 400, "Bad Request", "text/plain", "Bad request".toByteArray())
            val method = request[0]
            val headers = mutableMapOf<String, String>()
            var headerBytes = 0
            while (true) {
                val line = readLine(input, 8192) ?: return@use
                headerBytes += line.length
                if (headerBytes > 16 * 1024) return send(output, 431, "Request Header Fields Too Large", "text/plain", ByteArray(0))
                if (line.isEmpty()) break
                val split = line.indexOf(':'); if (split <= 0) return send(output, 400, "Bad Request", "text/plain", ByteArray(0))
                headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
            }
            val target = HttpHelpers.normalizeTarget(request[1]) ?: return send(output, 400, "Bad Request", "text/plain", ByteArray(0))
            if (!target.startsWith("$token/")) return send(output, 404, "Not Found", "text/plain", ByteArray(0))
            val path = target.removePrefix("$token/")
            when {
                (method == "GET" || method == "HEAD") && path == "host.html" ->
                    send(output, 200, "OK", "text/html; charset=utf-8", HostPage.html(token).toByteArray(), method == "HEAD")
                (method == "GET" || method == "HEAD") && path == "current.pdf" -> serveFile(output, repository.currentFile, repository.displayName, method == "HEAD")
                (method == "GET" || method == "HEAD") && path.startsWith("pdfcraft/") -> serveAsset(output, path, method == "HEAD")
                method == "POST" && (path == "save" || path == "event") -> handlePost(input, output, path, headers)
                method !in setOf("GET", "HEAD", "POST") -> send(output, 405, "Method Not Allowed", "text/plain", ByteArray(0), extra = "Allow: GET, HEAD, POST\r\n")
                else -> send(output, 404, "Not Found", "text/plain", ByteArray(0))
            }
        } catch (_: Exception) { /* malformed/aborted clients are isolated */ }
    }

    private fun handlePost(input: InputStream, output: BufferedOutputStream, path: String, headers: Map<String, String>) {
        val max = if (path == "save") DocumentRepository.MAX_PDF_BYTES else 16L * 1024
        val length = headers["content-length"]?.toLongOrNull()
            ?: return send(output, 411, "Length Required", "text/plain", ByteArray(0))
        if (length < 0 || length > max) return send(output, 413, "Payload Too Large", "text/plain", ByteArray(0))
        try {
            if (path == "save") {
                val encoded = headers["x-document-name"].orEmpty()
                val name = FilenameUtil.sanitize(URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()))
                val artifact = repository.writeRecovery(input, length, name)
                listener.onSaved(headers["x-save-as"].equals("true", true), artifact)
            } else {
                val body = ByteArray(length.toInt()); var pos = 0
                while (pos < body.size) {
                    val n = input.read(body, pos, body.size - pos)
                    if (n < 0) return send(output, 400, "Bad Request", "text/plain", ByteArray(0))
                    pos += n
                }
                listener.onEvent(String(body, StandardCharsets.UTF_8))
            }
            send(output, 204, "No Content", "text/plain", ByteArray(0))
        } catch (e: Exception) { send(output, 500, "Internal Server Error", "text/plain", (e.message ?: "Save failed").toByteArray()) }
    }

    private fun serveFile(out: BufferedOutputStream, file: File, name: String, head: Boolean) {
        if (!file.isFile) return send(out, 404, "Not Found", "text/plain", ByteArray(0))
        val safeHeader = FilenameUtil.sanitize(name).replace(Regex("[^A-Za-z0-9._ -]"), "_")
        writeHeaders(out, 200, "OK", "application/pdf", file.length(), "X-Document-Name: $safeHeader\r\n")
        if (!head) file.inputStream().use { it.copyTo(out) }; out.flush()
    }

    private fun serveAsset(out: BufferedOutputStream, path: String, head: Boolean) {
        // normalizeTarget already rejects decoded dot segments and backslashes.
        val assetPath = HttpHelpers.assetPath(path)
            ?: return send(out, 404, "Not Found", "text/plain", ByteArray(0))
        val stream = try { context.assets.open(assetPath) } catch (_: Exception) { return send(out, 404, "Not Found", "text/plain", ByteArray(0)) }
        stream.use {
            // Closing the connection delimits compressed APK assets without materializing large WASM in memory.
            writeHeaders(out, 200, "OK", HttpHelpers.mime(path), null)
            if (!head) it.copyTo(out)
            out.flush()
        }
    }

    private fun send(out: BufferedOutputStream, code: Int, reason: String, mime: String, body: ByteArray, head: Boolean = false, extra: String = "") {
        writeHeaders(out, code, reason, mime, body.size.toLong(), extra)
        if (!head) out.write(body)
        out.flush()
    }

    private fun writeHeaders(out: BufferedOutputStream, code: Int, reason: String, mime: String, length: Long?, extra: String = "") {
        val h = buildString {
            append("HTTP/1.1 $code $reason\r\nContent-Type: $mime\r\n")
            if (length != null) append("Content-Length: $length\r\n")
            append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: default-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; img-src 'self' data: blob:; connect-src 'self'; worker-src 'self' blob:\r\n")
            append(extra).append("Connection: close\r\n\r\n")
        }
        out.write(h.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun readLine(input: InputStream, max: Int): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= max) {
            val b = input.read(); if (b < 0) return null
            if (b == 10) break
            if (b != 13) bytes.add(b.toByte())
        }
        if (bytes.size > max) return null
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }

    override fun close() {
        running.set(false); try { socket?.close() } catch (_: Exception) {}
        workers.shutdownNow(); acceptThread?.interrupt()
    }
}
