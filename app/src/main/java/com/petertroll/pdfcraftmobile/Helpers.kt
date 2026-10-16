package com.petertroll.pdfcraftmobile

import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object HttpHelpers {
    /** Returns a safe relative asset path, or null for malformed/traversing targets. */
    fun normalizeTarget(target: String): String? {
        val raw = target.substringBefore('?').substringBefore('#')
        val decoded = try { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) } catch (_: Exception) { return null }
        if (!decoded.startsWith('/') || decoded.indexOf('\u0000') >= 0 || decoded.contains('\\')) return null
        val parts = decoded.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "wasm" -> "application/wasm"
        "pdf" -> "application/pdf"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "json" -> "application/json; charset=utf-8"
        else -> "application/octet-stream"
    }

    /** Maps the public loopback URL namespace to the directory packaged in the APK. */
    fun assetPath(path: String): String? =
        if (path.startsWith("pdfcraft/") && path.length > "pdfcraft/".length)
            "pdfcraft-web/" + path.removePrefix("pdfcraft/") else null
}

object PdfHeader {
    /** PDF readers permit leading white space; tolerate an UTF-8 BOM as well. */
    fun isPdf(input: InputStream): Boolean {
        val probe = ByteArray(1024)
        val count = input.read(probe)
        if (count <= 0) return false
        var i = if (count >= 3 && probe[0] == 0xef.toByte() && probe[1] == 0xbb.toByte() && probe[2] == 0xbf.toByte()) 3 else 0
        while (i < count && probe[i].toInt().toChar() in charArrayOf(' ', '\t', '\r', '\n', '\u000c', '\u0000')) i++
        return i + 5 <= count && probe.copyOfRange(i, i + 5)
            .contentEquals("%PDF-".toByteArray(StandardCharsets.US_ASCII))
    }
}

object HostActionPolicy {
    fun canUseCurrentBytes(dirty: Boolean): Boolean = !dirty
    const val DIRTY_MESSAGE = "Save your changes with PdfCraft’s Save command first, then try again."
}

object MimeFilters {
    /** Converts browser accept values (MIME types and extensions) to SAF MIME types. */
    fun normalize(values: Array<String>?): Array<String> {
        val mapped = values.orEmpty().flatMap { it.split(',') }.mapNotNull { raw ->
            when (val value = raw.trim().lowercase()) {
                ".pdf" -> "application/pdf"
                ".png" -> "image/png"
                ".jpg", ".jpeg" -> "image/jpeg"
                ".webp" -> "image/webp"
                else -> value.takeIf { '/' in it && !it.startsWith('.') }
            }
        }.distinct()
        return mapped.ifEmpty { listOf("application/pdf") }.toTypedArray()
    }
}

object RecoveryPolicy {
    fun belongsTo(recoveryOwner: String?, activeDocument: String?): Boolean =
        recoveryOwner != null && recoveryOwner == activeDocument
}

object FilenameUtil {
    fun sanitize(value: String?): String {
        var name = value.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().trim('.')
        if (name.isBlank()) name = "document.pdf"
        if (!name.endsWith(".pdf", true)) name += ".pdf"
        return name.take(120).let { if (it.endsWith(".pdf", true)) it else it.dropLast(4) + ".pdf" }
    }
}

enum class SaveRoute { ORIGINAL, CREATE_DOCUMENT }
object SaveRouter {
    fun decide(): SaveRoute {
        // PdfCraft's browser bridge does not report whether an internal chooser opened a new
        // primary document or selected a secondary combine/insert input. Never risk writing
        // newly opened bytes over a stale Android-side source URI.
        return SaveRoute.CREATE_DOCUMENT
    }
}
