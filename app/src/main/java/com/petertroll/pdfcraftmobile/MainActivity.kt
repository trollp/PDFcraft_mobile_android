package com.petertroll.pdfcraftmobile

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.snackbar.Snackbar
import com.petertroll.pdfcraftmobile.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), LoopbackServer.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: DocumentRepository
    private lateinit var pendingSaves: PendingSaveStore
    private lateinit var server: LoopbackServer
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var dirty = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDocument(uri, PICKER_GRANTS)
    }
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val pending = runCatching { pendingSaves.requireActive() }.getOrElse { error ->
            showMissingSaveState(error)
            return@registerForActivityResult
        }
        if (uri == null) {
            showPendingSave(pending, "Save canceled. Your private recovery copy is still available.")
            return@registerForActivityResult
        }
        io.execute {
            runCatching {
                when (pending.mode) {
                    PendingSaveMode.ADOPT -> repository.adoptSavedDestination(
                        SavedArtifact(pending.documentId, pending.payload, pending.name), uri, PICKER_GRANTS
                    )
                    PendingSaveMode.EXPORT -> contentResolver.openOutputStream(uri, "wt").use { output ->
                        requireNotNull(output) { "Provider refused output" }
                        pending.payload.inputStream().use { it.copyTo(output) }
                    }
                }
                pendingSaves.complete(pending.id)
            }.onSuccess { runOnUiThread {
                if (pending.mode == PendingSaveMode.ADOPT) binding.toolbar.subtitle = repository.displayName
                toast("PDF saved")
            } }.onFailure { e -> runOnUiThread {
                showPendingSave(pending, "Could not save: ${e.message}. Your private recovery copy was retained.")
            } }
        }
    }
    private val chooseWebFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback.also { fileCallback = null } ?: return@registerForActivityResult
        callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)
        repository = DocumentRepository(this)
        pendingSaves = PendingSaveStore(filesDir)
        server = LoopbackServer(this, repository, this).also { it.start() }
        configureToolbar(); configureWebView()
        binding.retryButton.setOnClickListener { guarded("reload") { reloadPage() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = guarded("exit") { finish() }
        })
        val inbound = inboundUri(intent)
        if (inbound != null) importDocument(inbound, intent.flags) else io.execute {
            repository.restoreLast()
            runOnUiThread { loadHost() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = inboundUri(intent) ?: return
        guarded("open the incoming PDF") { importDocument(uri, intent.flags) }
    }

    private fun configureToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open -> guarded("open another PDF") { openDocument.launch(arrayOf("application/pdf")) }
                R.id.action_export -> exportLatest()
                R.id.action_recovery -> openRecoveryActions()
                R.id.action_share -> shareLatest()
                R.id.action_reload -> guarded("reload") { reloadPage() }
                R.id.action_about -> showAbout()
                else -> return@setOnMenuItemClickListener false
            }; true
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() = with(binding.webView) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.setSupportMultipleWindows(false)
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback ?: return false
                val intent = runCatching { params?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT) }.getOrElse {
                    fileCallback = null; callback.onReceiveValue(null); return false
                }.apply {
                    action = Intent.ACTION_OPEN_DOCUMENT
                    addCategory(Intent.CATEGORY_OPENABLE)
                    val mimeTypes = MimeFilters.normalize(params?.acceptTypes)
                    type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
                    if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                return runCatching { chooseWebFiles.launch(intent); true }.getOrElse {
                    fileCallback = null; callback.onReceiveValue(null); toast("No file picker is available"); false
                }
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { showLoading("Loading PdfCraft…") }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) showFatal("WebView error: ${error?.description}")
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (isAllowed(u)) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }.onFailure { toast("No app can open this link") }
                return true
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                if (isAllowed(request.url)) return null
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream("Blocked non-local resource".toByteArray()))
            }
        }
    }

    private fun isAllowed(uri: Uri): Boolean = uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == server.port &&
        (uri.path ?: "").startsWith("/${server.token}/")

    private fun loadHost() { binding.webView.loadUrl(server.url) }
    private fun reloadPage() { dirty = false; loadHost() }

    private fun importDocument(uri: Uri, grantFlags: Int) {
        if (uri.scheme != "content") return showError("Only content:// PDF documents can be opened")
        showLoading("Opening PDF…")
        io.execute {
            repository.import(uri, grantFlags).onSuccess { runOnUiThread { dirty = false; loadHost() } }
                .onFailure { e -> runOnUiThread { showFatal("Could not open PDF: ${e.message}") } }
        }
    }

    private fun inboundUri(intent: Intent): Uri? {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        return uri?.takeIf { it.scheme == "content" }
    }

    override fun onSaved(saveAs: Boolean, artifact: SavedArtifact) {
        runOnUiThread {
            if (!repository.isActive(artifact)) return@runOnUiThread showError("Save retained as recovery for the previous document; it was not routed to the current document.")
            dirty = false
            val route = SaveRouter.decide()
            if (route == SaveRoute.ORIGINAL) writeOriginal(artifact) else beginCreate(artifact.file, artifact.name, PendingSaveMode.ADOPT, artifact.documentId)
        }
    }

    private fun writeOriginal(artifact: SavedArtifact) = io.execute {
        val uri = repository.originalUri
        runCatching { requireNotNull(uri); repository.writeArtifactTo(artifact, uri) }
            .onSuccess { runOnUiThread { toast("Saved to original document") } }
            .onFailure { e -> runOnUiThread {
                showError("Original could not be written: ${e.message}. Choose a destination; recovery was retained.")
                beginCreate(artifact.file, artifact.name, PendingSaveMode.ADOPT, artifact.documentId)
            } }
    }

    override fun onEvent(json: String) {
        val event = runCatching { JSONObject(json) }.getOrNull() ?: return
        runOnUiThread {
            when (event.optString("type")) {
                "pdfcraft:ready" -> {
                    binding.statusPanel.visibility = View.GONE
                    val retained = repository.hasPendingRecovery() || pendingSaves.hasPendingRecord()
                    binding.toolbar.subtitle = repository.displayName + if (retained) " • save recovery available" else ""
                }
                "pdfcraft:dirty" -> dirty = event.optBoolean("dirty")
                "pdfcraft:failed" -> showFatal(event.optString("error", "PdfCraft could not start"))
            }
        }
    }

    private fun exportLatest() {
        if (!HostActionPolicy.canUseCurrentBytes(dirty)) return toast(HostActionPolicy.DIRTY_MESSAGE)
        io.execute {
            val result = runCatching { repository.latestForExport() }
            runOnUiThread {
                result.onSuccess { snapshot ->
                    if (snapshot == null) toast("Open a PDF first")
                    else beginCreate(snapshot.file, snapshot.name, PendingSaveMode.EXPORT, snapshot.documentId)
                }.onFailure { showError("Could not prepare PDF: ${it.message}") }
            }
        }
    }

    private fun beginCreate(source: File, name: String, mode: PendingSaveMode, documentId: String) {
        io.execute {
            val result = runCatching { pendingSaves.begin(source, name, mode, documentId) }
            runOnUiThread {
                result.onSuccess(::launchCreate).onFailure {
                    val existing = runCatching { pendingSaves.requireActive() }.getOrNull()
                    if (existing != null) showPendingSave(existing, "Finish or export the earlier recovered save first.")
                    else showError("Could not retain save recovery: ${it.message}")
                }
            }
        }
    }

    private fun launchCreate(pending: PendingSaveTransaction) {
        createDocument.launch(FilenameUtil.sanitize(pending.name))
    }

    private fun openRecoveryActions() {
        runCatching { pendingSaves.requireActive() }
            .onSuccess { showPendingSave(it, "A save is waiting for a destination. The recovery bytes are stored privately.") }
            .onFailure {
                if (pendingSaves.hasPendingRecord()) showMissingSaveState(it)
                else toast("No unfinished save recovery is available")
            }
    }

    private fun showPendingSave(pending: PendingSaveTransaction, message: String) {
        AlertDialog.Builder(this)
            .setTitle("Save not completed")
            .setMessage(message)
            .setNegativeButton("Keep recovery", null)
            .setPositiveButton("Retry") { _, _ ->
                runCatching { pendingSaves.changeMode(pending.id, pending.mode) }
                    .onSuccess(::launchCreate).onFailure { showMissingSaveState(it) }
            }
            .setNeutralButton("Export recovery") { _, _ ->
                runCatching { pendingSaves.changeMode(pending.id, PendingSaveMode.EXPORT) }
                    .onSuccess(::launchCreate).onFailure { showMissingSaveState(it) }
            }.show()
    }

    private fun showMissingSaveState(error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle("Save could not continue")
            .setMessage("Android returned a save result, but its recovery transaction is missing or damaged. No source document was overwritten. ${error.message.orEmpty()}")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun shareLatest() {
        if (!HostActionPolicy.canUseCurrentBytes(dirty)) return toast(HostActionPolicy.DIRTY_MESSAGE)
        io.execute {
            runCatching {
                val snapshot = repository.latestForExport() ?: error("Open a PDF first")
                val dir = File(cacheDir, "shared").also { it.mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, FilenameUtil.sanitize(snapshot.name)); snapshot.file.copyTo(target, true)
                FileProvider.getUriForFile(this, "$packageName.files", target)
            }.onSuccess { uri -> runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "Share PDF"))
            } }.onFailure { e -> runOnUiThread { showError("Could not prepare share: ${e.message}") } }
        }
    }

    private fun guarded(action: String, proceed: () -> Unit) {
        if (!dirty) return proceed()
        AlertDialog.Builder(this).setTitle("Unsaved changes")
            .setMessage("PdfCraft reports unsaved changes. Discard them and $action?")
            .setNegativeButton("Keep editing", null).setPositiveButton("Discard") { _, _ -> dirty = false; proceed() }.show()
    }

    private fun showAbout() {
        val notices = listOf("LICENSE-MIT", "LICENSE-APACHE", "NOTICE").joinToString("\n\n==============================\n\n") { name ->
            "$name\n\n" + assets.open("pdfcraft-web/$name").bufferedReader().use { it.readText() }
        }
        val text = """PdfCraft Mobile

An unofficial Android wrapper around the upstream open-source PdfCraft 0.8.0 WebAssembly release. It is not endorsed by the ArtCraft Team.

Upstream source: https://github.com/storytold/pdfcraft
Release ZIP SHA-256: 1f9ea51f3c1f71cc719c12814e10f236912fa41ef54a7782c7dd4a619d9eee9a

Privacy: the editor and its loopback server run entirely on this device. The server binds only to 127.0.0.1 behind a random per-process path. PDFs are not uploaded by this wrapper. External links are opened in another app.

Licenses and notices

$notices"""
        val view = TextView(this).apply { setPadding(48, 24, 48, 24); setTextIsSelectable(true); this.text = text }
        AlertDialog.Builder(this).setTitle("About PdfCraft Mobile").setView(ScrollView(this).apply { addView(view) }).setPositiveButton("Close", null).show()
    }

    private fun showLoading(message: String) { binding.statusPanel.visibility = View.VISIBLE; binding.progress.visibility = View.VISIBLE; binding.retryButton.visibility = View.GONE; binding.statusText.text = message }
    private fun showFatal(message: String) { binding.statusPanel.visibility = View.VISIBLE; binding.progress.visibility = View.GONE; binding.retryButton.visibility = View.VISIBLE; binding.statusText.text = message }
    private fun showError(message: String) { Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show() }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null); fileCallback = null
        binding.webView.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        server.close(); io.shutdownNow(); super.onDestroy()
    }

    companion object {
        private const val PICKER_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    }
}
