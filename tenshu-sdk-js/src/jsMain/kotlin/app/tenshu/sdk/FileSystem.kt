@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import app.tenshu.sdk.internal.generatePreview
import app.tenshu.sdk.internal.toJs
import app.tenshu.sdk.internal.errorMessage
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.promise
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.Name
import org.tenshu.core.file.name
import org.tenshu.core.contains
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.getOrNull
import org.tenshu.core.file.ContentModifiedAt
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.Data
import org.tenshu.core.file.Device
import org.tenshu.core.file.File
import org.tenshu.core.file.FileHistory
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileCount
import org.tenshu.core.file.Folder
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.ThumbnailXXLarge
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.MovedFileModification
import org.tenshu.core.file.RenamedFileModification
import org.tenshu.core.file.Size
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.hasLocalData
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.fold
import org.tenshu.core.id
import org.tenshu.core.ifEmptyError
import org.tenshu.core.relation.eager
import org.tenshu.core.relation.relation
import org.tenshu.core.time.Instant
import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.ContentResolvability
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.core.file.ContentId
import org.tenshu.file.client.fileService
import kotlin.js.Promise

// The projection every file-listing read shares: `path` is a composite FIELD, the three
// timestamps/size are value PROPERTIES, and a projected composite carries only what is asked
// for — so both halves are required. Mirrors `TenshuFilesRepository`'s listing select.
private fun TList<File>.withRowFields(): TList<Entity<File>> = select {
    fields(File.Field.PATH)
    relations(File.Size, File.CreatedAt, File.LastModifiedAt, File.ContentModifiedAt, Folder.FileCount)
}

// Caller pattern: Promise-returning functions. Suspends live inside the
// `GlobalScope.promise { ... }` bridge; JS sees a vanilla `Promise`,
// suitable for `useQuery({ queryFn: () => FileSystem.list(path) })`. The
// hand-written TypeScript adapter in `web/sdk` is the source of truth for
// the JS surface shape.
@JsExport
object FileSystem {

