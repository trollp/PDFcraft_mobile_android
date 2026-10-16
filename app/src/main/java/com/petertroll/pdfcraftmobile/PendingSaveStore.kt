package com.petertroll.pdfcraftmobile

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

enum class PendingSaveMode { ADOPT, EXPORT }

data class PendingSaveTransaction(
    val id: String,
    val payload: File,
    val name: String,
    val mode: PendingSaveMode,
    val documentId: String
)

/**
 * A small on-disk transaction journal for Android's create-document round trip.
 * The journal owns a private copy, so neither activity recreation, process death,
 * nor deletion of a cache snapshot can invalidate bytes waiting for a destination.
 */
class PendingSaveStore(filesDir: File) {
    private val directory = File(filesDir, "pending-save")
    private val active = File(directory, "active")

    @Synchronized
    @Throws(IOException::class)
    fun begin(source: File, name: String, mode: PendingSaveMode, documentId: String): PendingSaveTransaction {
        if (active.exists()) throw IOException("Another save is waiting for a destination")
        if (!source.isFile) throw IOException("Recovery bytes are unavailable")
        directory.mkdirs()
        val id = UUID.randomUUID().toString()
        val payload = File(directory, "$id.pdf")
        try {
            FileOutputStream(payload).use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
                output.fd.sync()
            }
            val transaction = PendingSaveTransaction(id, payload, FilenameUtil.sanitize(name), mode, documentId)
            writeRecord(transaction)
            return transaction
        } catch (error: Exception) {
            payload.delete()
            throw if (error is IOException) error else IOException("Could not retain recovery bytes", error)
        }
    }

    @Synchronized
    fun activeOrNull(): PendingSaveTransaction? {
        if (!active.exists()) return null
        return readRecord()
    }

    fun hasPendingRecord(): Boolean = active.exists()

    @Synchronized
    @Throws(IOException::class)
    fun requireActive(): PendingSaveTransaction = activeOrNull()
        ?: throw IOException("No pending save transaction was found")

    @Synchronized
    @Throws(IOException::class)
    fun changeMode(id: String, mode: PendingSaveMode): PendingSaveTransaction {
        val current = requireActive()
        if (current.id != id) throw IOException("Pending save transaction changed")
        return current.copy(mode = mode).also(::writeRecord)
    }

    @Synchronized
    @Throws(IOException::class)
    fun complete(id: String) {
        val current = requireActive()
        if (current.id != id) throw IOException("Pending save transaction changed")
        if (!active.delete()) throw IOException("Could not clear pending save journal")
        current.payload.delete()
    }

    private fun writeRecord(transaction: PendingSaveTransaction) {
        directory.mkdirs()
        val temporary = File(directory, "active.tmp")
        try {
            FileOutputStream(temporary).use { file ->
                val output = DataOutputStream(BufferedOutputStream(file))
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(transaction.id)
                output.writeUTF(transaction.payload.name)
                output.writeUTF(transaction.name)
                output.writeUTF(transaction.mode.name)
                output.writeUTF(transaction.documentId)
                output.flush()
                file.fd.sync()
            }
            if (!temporary.renameTo(active)) {
                temporary.copyTo(active, overwrite = true)
                temporary.delete()
            }
        } finally {
            temporary.delete()
        }
    }

    private fun readRecord(): PendingSaveTransaction = try {
        DataInputStream(BufferedInputStream(active.inputStream())).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Pending save journal is corrupt")
            val id = input.readUTF()
            val payloadName = input.readUTF()
            val name = input.readUTF()
            val mode = runCatching { PendingSaveMode.valueOf(input.readUTF()) }
                .getOrElse { throw IOException("Pending save journal has an invalid mode", it) }
            val documentId = input.readUTF()
            if (id.isBlank() || documentId.isBlank() || payloadName != "$id.pdf") throw IOException("Pending save journal is invalid")
            val payload = File(directory, payloadName)
            if (!payload.isFile) throw IOException("Retained recovery bytes are missing")
            PendingSaveTransaction(id, payload, FilenameUtil.sanitize(name), mode, documentId)
        }
    } catch (error: IOException) {
        throw error
    } catch (error: Exception) {
        throw IOException("Pending save journal is corrupt", error)
    }

    private companion object {
        const val MAGIC = 0x50444653 // PDFS
        const val VERSION = 1
    }
}