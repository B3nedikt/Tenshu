package app.tenshu.sdk

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// JS-side tests for :tenshu-sdk-js. Exercises the full in-process runtime
// through the public SDK surface — no internal mocks, no :core-test-utils
// fixtures (the production wiring rule from JsRuntime.kt's header still
// applies).
//
// Tests share a single JsRuntime singleton across the test class, which is
// realistic — the JS bundle has exactly one runtime per page load. The
// underlying IndexedDB also persists across test runs, so tests use unique
// folder paths (timestamp-suffixed) to stay independent of stale state.
//
// Extends [SdkRuntimeTest]: these file ops build the executionContext and start
// the SyncScheduler, which — when the runtime booted sync-enabled from a prior
// run's persisted RECOVERY_PHRASE — would otherwise construct the relay under the
// production default before any configure(). The base pins the relay sink first.
class JsRuntimeTest : SdkRuntimeTest() {

    @Test
    fun runtimeStatus_isReady() = runTest {
        TenshuSdk.awaitReady().await()
        val status = TenshuSdk.runtimeStatus()
        assertEquals(true, status.ready)
    }

    // Full create → list round-trip: create succeeds, then a subsequent
    // list of the parent folder shows the new folder. Proves that
    // `addDataToArray` under `/` actually attaches (slice 8 wired
    // `initFileService` so the root `/` Folder entity exists).
    //
    // Path is timestamped because IndexedDB persists across test runs —
    // a hard-coded path would already exist on the second run and
    // `createFolder` would resolve `false` (`AlreadyExistsError` mapped to
    // false per FileSystem.kt's idempotent-seed semantics).
    @Test
    fun createFolder_thenList_roundTrips() = runTest {
        val name = "/JsRuntimeTest-${Date.now().toLong()}"
        val created = FileSystem.createFolder(name).await()
        assertTrue(created, "FileSystem.createFolder should resolve true on first creation")

        val listed = FileSystem.list("/").await()
        val paths = listed.map { it.path as String }
        assertTrue(
            paths.contains(name),
            "FileSystem.list(\"/\") should contain the newly created folder; got $paths",
        )
    }

    // W11 boundary-contract guard. The 0 B family — no size, no date, folders stuck at
    // "0 items", `details` returning null — shipped from Phase C to 2026-08-28 behind a green
    // suite, because NOTHING asserted the shape of a listing row. Both sides compiled: the
    // Kotlin walker produced whatever `composite.fields` held, and the TS type declared what
    // the UI hoped for, with no test comparing them.
    //
    // ⚠ This is the assertion that was missing. It is deliberately about the emitted CONTRACT
    // (the keys `web/sdk/src/files.ts` publishes), not about the mapper's internals — a future
    // rewrite may change how the row is built, but not what it carries.
    @Test
    fun list_rowCarriesTheFullPublishedContract() = runTest {
        val stamp = Date.now().toLong()
        val fileName = "JsRuntimeTest-contract-$stamp.bin"
        val folderPath = "/JsRuntimeTest-contract-dir-$stamp"
        val payload = ByteArray(4242) { 7 }

        assertTrue(FileSystem.uploadFile("/", fileName, payload).await())
        assertTrue(FileSystem.createFolder(folderPath).await())

        val rows = FileSystem.list("/").await()

        val file = rows.firstOrNull { it.path == "/$fileName" }
        assertNotNull(file, "the uploaded file should be listed")
        assertEquals(4242.0, file.size, "size must reach the bridge — this is the 0 B guard")
        assertTrue(file.lastModified > 0.0, "lastModified must be populated, not absent")
        assertEquals(false, file.isFolder, "a file must not claim to be a folder")

        val folder = rows.firstOrNull { it.path == folderPath }
        assertNotNull(folder, "the created folder should be listed")
        assertEquals(true, folder.isFolder, "a folder must be discriminable without `files`")
        assertEquals(0.0, folder.itemCount, "an empty folder reports 0 children")

        // `details` returned null for every path until W11; pin that it resolves.
        // ⚠ Result-shaped since the T? work: assert the read SUCCEEDED before reading the row,
        // or a failed read passes as "no such file" — the exact confusion being removed.
        val detailsResult = FileSystem.details("/$fileName").await()
        assertTrue(detailsResult.ok as Boolean, "details must succeed for a file that exists")
        val details = detailsResult.row
        assertNotNull(details, "details must resolve a row for a file that exists")
        assertEquals(4242.0, details.size)
    }

    // Slice 10 round-trip: upload bytes, list shows the file, getData
    // returns the same bytes. Exercises the IDB-backed FileDatabase end-
    // to-end through the public SDK surface.
    @Test
    fun uploadFile_thenGetData_roundTrips() = runTest {
        val fileName = "JsRuntimeTest-upload-${Date.now().toLong()}.bin"
        val payload = byteArrayOf(1, 2, 3, 4, 5, -1, -128, 127)

        val uploaded = FileSystem.uploadFile("/", fileName, payload).await()
        assertTrue(uploaded, "FileSystem.uploadFile should resolve true on first upload")

        val listed = FileSystem.list("/").await()
        val paths = listed.map { it.path as String }
        assertTrue(
            paths.contains("/$fileName"),
            "FileSystem.list(\"/\") should contain the uploaded file; got $paths",
        )

        val read = FileSystem.getData("/$fileName").await()
        assertTrue(read.ok as Boolean, "getData should succeed for a file just uploaded here")
        val readBack = read.bytes as ByteArray?
        assertNotNull(readBack, "FileSystem.getData should return bytes for the uploaded file")
        assertContentEquals(
            payload,
            readBack,
            "FileSystem.getData should return the exact bytes that were uploaded",
        )
    }