    // Lists files under `path` as [FileRowJs] rows. Mirrors the production
    // navigation pattern in :drive-shared/.../TenshuFilesRepository.getFiles —
    // start at the default FileSystem's root, narrow to the requested folder
    // if path != "/", take one emission of the streaming `flow()`. The
    // `flow()` infrastructure builds the StreamInteractor, takes the initial
    // result, and `endStream`s in its own `finally` block (see
    // :core/.../collection/list/Stream.kt).
    fun list(path: String): Promise<Array<FileRowJs>> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val client = JsRuntime.tenshuClient
        val list = client.fileService.defaultFileSystem.root.files.let {
            if (path != "/") {
                it.search<Folder> { this.path equalTo path }.ifEmptyError().files
            } else {
                it
            }
        }
        list.withRowFields().flow().first().fold(
            onSuccess = { files -> files.toList().map { fileRowOf(it) }.toTypedArray() },
            onFailure = { emptyArray() },
        )
    }

    // The [limit] most-recently-modified entries across the whole drive, newest
    // first — the Home "recent" surface. Mirrors `TenshuFilesRepository.recentFiles`:
    // a full-tree `traverse()` sorted by `File.LastModifiedAt`, capped to [limit].
    // Returns the same [FileRowJs] rows as [list]. Cost note: `traverse()` walks the whole drive per call
    // — fine for normal drives; a maintained flat index is the optimisation if it
    // gets hot (same caveat as native).
    //
    // ⚠⚠ A failed read REJECTS; it is never an empty array. Folding it to `[]` made Drive's Home and
    // the apex overview's Drive card both say "no files yet" on a broken read — the failure-as-empty
    // lie `web-apex-home.md` §3.6 forbids. Both callers already have an error state for a rejection.
    fun recentFiles(limit: Int): Promise<Array<FileRowJs>> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val client = JsRuntime.tenshuClient
        client.fileService.defaultFileSystem.root.files
            .traverse()
            .sortByDescending(File.LastModifiedAt)
            .take(limit)
            .withRowFields()
            .flow()
            .first()
            .fold(
                onSuccess = { files -> files.toList().map { fileRowOf(it) }.toTypedArray() },
                onFailure = { error -> throw IllegalStateException("recentFiles failed: $error") },
            )
    }

    // Creates a folder at `path`. Resolves `true` on success, `false` on any
    // error (including AlreadyExistsError — callers can treat seed-style
    // calls as idempotent). Mirrors the production extension
    // `org.tenshu.core.file.system.createFolder`.
    fun createFolder(path: String): Promise<Boolean> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val client = JsRuntime.tenshuClient
        client.fileService.defaultFileSystem.createFolder(path).fold(
            onSuccess = { true },
            onFailure = { false },
        )
    }

    // Uploads `data` as a new file at `parentPath/fileName`. Resolves `true`
    // on success, `false` on any error (including `AlreadyExistsError`).
    // Bytes are persisted via `IndexedDbFileDatabase`; metadata via the
    // protocol `Database`. Mirrors the production extension
    // `org.tenshu.core.file.system.uploadFile`. JS callers pass an
    // `Int8Array` (the JS representation of Kotlin `ByteArray`); they can
    // get one from a browser `File` via `new Int8Array(await file.arrayBuffer())`.
    fun uploadFile(parentPath: String, fileName: String, data: ByteArray): Promise<Boolean> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            val client = JsRuntime.tenshuClient
            client.fileService.defaultFileSystem.uploadFile(parentPath, fileName, data).fold(
                onSuccess = { true },
                onFailure = { false },
            )
        }

    /**
     * Files whose **name** contains [query], case-insensitively, anywhere in the drive
     * (`drive-web-parity.md` W4). Empty query resolves an empty array — a search screen showing
     * the whole drive before you type is noise, not a feature.
     *
     * Mirrors `TenshuFilesRepository.searchFiles` deliberately, down to the predicate:
     *
     * ⚠ Matches the derived **name**, not the path. A path match would surface every unrelated
     * descendant of a folder whose name happens to contain the needle.
     * ⚠ `ignoreCase = true` is an explicit opt-in — `contains` defaults case-sensitive like
     * Kotlin's, and a user typing "photo" expects "Photo.jpg".
     * ⚠ The filter runs proxy-side, so only matching rows cross the bridge; there is no
     * client-side re-filter to drift from this one.
     *
     * ⚠ A full `traverse()` per query, not an index — the same trade native makes. An index only
     * pays above ~10k–50k files, and on web the resident-set ceiling bites first
     * (`bounded-store-cache.md`).
     */
    fun search(query: String): Promise<Array<FileRowJs>> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val needle = query.trim()
        if (needle.isEmpty()) return@promise emptyArray()
        JsRuntime.tenshuClient.fileService.defaultFileSystem.root
            .files
            .traverse()
            .filter<File> { name.contains(needle, ignoreCase = true) }
            .withRowFields()
            .flow()
            .first()
            .fold(
                onSuccess = { files -> files.toList().map { fileRowOf(it) }.toTypedArray() },
                onFailure = { emptyArray() },
            )
    }

    /**
     * Whether the file at [path] already has its bytes on this device (`drive-web-parity.md` W6).
     * A file without them still lists and still opens — the bytes fetch from the content plane on
     * demand — so this is "available offline", not "available".
     *
     * ⚠⚠ Call this **lazily, per visible row** — never across a whole listing. The op's own
     * contract says so, and it is not a web-specific worry: resolving presence eagerly for a
     * listing trips Android's Binder watchdog. A batched variant is no way round it — fewer round
     * trips does not make eager resolution cheap, it just moves the cost.
     *
     * `false` on any failure, so a row falls back to its ordinary glyph rather than claiming
     * presence it cannot prove.
     */
    fun hasLocalData(path: String): Promise<Boolean> = GlobalScope.promise {
        JsRuntime.awaitReady()
        runCatching {
            JsRuntime.tenshuClient.fileService.defaultFileSystem.hasLocalData(path).get().get()
        }.getOrDefault(false)
    }

    /**
     * Reads the bytes of the file at `path`.
     *
     * Resolves `{ ok: true, bytes }` — where `bytes` is `Int8Array` **or null when this device
     * legitimately has none** (no such file, or the content has not been fetched here) — or
     * `{ ok: false, error: { kind, message } }` when the read itself failed.
     *
     * ⚠⚠ Three answers, not two, and that is the point. A `Promise<Int8Array | null>` folds
     * "no bytes" and "the read broke" into one value, so callers can only assume the first:
     * `saveToDisk` silently downloads nothing, the opener silently shows nothing. The
     * legitimate absence is still a *success* (`ok: true, bytes: null`) because it is an answer,
     * not a fault.
     */
    fun getData(path: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        try {
            // ⚠ Read first: `okResult`'s builder is not a suspend lambda.
            val bytes = readFileData(path)
            okResult { it.bytes = bytes }
        } catch (t: Throwable) {
            failureResult("read", t.message ?: t::class.simpleName)
        }
    }

    // A preview thumbnail for the file at `path`, fitting `width × height`
    // with its aspect ratio kept. Resolves `{ ok: true, bytes }` — `bytes`
    // null when the file has no preview (nothing at that path, or bytes that
    // can't be decoded) — or `{ ok: false, error }` when the read or the
    // decode broke ([previewBytes]). The bytes are a stored thumbnail class
    // when one exists, encoded as the minting device chose; otherwise a local
    // render from [readFileData] through `createImageBitmap` +
    // `OffscreenCanvas.convertToBlob` (WebP, quality 0.85) — the same shape
    // Android's `AndroidGetPreviewImplementation` produces.
    fun getPreview(path: String, width: Int, height: Int): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            try {
                val bytes = previewBytes(path, width, height)
                okResult { it.bytes = bytes }
            } catch (t: Throwable) {
                failureResult("preview", t.message ?: t::class.simpleName)
            }
        }

    /**
     * The **large** preview for a full-screen surface — the lightbox's read
     * (`drive-xxlarge-thumbnail-rung.md` §4.8).
     *
     * Resolves `{ ok: true, bytes, thumbnailClass, coveringRung }`, or `{ ok: false, error }` when
     * the read itself broke.
     *
     * ⚠⚠ **Three fields, because bytes alone cannot drive the caller's decision.** The lightbox has
     * to choose between drawing what it got and downloading the whole original, and that choice
     * needs two things this SDK's other preview surface throws away:
     *
     *  - **[thumbnailClass]** — which class supplied the bytes (`xxlarge`, `xlarge`, `large`,
     *    `list`, or `null` for none). The caller compares its edge against the size it asked for;
     *  - **[coveringRung]** — `"present"`, `"absent"` or `"unknown"` for *the largest class that
     *    could cover this request*. ⚠⚠ `"absent"` means the read succeeded and the class genuinely
     *    is not here; `"unknown"` means the read or the pull failed and we cannot tell. Only
     *    `"absent"` licenses downloading the original. Collapsing those two is the mistake this
     *    whole surface exists to avoid — see `JsResult.kt`: *never return null for two different
     *    reasons*. Read as absence, a network blip fetches a 6–18 MB original on every open; read
     *    as presence, the picture stays blurry for ever.
     *
     * ⚠ Which class is "covering" depends on [px]: above `XLARGE_PX` it is `XXLARGE`, at or below
     * it `XLARGE`. A 1024 px lightbox must not pull ~0.5 MB to show a picture 1024 px covers
     * (§4.4), and it must still learn that `XLARGE` is missing so it can fall back.
     *
     * ⚠ Never projects `File.Preview` or `File.ViewerPreview` — both resolve through `GetPreview`,
     * whose browser implementation is async while the dynamic-property path is not
     * (`drive-web-parity.md` §1.2i). These are stored classes, served synchronously.
     */
    fun getLargePreview(path: String, px: Int): Promise<dynamic> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            try {
                val answer = largePreviewJs(path, px)
                okResult {
                    it.bytes = answer.bytes
                    it.thumbnailClass = answer.thumbnailClass
                    it.coveringRung = answer.coveringRung
                }
            } catch (t: Throwable) {
                failureResult("preview", t.message ?: t::class.simpleName)
            }
        }

    /**
     * The preview pipeline itself. Separated so [getPreview] can wrap it once: `null` here means
     * "this file has no preview" (a folder, an `.ics`, an undecodable blob) — a real answer —
     * while a throw means the read or the decode broke, which the caller must be able to tell
     * apart. Collapsed, an unwired stored-class read looks exactly like files with no preview:
     * category icons everywhere, and no error.
     */
    private suspend fun previewBytes(path: String, width: Int, height: Int): ByteArray? {
            // Ask for the STORED thumbnail class first (`drive-web-parity.md` W2), exactly as
            // `TenshuFilesRepository.getPreview` does. Going straight to `readFileData` + canvas
            // would pull a whole 3 MB photo through the content plane to produce a 40 px row
            // icon. The class is ~7 KB.
            readPreviewJs(path, width, height)?.let { return it }

            // Cold path, per `drive-synced-thumbnails.md` §11.2: both classes are
            // `Syncable(OnDemand)` on the content plane, so a device that has never asked for
            // them holds only the metadata. Pull them, then read again. ⚠ Two reads, not one
            // projection of both: within a single select the classes resolve before the
            // terminal materialises anything.
            cacheThumbnailsJs(path)
            return readPreviewJs(path, width, height)
                // ⚠ Local-bytes fallback. A browser upload mints the ladder
                // (`JsGetPreviewImplementation`), so this covers the two cases the classes
                // cannot: a browser upload that carries no classes (written before the web
                // minted them), and one whose mint came back empty (non-image, or a decode this
                // browser could not do). Renders from the bytes this device holds.
                ?: readFileData(path)?.let { generatePreview(it, width, height) }
        }

    // Renames the file or folder at `path` to `newName`, keeping it in the
    // same parent. Resolves `true` on success, `false` on any error
    // (including `AlreadyExistsError` when the parent already holds an entry
    // of that name). Mirrors `TenshuFilesRepository.renameFile`: a rename is
    // a `moveFile` to the new path under the same parent — the path
    // derivation here matches the native repository exactly.
    fun rename(path: String, newName: String): Promise<Boolean> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val client = JsRuntime.tenshuClient
        val parentPath = path.substringBeforeLast("/")
        val newPath = if (parentPath.isEmpty()) "/$newName" else "$parentPath/$newName"
        client.fileService.defaultFileSystem.moveFile(path, newPath).fold(
            onSuccess = { true },
            onFailure = { false },
        )
    }

    // Moves the file or folder at `sourcePath` into `destinationFolderPath`,
    // keeping its name (`destinationFolderPath` is a folder path; `"/"` for
    // the root). Resolves `true` on success, `false` on any error (including
    // `AlreadyExistsError`). Mirrors `TenshuFilesRepository.moveFile`,
    // including the target-path derivation.
    fun move(sourcePath: String, destinationFolderPath: String): Promise<Boolean> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            val client = JsRuntime.tenshuClient
            val name = sourcePath.substringAfterLast("/")
            val targetPath = if (destinationFolderPath == "/") {
                "/$name"
            } else {
                "${destinationFolderPath.trimEnd('/')}/$name"
            }
            client.fileService.defaultFileSystem.moveFile(sourcePath, targetPath).fold(
                onSuccess = { true },
                onFailure = { false },
            )
        }

    // Deletes the file or folder at `path` (recursively for folders, per the
    // `DeleteFile` op). Resolves `true` on success, `false` on any error.
    // Mirrors `TenshuFilesRepository.deleteFile`.
    fun delete(path: String): Promise<Boolean> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val client = JsRuntime.tenshuClient
        client.fileService.defaultFileSystem.deleteFile(path).fold(
            onSuccess = { true },
            onFailure = { false },
        )
    }

    /**
     * One file's row, by path — the "Details" surface (`drive-file-details-screen.md`).
     *
     * Resolves `{ ok: true, row }` — `row` null when no entry at that path — or
     * `{ ok: false, error }` when the listing read failed. Mirrors
     * `TenshuFilesRepository.fileDetails`: navigate to the parent folder, project the row fields,
     * find the matching path. Raw values (bytes, epoch-millis) — formatting is the web UI's job.
     *
     * ⚠ Two shapes on purpose: as one `null`, "no such file" and "the folder read broke" render the
     * Details drawer's not-found state for a failed read. Two different problems, one blank panel.
     */
    fun details(path: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val client = JsRuntime.tenshuClient
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        client.fileService.defaultFileSystem.root.files
            .navigateToFolder(parent)
            .withRowFields()
            .flow()
            .first()
            .fold(
                onSuccess = { entries ->
                    val row = entries.toList().map { fileRowOf(it) }.firstOrNull { it.path == path }
                    okResult { it.row = row }
                },
                onFailure = { error -> failureResult("read", error.toString()) },
            )
    }

    // Modification history for the file at `path` — the "Activity" surface
    // (`drive-file-details-screen.md` §3), newest first. Resolves an array of
    // `{ kind, detail, timestampMillis, deviceId, deviceName }` (empty on any
    // error / no history). Mirrors `TenshuFilesRepository.fileActivity`:
    // navigate to the file, read its `File.FileHistory` relation, project the
    // modification fields (+ the authoring `Device`), map per subtype.
    fun activity(path: String): Promise<Array<dynamic>> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val client = JsRuntime.tenshuClient
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        client.fileService.defaultFileSystem.root.files
            .navigateToFolder(parent)
            .search<File> { this.path equalTo path }
            .ifEmptyError()
            .relation(File.FileHistory)
            .select {
                fields(
                    FileModification.Field.COMPOSITE_KEY,
                    FileModification.Field.TIMESTAMP,
                    FileModification.Field.DEVICE_ID,
                    RenamedFileModification.Field.OLD_NAME,
                    RenamedFileModification.Field.NEW_NAME,
                    MovedFileModification.Field.NEW_PARENT_PATH,
                )
                relations(FileModification.Device)
            }
            .flow()
            .first()
            .fold(
                onSuccess = { mods ->
                    mods.toList()
                        .map { mapActivityJs(it) }
                        .sortedByDescending { (it.timestampMillis as Double) }
                        .toTypedArray()
                },
                onFailure = { emptyArray() },
            )
    }

    /**
     * The realm's file inventory — Control → Data → Files (`control-ia.md` §5.1): how many files
     * and folders the drive holds, their total size, the newest change, and a per-extension
     * breakdown. A meta-view, not a browser: no row leaves this call, only totals.
     *
     * Resolves `{ ok: true, inventory: FileInventoryJs }` or `{ ok: false, error: { kind: "read",
     * message } }` when the tree read failed.
     *
     * ⚠⚠ A failure is NOT an empty inventory. [list] and [search] fold a failed read into an empty
     * array, and for an inventory that would render "0 files, 0 B" — a confident, wrong answer
     * about the user's data. An empty drive is `ok: true` with zero counts; a broken read is
     * `ok: false`, and the screen tells them apart.
     *
     * ⚠ Projects ITS OWN minimal select ([withInventoryFields]), not [withRowFields]: exactly
     * what [fileInventoryOf] reads, so no `Folder.FileCount` (a dynamic resolve per folder) and no
     * `LastModifiedAt`. Never `File.Preview` or any thumbnail class (`drive-web-parity.md` §1.2i),
     * and never `File.Data`.
     * Grouping by extension is done here so the page receives a handful of totals rather than one
     * object per file; *naming* the groups (images, documents…) is presentation and stays in TS.
     *
     * ⚠ A full `traverse()` per call — the same cost [recentFiles] and [search] accept. Called on
     * page open, never on a timer.
     */
    fun inventory(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.fileService.defaultFileSystem.root
            .files
            .traverse()
            .withInventoryFields()
            .flow()
            .first()
            .fold(
                onSuccess = { files -> inventoryResultJs(files.toList().map { inventoryRowOf(it) }) },
                onFailure = { error -> inventoryFailureJs(error.errorMessage()) },
            )
    }
}

