package com.petertroll.pdfcraftmobile

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

data class SavedArtifact(val documentId: String, val file: File, val name: String)
data class ExportSnapshot(val documentId: String, val file: File, val name: String)

class DocumentRepository(private val context: Context) {
    companion object { const val MAX_PDF_BYTES = 100L * 1024 * 1024 }
    private val prefs = context.getSharedPreferences("documents", Context.MODE_PRIVATE)
    val currentFile = File(context.filesDir, "working/current.pdf")

    @Volatile var originalUri: Uri? = null; private set
    @Volatile var displayName: String = "document.pdf"; private set
    @Volatile private var documentId: String? = null

    init {
        displayName = prefs.getString("last_name", "document.pdf") ?: "document.pdf"
        documentId = prefs.getString("last_document_id", null)
    }


    /** Imports atomically. A failed import leaves the active document and all recoveries intact. */
    @Synchronized
    fun import(uri: Uri, grantFlags: Int, restoredDocumentId: String? = null): Result<Unit> = runCatching {
        require(uri.scheme == "content") { "Only content:// PDF documents are supported" }
        persistGrantedPermissions(uri, grantFlags)
        val name = FilenameUtil.sanitize(queryName(uri))
        currentFile.parentFile?.mkdirs()
        val tmp = File(currentFile.parentFile, "current-${UUID.randomUUID()}.tmp")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Document provider did not return data" }
                FileOutputStream(tmp).use { output -> copyBounded(input, output); output.fd.sync() }
            }
            if (!tmp.inputStream().use(PdfHeader::isPdf)) throw IOException("Selected file is not a PDF")
            promote(tmp, currentFile)
        } finally {
            tmp.delete()
        }
        val id = restoredDocumentId ?: UUID.randomUUID().toString()
        originalUri = uri
        displayName = name
        documentId = id
        prefs.edit().putString("last_uri", uri.toString()).putString("last_name", name)
            .putString("last_document_id", id).apply()
    }

    @Synchronized
    fun restoreLast(): Boolean {
        val text = prefs.getString("last_uri", null) ?: return false
        val id = prefs.getString("last_document_id", null) ?: return false
        val uri = Uri.parse(text)
        if (uri.scheme != "content" || context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return false
        return import(uri, 0, id).isSuccess
    }

    /** Streams a save to a bounded temporary file, fsyncs, then atomically publishes it. */
    @Synchronized
    fun writeRecovery(input: InputStream, length: Long, suggestedName: String): SavedArtifact {
        if (length < 0 || length > MAX_PDF_BYTES) throw IOException("PDF exceeds 100 MiB limit")
        // PdfCraft may open its primary input through the browser chooser or create a blank
        // document, neither of which carries Android document identity. Start an unsourced
        // session on first save; the Activity will always ask for a safe destination.
        val owner = documentId ?: UUID.randomUUID().toString().also {
            documentId = it
            originalUri = null
            displayName = FilenameUtil.sanitize(suggestedName)
            prefs.edit().remove("last_uri").putString("last_name", displayName)
                .putString("last_document_id", it).apply()
        }
        val dir = File(context.filesDir, "recovery").also { it.mkdirs() }
        val generation = UUID.randomUUID().toString()
        val tmp = File(dir, "$generation.tmp")
        val destination = File(dir, "$generation.pdf")
        try {
            FileOutputStream(tmp).use { output -> copyExactBounded(input, output, length); output.fd.sync() }
            if (!tmp.inputStream().use(PdfHeader::isPdf)) throw IOException("Saved data is not a PDF")
            promote(tmp, destination)
        } finally {
            tmp.delete()
        }
        val artifact = SavedArtifact(owner, destination, FilenameUtil.sanitize(suggestedName))
        displayName = artifact.name
        val records = prefs.getStringSet("recoveries", emptySet()).orEmpty().toMutableSet()
        records += encodeRecovery(artifact)
        prefs.edit().putStringSet("recoveries", records).putString("recovery_owner", owner)
            .putString("recovery_path", destination.absolutePath).putString("recovery_name", artifact.name).apply()
        return artifact
    }

    @Synchronized fun isActive(artifact: SavedArtifact): Boolean = RecoveryPolicy.belongsTo(artifact.documentId, documentId)

    @Synchronized
    fun canWriteOriginal(): Boolean {
        val uri = originalUri ?: return false
        return try { context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false }
        catch (_: Exception) { false }
    }

    @Synchronized
    fun writeArtifactTo(artifact: SavedArtifact, uri: Uri) {
        if (!isActive(artifact) || !artifact.file.isFile) throw IOException("Saved output belongs to a different document")
        context.contentResolver.openOutputStream(uri, "wt").use { out ->
            requireNotNull(out) { "Document provider refused output" }
            artifact.file.inputStream().use { it.copyTo(out) }
        }
    }

    /** Save As adopts the destination and refreshes working state; toolbar Save copy does not. */
    @Synchronized
    fun adoptSavedDestination(artifact: SavedArtifact, uri: Uri, grantFlags: Int) {
        writeArtifactTo(artifact, uri)
        persistGrantedPermissions(uri, grantFlags)
        artifact.file.inputStream().use { input ->
            val tmp = File(currentFile.parentFile, "adopt-${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(tmp).use { output -> input.copyTo(output); output.fd.sync() }
                promote(tmp, currentFile)
            } finally { tmp.delete() }
        }
        originalUri = uri
        displayName = FilenameUtil.sanitize(queryName(uri) ?: artifact.name)
        prefs.edit().putString("last_uri", uri.toString()).putString("last_name", displayName)
            .putString("last_document_id", documentId).apply()
    }

    @Synchronized
    fun latestForExport(): ExportSnapshot? {
        val id = documentId ?: return null
        val recovery = recoveryRecords().filter { RecoveryPolicy.belongsTo(it.documentId, id) && it.file.isFile }
            .maxByOrNull { it.file.lastModified() }
        val source = when {
            recovery != null && RecoveryPolicy.belongsTo(recovery.documentId, id) && recovery.file.isFile -> recovery.file to recovery.name
            currentFile.isFile -> currentFile to displayName
            else -> return null
        }
        // A picker/chooser can remain open while another document is imported. Give the caller
        // an immutable generation rather than a mutable working-file path.
        val dir = File(context.cacheDir, "export-snapshots").also { it.mkdirs() }
        val snapshot = File(dir, "${UUID.randomUUID()}.pdf")
        source.first.copyTo(snapshot, overwrite = false)
        return ExportSnapshot(id, snapshot, source.second)
    }

    @Synchronized fun hasPendingRecovery(): Boolean =
        recoveryRecords().any { it.file.isFile && !RecoveryPolicy.belongsTo(it.documentId, documentId) }

    private fun persistGrantedPermissions(uri: Uri, flags: Int) {
        if (uri.scheme != "content" || flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        for (mode in intArrayOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            if (flags and mode != 0) try { context.contentResolver.takePersistableUriPermission(uri, mode) }
            catch (_: SecurityException) { /* provider may decline either mode independently */ }
        }
    }

    private fun recoveryRecords(): List<SavedArtifact> {
        val records = prefs.getStringSet("recoveries", emptySet()).orEmpty().mapNotNull { record ->
            val fields = record.split('\n', limit = 3)
            if (fields.size == 3) SavedArtifact(fields[0], File(fields[1]), fields[2]) else null
        }.toMutableList()
        // Read the pre-registry single recovery metadata for upgrades without deleting it.
        val legacyOwner = prefs.getString("recovery_owner", null)
        val legacyPath = prefs.getString("recovery_path", null)
        if (legacyOwner != null && legacyPath != null && records.none { it.file.absolutePath == legacyPath }) {
            records += SavedArtifact(legacyOwner, File(legacyPath), prefs.getString("recovery_name", "document.pdf")!!)
        }
        return records
    }

    private fun encodeRecovery(artifact: SavedArtifact): String =
        "${artifact.documentId}\n${artifact.file.absolutePath}\n${artifact.name}"

    private fun queryName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else uri.lastPathSegment
        } finally { cursor?.close() }
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream) {
        val buf = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n
            if (total > MAX_PDF_BYTES) throw IOException("PDF exceeds 100 MiB limit")
            output.write(buf, 0, n)
        }
    }

    private fun copyExactBounded(input: InputStream, output: java.io.OutputStream, expected: Long) {
        val buf = ByteArray(64 * 1024); var remaining = expected
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("Incomplete save body")
            output.write(buf, 0, n); remaining -= n
        }
    }

    private fun promote(source: File, destination: File) {
        if (!source.renameTo(destination)) { source.copyTo(destination, overwrite = true); source.delete() }
    }
}
