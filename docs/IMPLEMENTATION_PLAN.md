# PdfCraft Mobile implementation plan

## Objective
Build an installable Android application that runs the upstream PdfCraft 0.8.0 WebAssembly application locally and offline, with Android-native document open/save/share integration.

## Architecture
- Kotlin/XML Android app, minSdk 24, compile/target SDK 34.
- Bundle the unmodified official `pdfcraft-web-0.8.0` release under app assets.
- Serve the web application from an in-process loopback-only HTTP server on a random port and unguessable per-process token path. This supplies correct JavaScript/WASM MIME types and a secure-context `localhost` origin.
- Load a same-origin host page containing PdfCraft in an iframe with `?host=parent&webgl`.
- Use PdfCraft's documented `postMessage` bridge:
  - receive `pdfcraft:ready`, `pdfcraft:dirty`, `pdfcraft:failed`, and `pdfcraft:save`;
  - send `pdfcraft:open` with an ArrayBuffer.
- Transfer PDFs through loopback HTTP endpoints, not Base64/JavascriptInterface payloads.
- Android uses Storage Access Framework for open and create/save-copy, persists URI grants, handles ACTION_VIEW/SEND, and keeps a private recovery copy before external writes.

## Core requirements
1. Home/shell actions: Open PDF, save/export pending output, Share current output, About/licenses.
2. Open PDFs from picker, ACTION_VIEW, and ACTION_SEND.
3. Reopen last persisted document when permission remains available.
4. Use upstream PdfCraft UI and engine for viewing, text search, comments, form filling, page organization, combine/extract/split, encryption, and other web-supported tools.
5. Save behavior:
   - receive bytes from PdfCraft;
   - persist an app-private recovery copy first;
   - normal Save writes to the original writable URI when possible;
   - Save As invokes ACTION_CREATE_DOCUMENT;
   - on failure, retain recovery output and show a recoverable error.
6. Share via cache FileProvider URI.
7. Dirty-document back handling and lifecycle-safe state.
8. WebView hardening:
   - JavaScript and DOM storage enabled only for the bundled app;
   - block non-loopback subresources;
   - external navigations open in the system browser;
   - disable cleartext to non-local origins through interception;
   - do not expose a broad JavascriptInterface.
9. Robust loopback server:
   - bind only 127.0.0.1;
   - random OS-selected port and random token prefix;
   - MIME handling for HTML, JS, WASM and PDF;
   - GET/HEAD and POST body handling;
   - bounded body sizes and timeouts;
   - path traversal prevention;
   - clean shutdown.
10. Responsive Android toolbar around full-screen WebView, progress/loading/error states, and offline/privacy explanation.
11. Include upstream MIT/Apache licenses, NOTICE, source URL, exact version and release SHA-256. Label the wrapper as unofficial and avoid claiming ArtCraft endorsement.
12. Unit tests for HTTP request/path parsing, MIME selection, host-page protocol strings, filename sanitization, and save-state decisions where practical.

## Verification
- `./gradlew test lint assembleDebug` with SDK 34.
- Inspect lint report and unit test XML.
- Verify APK with apksigner/aapt.
- Confirm bundled WASM, NOTICE and licenses are present.
- If no ADB device is attached, report physical-device validation as unavailable rather than claiming it.