/** The `kind` an [FileSystem.inventory] failure carries. ⚠ `web/sdk/src/files.ts` spells it too. */
internal const val INVENTORY_READ_FAILED = "read"

internal fun inventoryFailureJs(message: String?): dynamic = failureResult(INVENTORY_READ_FAILED, message)

/**
 * The inventory's projection, and nothing else (the contract test pins both lists). `path` gives
 * the type-independent extension; `Size` the bytes; `ContentModifiedAt` with its `CreatedAt`
 * fallback the "newest change" — the same date a listing row shows as "Modified". The folder/file
 * split comes from the entity's type, which needs no projection.
 *
 * ⚠ Adding a field here must come with [inventoryRowOf] reading it — anything else is a resolve
 * per row that nothing uses, on a full-tree walk.
 */
internal val INVENTORY_FIELDS: List<String> = listOf(File.Field.PATH)
internal val INVENTORY_RELATIONS: List<org.tenshu.core.relation.Property<*>> =
    listOf(File.Size, File.ContentModifiedAt, File.CreatedAt)

private fun TList<File>.withInventoryFields(): TList<Entity<File>> = select {
    fields(*INVENTORY_FIELDS.toTypedArray())
    relations(*INVENTORY_RELATIONS.toTypedArray())
}

/** What [fileInventoryOf] folds: one listing row, reduced to the inventory's fields. */
internal class InventoryRow(
    val path: String,
    val size: Double,
    /** A file's "Modified" date, as [FileRowJs.lastModified]; `0` for a folder (unread). */
    val modified: Double,
    val isFolder: Boolean,
)

