package com.petertroll.pdfcraftmobile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class HelpersTest {
    @Test fun normalizesTargetsAndDropsQuery() {
        assertEquals("token/pdfcraft/index.html", HttpHelpers.normalizeTarget("/token/pdfcraft/index.html?host=parent"))
    }
    @Test fun rejectsTraversalAndMalformedTargets() {
        assertNull(HttpHelpers.normalizeTarget("/token/../secret"))
        assertNull(HttpHelpers.normalizeTarget("/token/%2e%2e/secret"))
        assertNull(HttpHelpers.normalizeTarget("/token\\secret"))
        assertNull(HttpHelpers.normalizeTarget("relative"))
    }
    @Test fun mapsCriticalMimeTypes() {
        assertEquals("application/wasm", HttpHelpers.mime("app.wasm"))
        assertEquals("text/javascript; charset=utf-8", HttpHelpers.mime("app.js"))
        assertEquals("application/pdf", HttpHelpers.mime("FILE.PDF"))
        assertEquals("text/html; charset=utf-8", HttpHelpers.mime("index.html"))
    }
    @Test fun mapsPublicAssetPathToPackagedDirectory() {
        val mapped = HttpHelpers.assetPath("pdfcraft/index.html")
        assertEquals("pdfcraft-web/index.html", mapped)
        assertTrue(File("src/main/assets/$mapped").isFile)
        assertNull(HttpHelpers.assetPath("other/index.html"))
    }
    @Test fun validatesPdfHeaderWithAllowedPrefix() {
        assertTrue(PdfHeader.isPdf(ByteArrayInputStream("\uFEFF \r\n%PDF-1.7".toByteArray())))
        assertFalse(PdfHeader.isPdf(ByteArrayInputStream("not a pdf".toByteArray())))
    }
    @Test fun sanitizesFilenames() {
        assertEquals("report.pdf", FilenameUtil.sanitize("folder/report.pdf"))
        assertEquals("bad_name_.pdf", FilenameUtil.sanitize("bad:name?.pdf"))
        assertEquals("document.pdf", FilenameUtil.sanitize("..."))
        assertTrue(FilenameUtil.sanitize("x".repeat(200)).length <= 120)
    }
    @Test fun hostPageContainsProtocolAndBinaryTransport() {
        val page = HostPage.html("abc123")
        assertTrue(page.contains("pdfcraft:ready")); assertTrue(page.contains("pdfcraft:open"))
        assertTrue(page.contains("pdfcraft:save")); assertTrue(page.contains("pdfcraft:dirty")); assertTrue(page.contains("pdfcraft:failed"))
        assertTrue(page.contains("arrayBuffer()")); assertTrue(page.contains("body:m.bytes"))
        assertFalse(page.lowercase().contains("base64"))
        assertTrue(page.contains("/abc123/pdfcraft/index.html?host=parent&amp;webgl"))
        // PdfCraft 0.8.0 accepts only pdfcraft:open; there is no host request-save command.
        assertFalse(page.contains("pdfcraft:request-save"))
        assertFalse(HostActionPolicy.canUseCurrentBytes(true))
        assertTrue(HostActionPolicy.canUseCurrentBytes(false))
    }
    @Test fun recoveryMustBelongToActiveDocument() {
        assertTrue(RecoveryPolicy.belongsTo("doc-a", "doc-a"))
        assertFalse(RecoveryPolicy.belongsTo("doc-a", "doc-b"))
        assertFalse(RecoveryPolicy.belongsTo(null, "doc-a"))
    }
    @Test fun normalizesBrowserAcceptFiltersForAndroidPicker() {
        assertArrayEquals(arrayOf("application/pdf"), MimeFilters.normalize(arrayOf(".pdf")))
        assertArrayEquals(arrayOf("application/pdf", "image/png"),
            MimeFilters.normalize(arrayOf(".pdf,image/png")))
        assertArrayEquals(arrayOf("application/pdf"), MimeFilters.normalize(emptyArray()))
    }
    @Test fun routesSaves() {
        assertEquals(SaveRoute.CREATE_DOCUMENT, SaveRouter.decide())
    }
}
