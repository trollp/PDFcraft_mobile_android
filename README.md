# PdfCraft Mobile

PdfCraft Mobile is an **unofficial Android wrapper** around the unmodified open-source PdfCraft 0.8.0 WebAssembly release. It is not an official ArtCraft Team product.

## Architecture

- Kotlin/XML Android shell (`minSdk 24`, `targetSdk 34`) with a hardened WebView.
- An in-process HTTP server binds only to `127.0.0.1` on an OS-selected port. Every route is below a cryptographically random, per-process token.
- A same-origin host page embeds upstream PdfCraft with `?host=parent&webgl`. It uses the upstream `postMessage` protocol and transfers PDF `ArrayBuffer`s through bounded raw HTTP requests—never Base64 and never a JavaScript interface.
- Android Storage Access Framework handles open/create-document, upstream one-or-many file chooser requests, and persisted URI grants. `content://` ACTION_VIEW and ACTION_SEND (`application/pdf`) are supported.
- A received save is streamed to a bounded temporary file, validated, fsynced, and atomically promoted to document-owned app-private recovery. Before the destination picker opens, Android also journals a durable pending-save transaction with its own fsynced bytes. The wrapper never overwrites an existing source URI automatically. Sharing uses a temporary FileProvider URI.

## Feature coverage

Open, persisted last-document reopen, inbound intents, destination-based Save and Save As, new blank-document saving, upstream primary and secondary file choosers, combine/insert selection, durable pending-save recovery, save-copy, share, reload, dirty-change confirmation, startup/error UI, and complete upstream license display are implemented. The bundled editor remains usable offline. External links leave the WebView.

For data safety, every PdfCraft Save asks for a destination. The upstream browser bridge cannot tell Android whether its internal file chooser replaced the primary document or selected a secondary combine/insert input, so automatically writing to a previously opened URI could overwrite the wrong source. The chosen destination is adopted for reopening, but later saves still ask where to write.

The destination-picker transaction and a private copy of its exact bytes survive activity recreation and process death. Cancellation or write failure displays a **Save not completed** dialog; **Retry** repeats the intended save and **Export recovery** writes the retained bytes as a copy without adopting that destination. The overflow menu's **Save recovery** command reopens these choices later. A missing or damaged activity-result journal is always reported visibly and never treated as success. Transaction bytes are deleted only after a successful destination write; PdfCraft's original recovery generations remain app-private.

## Build and install

Requirements: JDK 17+ and Android SDK 34.

```sh
export ANDROID_HOME=/home/troll/Android/Sdk
export ANDROID_SDK_ROOT=/home/troll/Android/Sdk
./gradlew test lint assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Provenance

- Upstream: https://github.com/storytold/pdfcraft
- Bundled release: PdfCraft web 0.8.0
- Original release archive (not packaged in the APK): `third_party/pdfcraft-web-0.8.0.zip`
- SHA-256: `1f9ea51f3c1f71cc719c12814e10f236912fa41ef54a7782c7dd4a619d9eee9a`
- License texts and NOTICE are bundled from the upstream release and available in About.
- The launcher icon is contributor-original simple vector artwork and does not use Adobe or ArtCraft branding.

## Privacy and security

The wrapper makes no network request for editing and does not upload documents. The local server is loopback-only and tokenized. WebView file/content access and mixed content are disabled; only this process's loopback origin and token path may load subresources. Imported files and save bodies are capped at 100 MiB and must have a PDF header. Android backup is disabled so working and recovery PDFs are not copied into device backups. Recovery files are tagged to their source document and are never substituted for another active document.

## Known limits

- PdfCraft's upstream interface is a desktop-style canvas UI; small-screen ergonomics depend on that upstream UI.
- WebView/WebGL support varies by Android System WebView and GPU/driver.
- Large PDFs and the WebAssembly runtime can exceed memory limits on low-memory devices; this wrapper limits document transfer to 100 MiB.
- Upstream PdfCraft 0.8.0 has no host command to request serialization. While PdfCraft reports unsaved changes, toolbar **Save copy** and **Share** refuse with an instruction to use PdfCraft's own Save first; this prevents exporting stale bytes. The upstream bridge still creates an `ArrayBuffer` before its save POST, while Android receives that POST as a stream.
- Retained recovery files are app-private and are removed only when app data is cleared; the toolbar indicates when save recovery is available. Completed pending-transaction copies are removed after the destination write succeeds.
- The native toolbar remains because it is the only reliable access to open/share and process-death recovery actions, but its phone UI is streamlined to a single overflow menu instead of duplicating PdfCraft actions across the bar.
- There was no attached Android device during this build, so physical-device behavior has not yet been validated.