// [fileRowOf]'s reads, minus the ones [withInventoryFields] does not project — reading an
// unprojected relation off the entity is exactly the cost the minimal select exists to avoid.
private fun inventoryRowOf(d: Data): InventoryRow {
    val entity = d as? Entity<*>
    val typeId = entity?.type?.get() ?: d.id
    val path = when (d) {
        is Entity<*> -> d.getFieldValue<TString>(File.Field.PATH).get()
        is Composite -> d.getField<TString>(File.Field.PATH).get()
        else -> ""
    }
    val isFolder = typeId == Folder.ID
    val modified =
        if (isFolder) null
        else org.tenshu.core.file.contentModifiedAtOrCreated(
            entity?.get(File.ContentModifiedAt)?.toEpochMilliseconds(),
            entity?.get(File.CreatedAt)?.toEpochMilliseconds(),
        )
    return InventoryRow(
        path = path,
        size = if (isFolder) 0.0 else (entity?.get(File.Size)?.get() ?: 0L).toDouble(),
        modified = (modified ?: 0L).toDouble(),
        isFolder = isFolder,
    )
}

internal fun inventoryResultJs(rows: List<InventoryRow>): dynamic {
    val inventory = fileInventoryOf(rows)
    return okResult { it.inventory = inventory }
}