    // Slice 11: upload a real PNG (generated via canvas to dodge the
    // pain of hand-crafting a valid zlib-compressed IDAT chunk), ask for
    // a preview, expect non-empty encoded bytes back. Doesn't pixel-
    // compare — that's brittle across browser image-codec versions —
    // but does assert the canvas pipeline actually decoded + re-encoded
    // something. A null result here would mean either the bytes weren't
    // found in the FileDatabase or `createImageBitmap` rejected the input.
    // ⚠ Deliberately format-agnostic: since W2b these bytes come from the
    // minted `LIST` class, not the WebP fallback. The two
    // `webUpload_mints*` tests below are what pin which.
    @Test
    fun uploadFile_thenGetPreview_returnsEncodedBytes() = runTest {
        val fileName = "JsRuntimeTest-preview-${Date.now().toLong()}.png"
        val png = makeOpaqueRedPng(2, 2)

        val uploaded = FileSystem.uploadFile("/", fileName, png).await()
        assertTrue(uploaded, "FileSystem.uploadFile should resolve true on first upload")

        val previewResult = FileSystem.getPreview("/$fileName", 100, 100).await()
        assertTrue(previewResult.ok as Boolean, "getPreview should succeed for an uploaded PNG")
        val preview = previewResult.bytes as ByteArray?
        assertNotNull(preview, "FileSystem.getPreview should return bytes for the PNG")
        assertTrue(
            preview.isNotEmpty(),
            "FileSystem.getPreview should return non-empty encoded bytes",
        )
    }

    // W2b (`drive-web-parity.md`): a file uploaded IN a browser must arrive on every
    // other device WITH its stored thumbnail ladder. Before W2b it never did —
    // `:tenshu-sdk-js` registered no `GetPreview` implementation, so
    // `UploadFileImplementation.renderThumbnail` hit "No implementation found for
    // operation" and its own best-effort catch swallowed it.
    //
    // ⚠⚠ These assert on the ENCODED FORMAT, and that is the point, not incidental. The
    // public `getPreview` surface returns bytes either way — the minted stored class or,
    // failing that, the local-bytes WebP fallback — so "non-empty bytes" cannot tell a
    // working mint from a broken one. The format can: the ladder stores JPEG at every rung,
    // the fallback encodes WebP. A WebP here means the mint did not run.
    //
    // ⚠ `LIST` was PNG until 2026-08-31. PNG is lossless, so on photographic content it
    // barely compresses — measured ~9× larger than the lossy classes in a real realm. The
    // whole ladder is JPEG now; see `UploadFileImplementation`'s note for why not WebP.
    //
    // ⚠ They live in THIS suite, not one of their own, because the suite shares a single
    // `JsRuntime` and `SessionTest` purges the realm — any class sorting after it starts
    // with no realm to upload into.
    @Test
    fun webUpload_mintsTheJpegListClass() = runTest {
        TenshuSdk.awaitReady().await()
        val fileName = "JsRuntimeTest-mint-list-${Date.now().toLong()}.png"
        assertTrue(
            FileSystem.uploadFile("/", fileName, makeOpaqueRedPng(64, 64)).await(),
            "uploadFile should resolve true on first upload",
        )

        // A list-row-sized request: served by the stored `LIST` class when the mint worked.
        val listResult = FileSystem.getPreview("/$fileName", 100, 100).await()
        assertTrue(listResult.ok as Boolean, "getPreview should succeed for an uploaded PNG")
        val preview = listResult.bytes as ByteArray?
        assertNotNull(preview, "getPreview should return bytes for an uploaded PNG")
        assertTrue(
            preview.isJpeg(),
            "the LIST class must be JPEG — WebP here means the mint did not run and this is " +
                "the local-bytes fallback; PNG means the rung stopped asking for a lossy format",
        )
    }

    @Test
    fun webUpload_mintsTheJpegLargeClass() = runTest {
        TenshuSdk.awaitReady().await()
        val fileName = "JsRuntimeTest-mint-large-${Date.now().toLong()}.png"
        assertTrue(
            FileSystem.uploadFile("/", fileName, makeOpaqueRedPng(512, 512)).await(),
            "uploadFile should resolve true on first upload",
        )

        // Larger than the LIST rung, so the read selects the stored `LARGE` class.
        val largeResult = FileSystem.getPreview("/$fileName", 256, 256).await()
        assertTrue(largeResult.ok as Boolean, "getPreview should succeed for an uploaded PNG")
        val preview = largeResult.bytes as ByteArray?
        assertNotNull(preview, "getPreview should return bytes for an uploaded PNG")
        assertTrue(
            preview.isJpeg(),
            "the LARGE class must be JPEG — WebP here means the mint did not run",
        )
    }
}

// JPEG SOI + marker: FF D8 FF.
private fun ByteArray.isJpeg(): Boolean =
    size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() &&
        this[2] == 0xFF.toByte()

// Generates a real opaque-red PNG of the given dimensions via
// OffscreenCanvas. Used as test fixture so we don't have to hand-craft
// zlib-compressed IDAT chunks — the browser's own PNG encoder produces
// bytes its own decoder definitely accepts.
internal suspend fun makeOpaqueRedPng(width: Int, height: Int): ByteArray {
    val canvas = app.tenshu.sdk.internal.JsOffscreenCanvas(width, height)
    val ctx = canvas.getContext("2d")
        ?: error("OffscreenCanvas.getContext(\"2d\") returned null")
    ctx.fillStyle = "red"
    ctx.fillRect(0, 0, width, height)
    val pngOptions: dynamic = js("({ type: 'image/png' })")
    val blob = canvas.convertToBlob(pngOptions).await()
    val arrayBuffer = blob.arrayBuffer().await()
    return Int8Array(arrayBuffer).unsafeCast<ByteArray>()
}
