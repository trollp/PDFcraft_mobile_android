package com.petertroll.pdfcraftmobile

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class PendingSaveStoreTest {
    private val pdf = "%PDF-1.7\ntransaction bytes".toByteArray()

    @Test fun pendingSaveSurvivesANewStoreInstanceAndOwnsItsBytes() {
        val root = Files.createTempDirectory("pending-save").toFile()
        val source = File(root, "source.pdf").apply { writeBytes(pdf) }

        val begun = PendingSaveStore(root).begin(
            source = source,
            name = "edited.pdf",
            mode = PendingSaveMode.ADOPT,
            documentId = "document-1"
        )
        source.delete()

        val restored = PendingSaveStore(root).requireActive()
        assertEquals(begun.id, restored.id)
        assertEquals("edited.pdf", restored.name)
        assertEquals(PendingSaveMode.ADOPT, restored.mode)
        assertEquals("document-1", restored.documentId)
        assertArrayEquals(pdf, restored.payload.readBytes())
    }

    @Test fun successfulCompletionClearsStateAndPrivatePayload() {
        val root = Files.createTempDirectory("pending-save").toFile()
        val source = File(root, "source.pdf").apply { writeBytes(pdf) }
        val store = PendingSaveStore(root)
        val transaction = store.begin(source, "copy.pdf", PendingSaveMode.EXPORT, "document-1")

        store.complete(transaction.id)

        assertFalse(transaction.payload.exists())
        assertNull(PendingSaveStore(root).activeOrNull())
    }

    @Test fun unresolvedSaveCannotBeSilentlyReplaced() {
        val root = Files.createTempDirectory("pending-save").toFile()
        val first = File(root, "first.pdf").apply { writeBytes(pdf) }
        val second = File(root, "second.pdf").apply { writeBytes(pdf + 1) }
        val store = PendingSaveStore(root)
        val original = store.begin(first, "first.pdf", PendingSaveMode.ADOPT, "one")

        assertThrows(IOException::class.java) {
            store.begin(second, "second.pdf", PendingSaveMode.EXPORT, "two")
        }
        assertEquals(original.id, PendingSaveStore(root).requireActive().id)
    }

    @Test fun missingOrCorruptActivityResultStateIsAnErrorNotANoOp() {
        val root = Files.createTempDirectory("pending-save").toFile()
        assertThrows(IOException::class.java) { PendingSaveStore(root).requireActive() }

        File(root, "pending-save/active").apply { parentFile!!.mkdirs(); writeText("broken") }
        assertThrows(IOException::class.java) { PendingSaveStore(root).requireActive() }
    }
}