/**
 * Folds listing rows into totals. Pure, so the jsTest contract test can pin empty-vs-populated
 * without a seeded store. Folders count as folders and contribute no bytes (a folder's `size` is
 * not a sum of its children here); every file lands in exactly one extension bucket.
 */
internal fun fileInventoryOf(rows: List<InventoryRow>): FileInventoryJs {
    val files = rows.filterNot { it.isFolder }
    val byExtension = files
        .groupBy { extensionOf(it.path) }
        .map { (extension, group) ->
            ExtensionTotalJs(
                extension = extension,
                count = group.size,
                bytes = group.sumOf { it.size },
            )
        }
        // Largest first — what the page shows top-down; ties broken by name so the order is stable.
        .sortedWith(compareByDescending<ExtensionTotalJs> { it.bytes }.thenBy { it.extension })
        .toTypedArray()
    return FileInventoryJs(
        fileCount = files.size,
        folderCount = rows.size - files.size,
        totalBytes = files.sumOf { it.size },
        newestModifiedMillis = files.maxOfOrNull { it.modified } ?: 0.0,
        byExtension = byExtension,
    )
}

/** Lower-cased extension of the last path segment, `""` for none. A leading dot (`.env`) is a name, not an extension. */
internal fun extensionOf(path: String): String {
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
}

/** [FileSystem.inventory]'s answer. Byte counts are JS numbers, as on [FileRowJs]. */
@JsExport
class FileInventoryJs internal constructor(
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Double,
    /** The newest file's "Modified" date ([FileRowJs.lastModified]), `0` when there are no files. */
    val newestModifiedMillis: Double,
    /** One entry per extension, largest first. `extension` is `""` for files without one. */
    val byExtension: Array<ExtensionTotalJs>,
)

@JsExport
class ExtensionTotalJs internal constructor(
    val extension: String,
    val count: Int,
    val bytes: Double,
)

// Reads the best available stored preview class.
//
// ⚠⚠ Reads the CLASSES, not `File.Preview`. `File.Preview` resolves through `GetPreview`, whose
// browser implementation must be async (no synchronous decode in a browser) while the
// dynamic-property path is synchronous — so projecting it here would resolve an unawaited
// `AsyncReference` instead of bytes, and the `onFailure` below would make any such failure a
// silent null on EVERY call, every thumbnail then coming from `getPreview`'s local-bytes fallback
// (whole photos pulled through the content plane to draw row icons). `File.Thumbnail` /
// `File.ThumbnailLarge` go to the shared `GetStoredThumbnailImplementation`, which is wired on web
// and synchronous. See [JsGetPreviewImplementation] for the two-call-path rule.
//
// ⚠ `eager(...)` is load-bearing: both classes are deferred-by-default and this is a one-shot
// `.get()` — without the override every thumbnail resolves to null.
private suspend fun readPreviewJs(path: String, width: Int, height: Int): ByteArray? =
    JsRuntime.tenshuClient.fileService.defaultFileSystem.root
        .files
        .navigateToFolder(path.substringBeforeLast('/').ifEmpty { "/" })
        .search<File> { this.path equalTo path }
        .select { relations(eager(File.Thumbnail), eager(File.ThumbnailLarge)) }
        .resolve()
        .fold(
            onSuccess = { entity ->
                if (entity == null) return@fold null
                val list = entity.get(File.Thumbnail)?.getOrNull()
                val large = entity.get(File.ThumbnailLarge)?.getOrNull()
                // Smallest class that covers the request, falling back to whichever exists.
                // ⚠ A local echo of the ladder's LIST rung; the real selection is
                // `StoredThumbnailLadder`, which the three native `GetPreview` actuals share and
                // which is internal to `:file-service-proxy` (`drive-web-parity.md` §1.2i).
                if (maxOf(width, height) > GetStoredThumbnail.LIST_PX) large ?: list else list ?: large
            },
            onFailure = { null },
        )

/** What [largePreviewJs] answers: the bytes, the class they came from, and the covering rung's state. */
private class LargePreviewAnswer(
    val bytes: ByteArray?,
    val thumbnailClass: String?,
    val coveringRung: String,
)

// The covering rung's three states. ⚠⚠ ABSENT and UNKNOWN must never be folded together: the first
// is an answer ("nothing minted this class"), the second is the lack of one ("the relay was
// unreachable"). Only the first licenses a whole-original download.
private const val RUNG_PRESENT = "present"
private const val RUNG_ABSENT = "absent"
private const val RUNG_UNKNOWN = "unknown"

// Reads the two hero classes, keeping a FAILED read distinct from an empty one — which is the whole
// difference between this and [readPreviewJs], whose `onFailure = { null }` is right for a row icon
// and wrong here. A row that cannot draw is ordinary; a lightbox that cannot tell why it has no
// bytes either stays blurry or spends the original.
private suspend fun readHeroClassesJs(path: String): Result<Pair<ByteArray?, ByteArray?>> =
    runCatching {
        JsRuntime.tenshuClient.fileService.defaultFileSystem.root
            .files
            .navigateToFolder(path.substringBeforeLast('/').ifEmpty { "/" })
            .search<File> { this.path equalTo path }
            // ⚠ `eager(...)` for the same reason `readPreviewJs` needs it: both are deferred by
            // default and this is a one-shot read, so without it every class resolves to null.
            .select { relations(eager(File.ThumbnailXXLarge), eager(File.ThumbnailXLarge)) }
            .resolve()
            .fold(
                onSuccess = { entity ->
                    entity?.get(File.ThumbnailXXLarge)?.getOrNull() to
                        entity?.get(File.ThumbnailXLarge)?.getOrNull()
                },
                onFailure = { error -> error("hero class read failed: $error") },
            )
    }

// Names the hero classes so the OnDemand materialiser fetches them — the pull IS the projection, and
// it is also what declares the class (`WantedThumbnailClasses.notePull`).
//
// ⚠⚠ **Its result says nothing about whether the fetch worked, and must never be read as if it
// did.** `OnDemandResolutionImpl.resolve` returns `Success` unconditionally once the property has a
// `contentId` — it calls `resolveContentManifest` and never inspects the outcome — and a thumbnail
// class always has one. So a 503, a captive portal and "nobody ever minted this" arrive here
// identically. Deciding absence from this makes "a failed pull never falls through to the mint"
// dead code (`drive-xxlarge-thumbnail-rung.md` §9.6). Ask [confirmedAbsentOnTheRelayJs] instead;
// this call exists only for its side effect.
private suspend fun pullHeroClassesJs(path: String, includeXXLarge: Boolean) {
    runCatching {
        JsRuntime.tenshuClient.fileService.defaultFileSystem.root
            .files
            .navigateToFolder(path.substringBeforeLast('/').ifEmpty { "/" })
            .search<File> { this.path equalTo path }
            .select {
                if (includeXXLarge) {
                    relations(File.ThumbnailXXLarge, File.ThumbnailXLarge)
                } else {
                    relations(File.ThumbnailXLarge)
                }
            }
            .resolve()
    }
}

/**
 * Whether the relay definitely does **not** have [thumbnailClass] for this file — the only thing
 * that licenses the caller to spend a whole original.
 *
 * ⚠⚠ The same probe `EnsureThumbnailClassImplementation.confirmedAbsentOnTheRelay` uses, and
 * deliberately so: two platforms that disagree about what "absent" means spend originals on one
 * and stay blurry on the other. `ContentResolvability.UNKNOWN` — no emitter, sync disabled, no manifest, a
 * chunk probe that could not answer — is **not** absence, and reads `false` here.
 *
 * ⚠ The emitter is wired on web (`FirstPartyPlugins` publishes `SyncObserver` into
 * `syncBlockEmitterHolder`), but a null one still answers `false`: a runtime that cannot tell must
 * not license a download.
 */
private suspend fun confirmedAbsentOnTheRelayJs(path: String, thumbnailClass: String): Boolean {
    val emitter = JsRuntime.syncBlockEmitterHolder.fileContentEmitter ?: return false
    return runCatching {
        val entity = JsRuntime.tenshuClient.fileService.defaultFileSystem.root
            .files
            .navigateToFolder(path.substringBeforeLast('/').ifEmpty { "/" })
            .search<File> { this.path equalTo path }
            .select { relations(eager(File.ContentId)) }
            .resolve()
            .getOrNull() ?: return false
        val contentId = (entity.get(File.ContentId) as? TString)?.get()?.takeIf { it.isNotEmpty() }
            ?: return false
        val dataId = DataId.StringDataId.create(
            context = JsRuntime.tContext,
            type = File.ID,
            keyValue = path,
        )
        emitter.contentResolvability(
            targetEntityId = dataId,
            contentId = FileBytesPropertyWriter.derivedThumbnailContentId(contentId, thumbnailClass),
        ) == ContentResolvability.ABSENT
    }.getOrDefault(false)
}

// §4.8's read: the hero classes first, then the small ones as a stand-in, reporting what it found
// and how sure it is. Mirrors `previewBytes`'s read → pull → read again shape, because the classes
// are `Syncable(OnDemand)` and a device that never asked holds only metadata.
private suspend fun largePreviewJs(path: String, px: Int): LargePreviewAnswer {
    // ⚠ Above 1024 px the covering class is XXLARGE; at or below it, XLARGE. Asking for XXLARGE on a
    // small request would put ~0.5 MB on the wire for a picture XLARGE already covers (§4.4), and a
    // lightbox that never learns XLARGE is missing can never fall back.
    val wantsXXLarge = px > GetStoredThumbnail.XLARGE_PX

    // ⚠⚠ **Anything at or above the request covers it**, which is not the same as "the class this
    // size asks for". A device holding `XXLARGE` but not `XLARGE` covers a 1024 px request
    // perfectly — reading only the size-matched slot there would return a 256 px stand-in *and* a
    // licence to fetch the original, while a 2048 px copy sits on disk.
    fun covering(classes: Pair<ByteArray?, ByteArray?>): ByteArray? =
        classes.first ?: if (!wantsXXLarge) classes.second else null

    fun answerFrom(classes: Pair<ByteArray?, ByteArray?>, rung: String): LargePreviewAnswer? {
        val (xxlarge, xlarge) = classes
        // Largest first: it is already here, so serving it costs nothing extra and it is the
        // sharpest thing available.
        if (xxlarge != null) return LargePreviewAnswer(xxlarge, GetStoredThumbnail.CLASS_XXLARGE, rung)
        if (xlarge != null) return LargePreviewAnswer(xlarge, GetStoredThumbnail.CLASS_XLARGE, rung)
        return null
    }

    val local = readHeroClassesJs(path).getOrElse {
        // The read broke. Fall back to a stand-in but say so — a failed read must not read as
        // "there is no large class", which would spend the original.
        return standIn(path, px, RUNG_UNKNOWN)
    }
    if (covering(local) != null) return answerFrom(local, RUNG_PRESENT)!!

    // Cold: nothing here yet. Pull (side effect only — its result cannot report failure), then
    // read again.
    pullHeroClassesJs(path, wantsXXLarge)
    val afterPull = readHeroClassesJs(path).getOrElse {
        return answerFrom(local, RUNG_UNKNOWN) ?: standIn(path, px, RUNG_UNKNOWN)
    }
    if (covering(afterPull) != null) return answerFrom(afterPull, RUNG_PRESENT)!!

    // ⚠⚠ Still nothing — and "still nothing" is NOT absence. It is equally the shape of a relay we
    // could not reach, because the pull above cannot report that. Only the relay can settle it, so
    // ask it: `ContentResolvability.ABSENT` is the one answer that licenses a whole-original
    // download, and UNKNOWN keeps the stand-in until a later open can ask again.
    val coveringClass =
        if (wantsXXLarge) GetStoredThumbnail.CLASS_XXLARGE else GetStoredThumbnail.CLASS_XLARGE
    val rung = if (confirmedAbsentOnTheRelayJs(path, coveringClass)) RUNG_ABSENT else RUNG_UNKNOWN
    return answerFrom(afterPull, rung) ?: standIn(path, px, rung)
}

// The small classes, as a stand-in while the caller decides what to do about the missing hero rung.
// ⚠ Reported with the class it came from, because the caller's fallback test is "is what I got
// smaller than what I asked for" — which bytes alone cannot answer.
private suspend fun standIn(path: String, px: Int, rung: String): LargePreviewAnswer =
    runCatching {
        JsRuntime.tenshuClient.fileService.defaultFileSystem.root
            .files
            .navigateToFolder(path.substringBeforeLast('/').ifEmpty { "/" })
            .search<File> { this.path equalTo path }
            .select { relations(eager(File.Thumbnail), eager(File.ThumbnailLarge)) }
            .resolve()
            .fold(
                onSuccess = { entity ->
                    val large = entity?.get(File.ThumbnailLarge)?.getOrNull()
                    val list = entity?.get(File.Thumbnail)?.getOrNull()
                    when {
                        large != null -> LargePreviewAnswer(large, GetStoredThumbnail.CLASS_LARGE, rung)
                        list != null -> LargePreviewAnswer(list, GetStoredThumbnail.CLASS_LIST, rung)
                        else -> LargePreviewAnswer(null, null, rung)
                    }
                },
                onFailure = { LargePreviewAnswer(null, null, rung) },
            )
    }.getOrDefault(LargePreviewAnswer(null, null, rung))

// Pulls the OnDemand thumbnail classes into the local cache so the next read finds them.
// Mirrors `TenshuFilesRepository.cacheThumbnails`.
private suspend fun cacheThumbnailsJs(path: String) {
    JsRuntime.tenshuClient.fileService.defaultFileSystem.root
        .files
        .navigateToFolder(path.substringBeforeLast('/').ifEmpty { "/" })
        .search<File> { this.path equalTo path }
        .select { relations(File.Thumbnail, File.ThumbnailLarge) }
        .resolve()
}

// Reads a personal file's plaintext through the chain, so an OnDemand fetch runs
// when this device doesn't hold the bytes.
//
// `File.Data` is `Syncable(OnDemand)`: a file's *entity* syncs eagerly but its
// *bytes* do not, so a file uploaded on another device lists in web Drive while
// its content lives only on the relay. Selecting `relations(File.Data)` is what
// makes the OnDemand materialiser fetch + decrypt it (mirrors
// `TenshuFilesRepository.getRemoteFile`). Reading `IndexedDbFileDatabase`
// directly answers `null` for every such file, so opening/downloading one
// would silently do nothing.
private suspend fun readFileData(path: String): ByteArray? =
    JsRuntime.tenshuClient.fileService.defaultFileSystem.root
        .files
        .navigateToFolder(path.substringBeforeLast('/').ifEmpty { "/" })
        .search<File> { this.path equalTo path }
        .select { relations(File.Data) }
        .resolve()
        .fold(
            // `getOrNull()` rather than `get()`: graceful on a byte-cache miss,
            // same reasoning as the native repository's.
            onSuccess = { entity -> entity?.get(File.Data)?.getOrNull() },
            // ⚠⚠ Throws, never `null`: a null makes a chain failure indistinguishable from "this
            // file has no bytes here", and every caller reads it as the second. The `@JsExport`
            // surfaces below turn it into `{ ok: false, error }` so the caller can actually
            // tell. See `JsResult.kt` for why `T?` is the wrong shape.
            onFailure = { error -> error("read failed: $error") },
        )

// Narrows a root file-listing to the folder at `parentPath`. Mirrors the
// private `navigateTo` helper in `TenshuFilesRepository` (root short-circuits;
// otherwise search the folder by path and descend into its files).
private fun TList<File>.navigateToFolder(parentPath: String): TList<File> =
    if (parentPath == "/") this
    else search<Folder> { this.path equalTo parentPath }.ifEmptyError().files

// Maps one `FileModification` to the flat JS activity row. Replicates
// `FileMapper.mapActivity`: the subtype (type id) drives `kind` + the `detail`
// string; the authoring device name resolves via the `FileModification.Device`
// reference (null when the device is unknown locally). Reads fields through
// either the `Entity` or plain-`Composite` shape (the filtered log materialises
// items as either). Raw epoch-millis — formatting is the web UI's job.
/**
 * One row of a file listing (`drive-web-parity.md` §1.2e/§1.2f): a typed value built by an
 * explicit mapper, not by the generic schema walker (`toJs()`).
 *
 * ⚠⚠ The walker cannot do this. `size` / `createdAt` / `lastModified` are `valueProperty`
 * declarations, not fields of the `File` composite, so `toJs()` — which flattens
 * `composite.fields` — emits no `size` key at all and every row renders `0 B`. Projecting
 * them is necessary but not sufficient: a projected field arrives as a `Composite` and the
 * walker's `is Composite` branch nests it, so `path` stops being a string. An explicit mapper
 * sidesteps both, and names its own keys — which is also why `lastModified` here matches the
 * published TS contract rather than the schema id's `lastModifiedAt`.
 *
 * ⚠ `isFolder` is the discriminator, never `'files' in entry`: whether the walker emits the `FILES`
 * field for a Folder and omits it for a File is incidental behaviour, not a contract.
 */
@JsExport
class FileRowJs internal constructor(
    val path: String,
    val size: Double,
    /**
     * The date to show as "Modified": a file's own (`File.ContentModifiedAt`, or `CreatedAt` on a
     * row that predates it), a folder's "last touched". ⚠ Never the upload moment — and so not
     * the key the recent feed is sorted by, which stays `File.LastModifiedAt`.
     */
    val lastModified: Double,
    val createdAt: Double,
    val isFolder: Boolean,
    /**
     * Child count for a folder, `0` for a file. ⚠ Read from the `Folder.FileCount` RELATION,
     * never by projecting the `FILES` field — the field materialises and ships every child
     * entity just to call `.size` (the note `FileMapper` carries for the same reason). The
     * walker's `files` array is no substitute: it is the empty array `ArrayDatabaseReference`
     * degrades to, so every folder would read "0 items".
     */
    val itemCount: Double,
)

// Mirrors `FileMapper.map` in :drive-shared-logic — same properties, read the same way, so the
// two readers can be diffed by eye. Accepts `Data` and branches like [mapActivityJs] because a
// projected element may arrive as an Entity or a bare Composite.
private fun fileRowOf(d: Data): FileRowJs {
    val entity = d as? Entity<*>
    val typeId = entity?.type?.get() ?: d.id
    val path = when (d) {
        is Entity<*> -> d.getFieldValue<TString>(File.Field.PATH).get()
        is Composite -> d.getField<TString>(File.Field.PATH).get()
        else -> ""
    }
    fun instant(p: org.tenshu.core.relation.Property.Value<org.tenshu.core.time.UnixInstant>): Double =
        (entity?.get(p)?.toEpochMilliseconds() ?: 0L).toDouble()
    val isFolder = typeId == Folder.ID
    val modified =
        if (isFolder) entity?.get(File.LastModifiedAt)?.toEpochMilliseconds()
        else org.tenshu.core.file.contentModifiedAtOrCreated(
            entity?.get(File.ContentModifiedAt)?.toEpochMilliseconds(),
            entity?.get(File.CreatedAt)?.toEpochMilliseconds(),
        )
    return FileRowJs(
        path = path,
        size = (entity?.get(File.Size)?.get() ?: 0L).toDouble(),
        lastModified = (modified ?: 0L).toDouble(),
        createdAt = instant(File.CreatedAt),
        isFolder = isFolder,
        itemCount = (entity?.get(Folder.FileCount)?.get() ?: 0L).toDouble(),
    )
}

private fun mapActivityJs(modification: Data): dynamic {
    val typeId = (modification as? Entity<*>)?.type?.get() ?: modification.id
    fun field(fieldId: String): String = when (modification) {
        is Entity<*> -> modification.getFieldValue<TString>(fieldId).get()
        is Composite -> modification.getField<TString>(fieldId).get()
        else -> ""
    }
    val timestampMillis = when (modification) {
        is Entity<*> -> modification.getFieldValue<Instant>(FileModification.Field.TIMESTAMP)
        is Composite -> modification.getField<Instant>(FileModification.Field.TIMESTAMP)
        else -> null
    }?.toEpochMilliseconds() ?: 0L

    val kind = when (typeId) {
        CreatedFileModification.ID -> "Created"
        RenamedFileModification.ID -> "Renamed"
        MovedFileModification.ID -> "Moved"
        else -> "Modified"
    }
    val detail: String? = when (typeId) {
        RenamedFileModification.ID ->
            field(RenamedFileModification.Field.OLD_NAME) + " → " +
                field(RenamedFileModification.Field.NEW_NAME)

        MovedFileModification.ID -> "to " + field(MovedFileModification.Field.NEW_PARENT_PATH)
        else -> null
    }

    val obj: dynamic = js("({})")
    obj.kind = kind
    obj.detail = detail
    obj.timestampMillis = timestampMillis.toDouble()
    obj.deviceId = field(FileModification.Field.DEVICE_ID)
    obj.deviceName = (modification as? Entity<*>)?.get(FileModification.Device)?.name?.get()
    return obj
}
