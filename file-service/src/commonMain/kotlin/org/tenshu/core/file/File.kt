package org.tenshu.core.file

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TConstant
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.contains
import org.tenshu.core.equalTo
import org.tenshu.core.oneOf
import org.tenshu.core.toTBoolean
import org.tenshu.core.substringAfterLast
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.dynamicProperty
import org.tenshu.core.relation.entityRelation
import org.tenshu.core.relation.valueProperty
import org.tenshu.core.relation.withParams
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.ContentReferencing
import org.tenshu.core.trait.DerivedContent
import org.tenshu.core.trait.PrimaryContent
import org.tenshu.core.trait.RealmData
import org.tenshu.core.trait.SliceKey
import org.tenshu.core.trait.Sliceable
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

interface File : Composite {

    override val schema: CompositeSchema get() = Schema

    val path: FilePath
        get() = getField(Field.PATH)

    companion object {
        const val ID = "tenshu/file"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            // File is the first RealmData type — it surfaces in Control's Data section (the
            // "Files" card; docs/proposals/core-realm-data.md §4 Phase 2). RealmData is a
            // fieldless marker: presentation (name, icon, order) lives in the consumer's UI,
            // not the schema. Folder inherits the marker for resolution but folds into this
            // single entry (§2.4). The newest-first sort key is File.CreatedAt, bound to
            // RealmData.CREATED_AT in fileServiceCommonModule.
            traits = listOf(
                FileDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
                RealmData.Schema,
                // §6e N1: this type's rows hold content-plane references; the trait names the
                // property their handles derive from, so the content plane never names `File`.
                ContentReferencing.Schema,
                // C8 (`sync-device-catch-up.md` §5.7.3): a collection of Files is sliced by
                // its parent entity — `Folder.FILES` is already one instance per folder, so
                // the user's own tree partitions it and there is no bucket boundary to pick.
                // This is the easy case, which is why C8 ships it first; the realm-wide
                // singletons (MOMENTS, MODIFICATIONS) need a time bucket and land in C9.
                Sliceable(SliceKey.PerEntity),
            ),
            fields = {
                string(id = Field.PATH, type = FilePath.Schema)
            }
        )

        fun create(
            context: TContext,
            path: String,
        ): File = context.create(
            type = ID,
            fields = listOf(path)
        )
    }

    object Field {
        const val PATH = "$ID/path"
    }
}

val Entity<out File>.path: FilePath
    get() = getFieldValue(File.Field.PATH)

// ── properties (extension handles, kept out of the interface) ───────────────
//
// Properties are *extension* data on File: any module may declare its own the
// same way without File enumerating it. Each handle is the single source of
// truth across declare / set / get / project (`relations`) / sort. The wire
// composites, RelationSchema descriptors and resolvers are unchanged — this is
// the authoring surface only (docs/adr/0013-property-authoring-unification.md).
//
// Extension properties can't carry an initializer (no backing field), so each is
// backed by a private file-level singleton and exposed via a getter.

// Static — any TValue/TConstant. `valueProperty(id, schema)` covers op-written
// scalars (Size, LastModifiedAt) and constants/singletons (Category) alike.

private val fileSize: Property.Value<TInteger> =
    valueProperty(id = "${File.ID}/size", schema = TInteger.Schema)

/**
 * Byte length of the file (`0` for folders). A mutable, op-written stored value
 * property — set via `Entity.create(ctx, File.create(...)) { set(File.Size, n) }`,
 * read via `entity.get(File.Size)`, projected via `relations(File.Size)`, sorted
 * via `list.sortBy(File.Size)`. The value factory is registered in
 * `fileServiceCommonModule`.
 */
val File.Companion.Size: Property.Value<TInteger> get() = fileSize

private val fileLastModifiedAt: Property.Value<UnixInstant> =
    valueProperty(id = "${File.ID}/lastModifiedAt", schema = UnixInstant.Schema)

/**
 * When this row was last touched **in the realm** (epoch millis via `.get()`): uploaded,
 * overwritten, moved or renamed. A mutable, op-written stored value property; see [Size] for the
 * access pattern.
 *
 * ⚠⚠ Not the date a file browser shows as "Modified" — that is [ContentModifiedAt]. A photo
 * taken in 2019 and backed up today has today's date here, which is what makes it the key of the
 * Home "recent" feed and useless as the file's own date. The two-timestamp split is the one every
 * sync service makes (OneDrive: `lastModifiedDateTime` against
 * `fileSystemInfo.lastModifiedDateTime`); `file-service-history.md` §3.4.
 */
val File.Companion.LastModifiedAt: Property.Value<UnixInstant> get() = fileLastModifiedAt

private val fileContentModifiedAt: Property.Value<UnixInstant> =
    valueProperty(id = "${File.ID}/contentModifiedAt", schema = UnixInstant.Schema)

/**
 * When the file's **content** was last modified, as the file itself records it (epoch millis via
 * `.get()`): the source file's own modification time, carried across the upload, or the moment of
 * the write where the uploader could not read one. This is the "Modified" date a user means, and
 * the one Finder and the Drive listings show.
 *
 * Written by every content write (upload and overwrite) and by nothing else — a move or a rename
 * carries it over unchanged, which is the whole difference from [LastModifiedAt].
 *
 * ⚠⚠ **Absent on a file** written before FileService v17, and on every folder. Absent means "this
 * row predates the property", never the epoch: read it through [contentModifiedAtOrCreated], which
 * falls back to [CreatedAt] — seeded from the same source timestamp, so for a file that was never
 * overwritten it is the same instant.
 */
val File.Companion.ContentModifiedAt: Property.Value<UnixInstant> get() = fileContentModifiedAt

/**
 * The file's own "Modified" date from the two stored instants that can answer it — the one place
 * the fallback [ContentModifiedAt] documents is spelled, so no reader invents its own.
 */
fun contentModifiedAtOrCreated(contentModifiedAtMillis: Long?, createdAtMillis: Long?): Long? =
    contentModifiedAtMillis ?: createdAtMillis

private val fileCreatedAt: Property.Value<UnixInstant> =
    valueProperty(id = "${File.ID}/createdAt", schema = UnixInstant.Schema)

/**
 * Creation wall-clock time (epoch millis via `.get()`). An **immutable**
 * op-written stored value property: written once when the file/folder is first
 * created and preserved across overwrites and moves (unlike [LastModifiedAt],
 * which is bumped on every write). Read via `entity.get(File.CreatedAt)`,
 * projected via `relations(File.CreatedAt)`. Re-introduced for the
 * FileDetailsScreen "Created" row (file-service-history.md §3.3); the value
 * factory is registered in `fileServiceCommonModule`.
 */
val File.Companion.CreatedAt: Property.Value<UnixInstant> get() = fileCreatedAt

/**
 * Chain accessor that makes [File.CreatedAt] referenceable **inside a predicate** —
 * `.filter<File> { createdAt.inRange(start, end) }` — the read the Photos feed's keyset page is
 * built on (`photos-app.md` PH-G1).
 *
 * ⚠ The first accessor of its kind for a *stored value* property: [name]'s twin resolves a cheap
 * dynamic property, while this one references a value the engine already has on the row, so the
 * predicate costs a map lookup rather than a computation. Same mechanism either way — the
 * reference is appended to the chain and resolved per row during the scan — and the same
 * restriction: only valid on a chain RO.
 *
 * ⚠ A row with no stored `CreatedAt` does not match any window. That is the same call
 * `PhotosCapturedBetween` makes (absent is not epoch-zero) and it must stay that way: reading
 * absence as `0` would file every timestamp-less file under whichever window reaches back
 * furthest.
 */
val File.createdAt: UnixInstant
    get() {
        val ro = this as? RequestObject
            ?: error("File.createdAt requires a chain RO (RequestObject); was called on $this")
        return ro.context.roProvider().createRequestObject(
            type = UnixInstant.Schema.toType(),
            requestChain = ro.requestChain.addReference(fileCreatedAt.id),
            context = ro.context,
        )
    }

private val fileContentId: Property.Value<TString> =
    valueProperty(id = "${File.ID}/contentId", schema = TString.Schema)

/**
 * The file's **content identity** — a stable random id the file's ciphertext
 * chunks + manifest are addressed by, independent of the file's path
 * (content-addressing Phase 1, `file-content-addressing.md` §3.1). Minted once by
 * `UploadFileImplementation` and carried unchanged across move/rename/copy, so
 * relocating a file is pure metadata: the same `contentId` still resolves the same
 * bytes, no re-encryption or re-upload. A mutable-in-principle but move-stable
 * op-written stored value; see [Size] for the access pattern. Deliberately a
 * **random** id, never the content hash — a content-derived relay-visible address
 * would be a confirmation-of-file oracle (§3.1). Synced eagerly like [Size] so a
 * device that has not materialised the bytes still knows where to fetch them; the
 * value factory is registered in `fileServiceCommonModule`.
 */
val File.Companion.ContentId: Property.Value<TString> get() = fileContentId

/**
 * Chain accessor for [ContentId] inside a predicate — `.filter<File> { contentId oneOf ids }`, which
 * is how Photos finds the files behind a set of favourites or album covers in one fused walk
 * instead of reading the whole library to look them up (`photos-app.md` §39). [createdAt]'s twin:
 * a stored value, read off the row, valid only on a chain RO.
 *
 * ⚠ Copies share a content id (a copy is pure metadata), so a match is *every* file holding that
 * content, not one.
 */
val File.contentId: TString
    get() {
        val ro = this as? RequestObject
            ?: error("File.contentId requires a chain RO (RequestObject); was called on $this")
        return ro.context.roProvider().createRequestObject(
            type = TString.Schema.toType(),
            requestChain = ro.requestChain.addReference(fileContentId.id),
            context = ro.context,
        )
    }

private val fileContentHash: Property.Value<TString> =
    valueProperty(id = "${File.ID}/contentHash", schema = TString.Schema)

/**
 * The file's **content hash** — the whole-file plaintext integrity anchor
 * (`IncrementalSha256` hash-chain), stamped at upload and synced eagerly like
 * [Size] (content-addressing Phase 1 §3.1). Lives only inside the encrypted
 * metadata; it is **never** a relay-visible address (that is [ContentId]'s job, and
 * it is random precisely so it leaks nothing). Buys integrity + within-realm dedup
 * lookups + "did this content actually change?" (e.g. the folder-link publish
 * loop). A stored **value**, not a `dynamicProperty`: a dynamic property computes
 * from the bytes, which a non-materialised device does not have — same reasoning as
 * [Preview]'s stored-thumbnail note.
 */
val File.Companion.ContentHash: Property.Value<TString> get() = fileContentHash

/**
 * Chain accessor that makes [File.ContentHash] referenceable **inside a predicate** —
 * `.filter<File> { contentHash equalTo h }` — the within-realm dedup lookup the property's own
 * KDoc has promised since content-addressing Phase 1 and which had no caller until now
 * (`photos-app.md` PH-G3). Same mechanism and same restriction as [createdAt]: the reference is
 * appended to the chain and resolved per row during the scan, and it is only valid on a chain RO.
 *
 * ⚠⚠ A reference is not an index. The engine has no property index at all — `search` is a
 * linear walk (`SearchImplementation`) — so this makes a hash *expressible*, not cheap. Asking it
 * once per candidate asset is O(library) per question; the way to spend one walk on many questions
 * is `contentHash oneOf hashes` — `RealmContentReads` in `:app-ingest` asks a batch per fused walk,
 * in byte-bounded replies (`photos-app.md` §40).
 *
 * ⚠ A file uploaded before the hash was stamped has no `contentHash`, and no predicate over it
 * matches such a row. Absence means "unknown", never "different" — which is why nothing here
 * treats a non-match as proof that two files differ.
 */
val File.contentHash: TString
    get() {
        val ro = this as? RequestObject
            ?: error("File.contentHash requires a chain RO (RequestObject); was called on $this")
        return ro.context.roProvider().createRequestObject(
            type = TString.Schema.toType(),
            requestChain = ro.requestChain.addReference(fileContentHash.id),
            context = ro.context,
        )
    }

private val fileCaptureLatE7: Property.Value<TInteger> =
    valueProperty(id = "${File.ID}/captureLatE7", schema = TInteger.Schema)

/**
 * Where the photograph was taken, latitude in E7 degrees — read from the file's own EXIF at upload
 * (`timeline-photos.md` T-P4). Absent on every non-photo, on a photo whose camera recorded no fix,
 * and on one whose GPS tags the platform redacted before we ever saw the bytes.
 *
 * ⚠ Written **once, at upload**, and never revised: it describes the moment of capture, which
 * cannot change afterwards. Same posture as [CreatedAt].
 *
 * ⚠ Stored as two scalars rather than one composite because a value property is a `ValueSchema` —
 * scalars are what the shape supports, and a reference property (the other option) would mean
 * minting an entity per photo location, which is precisely what this is not.
 */
val File.Companion.CaptureLatE7: Property.Value<TInteger> get() = fileCaptureLatE7

private val fileCaptureLngE7: Property.Value<TInteger> =
    valueProperty(id = "${File.ID}/captureLngE7", schema = TInteger.Schema)

/** Longitude in E7 degrees; see [CaptureLatE7]. The two are written together or not at all. */
val File.Companion.CaptureLngE7: Property.Value<TInteger> get() = fileCaptureLngE7

private val filePixelWidth: Property.Value<TInteger> =
    valueProperty(id = "${File.ID}/pixelWidth", schema = TInteger.Schema)

private val filePixelHeight: Property.Value<TInteger> =
    valueProperty(id = "${File.ID}/pixelHeight", schema = TInteger.Schema)

/**
 * The image or video's size in **stored** pixels, read from the file itself at upload
 * (`photos-app.md` PH-7). Absent on everything that is not a photo or a video, and on any file
 * whose container this codebase cannot read.
 *
 * ⚠⚠ Stored, not displayed. A portrait iPhone photo is stored landscape with [Orientation] `6`;
 * showing these two numbers to a user without applying the orientation reports every such photo
 * as being the wrong way round. `MediaMetadata.displayWidth` does that fold — use it rather than
 * repeating the rule at each call site.
 *
 * ⚠ Same posture as [CaptureLatE7]: written once at upload and never revised, because it describes
 * the bytes, which do not change. A stored value rather than a `dynamicProperty` for [Preview]'s
 * reason — a dynamic property computes from the bytes, which a non-materialised device does not
 * have.
 */
val File.Companion.PixelWidth: Property.Value<TInteger> get() = filePixelWidth

/** The stored pixel height. See [PixelWidth] — the two are written together or not at all. */
val File.Companion.PixelHeight: Property.Value<TInteger> get() = filePixelHeight

private val fileOrientation: Property.Value<TInteger> =
    valueProperty(id = "${File.ID}/orientation", schema = TInteger.Schema)

/**
 * EXIF orientation, **1–8**, describing how the stored pixels must be transformed to be shown
 * upright (`photos-app.md` PH-7).
 *
 * ⚠⚠ Absent means *"the file did not say"*, and a reader must then assume `1` (upright) — it must
 * NOT store `1` here on its behalf, because "no EXIF at all" and "EXIF that says upright" are
 * different facts and only the second is evidence.
 *
 * ⚠ Values outside 1–8 are refused at parse rather than stored: a viewer handed `0` cannot
 * interpret it, and a number it cannot interpret is worse than a number it does not have.
 */
val File.Companion.Orientation: Property.Value<TInteger> get() = fileOrientation

private val fileCameraMake: Property.Value<TString> =
    valueProperty(id = "${File.ID}/cameraMake", schema = TString.Schema)

private val fileCameraModel: Property.Value<TString> =
    valueProperty(id = "${File.ID}/cameraModel", schema = TString.Schema)

/**
 * Who made the camera, as EXIF spells it — "Apple", "Google", "NIKON CORPORATION"
 * (`photos-app.md` PH-7). Absent on a screenshot, on anything re-encoded by a messaging app, and
 * on every non-photo.
 *
 * ⚠ Recorded verbatim, not normalised. "NIKON CORPORATION" and "Nikon" are what two of their own
 * cameras write, and deciding they are the same manufacturer is a presentation question — the
 * place that displays it can fold them; the place that stores it must not, or the original is
 * gone.
 */
val File.Companion.CameraMake: Property.Value<TString> get() = fileCameraMake

/** The camera model, as EXIF spells it — "iPhone 15 Pro", "Pixel 8". See [CameraMake]. */
val File.Companion.CameraModel: Property.Value<TString> get() = fileCameraModel

private val fileDurationMs: Property.Value<TInteger> =
    valueProperty(id = "${File.ID}/durationMs", schema = TInteger.Schema)

/**
 * A video's playback length in milliseconds (`photos-app.md` PH-7), from the container's own
 * movie header. Absent on every still, and on a video still being recorded — a header written
 * mid-capture carries an "unknown" sentinel that the parser refuses rather than folding into a
 * plausible 49-day duration.
 */
val File.Companion.DurationMs: Property.Value<TInteger> get() = fileDurationMs

// Derived category — computed from the file's name on read, the same way [Name] is.
//
// ⚠⚠ It was a stored `valueProperty` until PH-1 (`photos-app.md`) and **nothing ever wrote it**:
// every consumer re-derived the answer from the filename instead — Drive's row glyph
// (`FileMapper`, whose comment called this "a vestigial property"), both photo ops
// (`PhotosCapturedBetweenImplementation`, `PhotosNearImplementation`) and the preview resolvers,
// three copies of one `when`. So a chain predicate on it resolved to null on every row, which is
// how the Photos feed found this: `filter<File> { category equalTo FileCategory.Image }` failed
// with "Could not resolve property reference" against a realm full of JPEGs.
//
// Deriving it is what the platform was already doing; this only moves the derivation to where a
// query can reach it. A no-param, non-deferred dynamic property resolves synchronously per row
// during a scan (the [Name] contract), and needs no backfill: a file uploaded years ago answers
// as readily as one uploaded now, because the answer was never in the store to begin with.
private val fileCategoryRelation: Property.Dynamic<TConstant> = dynamicProperty<File, TConstant>(
    id = FileCategory.ID,
    receiver = File.Schema,
) { categoryOf() }

// The same relation, re-typed for consumers. The template above must record as `TConstant`
// (see [FileCategoryOf] for why a sealed interface cannot have a placeholder), while every
// reader wants the category it actually gets back — so the handle wraps the recorded relation
// with the decoder that re-types it. `entity.get(File.Category)` stays `FileCategory?`.
private val fileCategory: Property.Dynamic<FileCategory> = Property.Dynamic(
    id = fileCategoryRelation.id,
    relationSchema = fileCategoryRelation.relationSchema,
    decoder = { it as? FileCategory },
)

/**
 * File category (a constant/singleton — `FileCategory.Image`, …), derived from the file's name on
 * read. Projected via `relations(File.Category)`, read via `entity.get(File.Category)`, and —
 * because it is a cheap synchronous dynamic property — usable in a predicate via the [category]
 * chain accessor. Resolved by [FileCategoryOf]; registered in `fileServiceCommonModule`.
 */
val File.Companion.Category: Property.Dynamic<FileCategory> get() = fileCategory

// Derived "is this in the trash" — computed from the path on read, like [Name] and [Category].
//
// ⚠ A derived property rather than a stored flag, and the reason is PH-D4's own decision: deleting
// *moves* the file to `/Trash`, so the path already IS the state. A stored `trashed` boolean beside
// it would be a second source of truth that a move could contradict — and the move is the thing
// that actually happens.
//
// ⚠⚠ It exists so the Photos feed can EXCLUDE the trash **in the chain**. The DSL has no negation
// and no `startsWith`, so `path` cannot express "not under /Trash" as a predicate; a boolean the
// engine resolves per row can (`filter<File> { trashed equalTo … }`). Filtering client-side after
// the page instead would hand the pager short pages and end the feed early — a real bug, not a
// tidiness argument.
private val fileTrashed: Property.Dynamic<TBoolean> = dynamicProperty<File, TBoolean>(
    id = "${File.ID}/trashed",
    receiver = File.Schema,
) { path.contains("$TRASH_ROOT/") }

/**
 * Whether this file sits in the realm's trash (`photos-app.md` PH-5). Derived from [path] on read;
 * projected via `relations(File.Trashed)` and usable in a predicate via the [trashed] accessor.
 *
 * ⚠ `contains`, not `startsWith` — the DSL has no `startsWith`. For a realm path the two agree
 * except for a file whose *name* contains `/Trash/`, which no filesystem allows.
 */
val File.Companion.Trashed: Property.Dynamic<TBoolean> get() = fileTrashed

/**
 * Chain accessor that makes [File.Trashed] referenceable inside a predicate —
 * `.filter<File> { trashed equalTo falseValue() }`. Same mechanism as [name] and [category].
 */
val File.trashed: TBoolean
    get() {
        val ro = this as? RequestObject
            ?: error("File.trashed requires a chain RO (RequestObject); was called on $this")
        return ro.context.roProvider().createRequestObject(
            type = TBoolean.Schema.toType(),
            requestChain = ro.requestChain.addReference(fileTrashed.id),
            context = ro.context,
        )
    }

/**
 * The predicate a photo reader actually wants: *this file is not in the trash*.
 *
 * ⚠ It exists because `equalTo` compares `Data`, not Kotlin values — passing a bare `false` makes
 * `equalToLogic` return false for **every** row (its first line is `if (other !is Data) return
 * false`), which would silently empty the feed rather than fail. Wrapping the constant here means
 * no caller can make that mistake.
 */
fun File.notTrashed(): TBoolean {
    val ro = this as? RequestObject
        ?: error("File.notTrashed requires a chain RO (RequestObject); was called on $this")
    return trashed equalTo false.toTBoolean(ro.context)
}

/**
 * The predicate a media reader actually wants: *this file is a photograph or a video*.
 *
 * ⚠⚠ It exists because a media grid is a **disjunction**, and separate `filter` steps are an *and*.
 * Photos read `category equalTo FileCategory.Image` at three sites and therefore could not show a
 * video at all — the app's whole video story was blocked on the chain DSL having no way to say
 * "either of these" (`photos-video-support.md` §M1). [oneOf] is that way; this names the set once
 * so the feed's rows, the feed's *count* and search cannot drift apart. The file already records
 * what that drift looks like: a count that filters differently from the rows renders as tiles that
 * never fill.
 *
 * ⚠ A plain helper over [oneOf], deliberately **not** a new `Property.Dynamic`. A derived property
 * would put a Photos-shaped concept into the stored schema and cost a service bump, to express
 * something the predicate can already say. Same shape and same reasoning as [notTrashed].
 *
 * ⚠ No RO guard of its own, unlike [notTrashed] — that one needs `ro.context` to wrap its constant,
 * whereas [category] already fails with the same message for a non-chain receiver.
 */
fun File.isMedia(): TBoolean = category oneOf listOf(FileCategory.Image, FileCategory.Video)

/**
 * Chain accessor that makes [File.Category] referenceable inside a predicate —
 * `.filter<File> { category equalTo FileCategory.Image }`, which is how a reader keeps documents
 * and folders out of a media grid without shipping them across IPC first. ⚠ The Photos feed used to
 * be that example and is no longer: it wants images *and* videos, which is [isMedia] over [oneOf].
 *
 * ⚠ Typed `TConstant`, not `FileCategory`, deliberately: what the chain hands a predicate is a
 * placeholder for a value no row has produced yet, and `FileCategory` is a **sealed** interface —
 * a placeholder implementing it would be a new subtype, breaking every exhaustive `when` over the
 * category in the repo. `equalTo` is declared on `Data`, so the predicate reads the same either
 * way.
 *
 * ⚠ The comparison is identity-of-constant, not string equality: pass `FileCategory.Image`, never
 * `"image"`.
 */
val File.category: TConstant
    get() {
        val ro = this as? RequestObject
            ?: error("File.category requires a chain RO (RequestObject); was called on $this")
        return ro.context.roProvider().createRequestObject(
            type = TConstant.Schema.toType(),
            requestChain = ro.requestChain.addReference(fileCategory.id),
            context = ro.context,
        )
    }

// ── entity relation: File → its FileModification history ─────────────────────
// An entity relation (docs/proposals/core-nested-projection.md §3.3): the file's
// append-only `FileModification` log, resolved from a per-file array keyed by the
// file's own DataId (an EntityPropertyDataId). The wire selector is the uniform
// StaticPropertyRelation; the per-file array is maintained by
// `FileHistoryRelationMaintainer`. Registered via `entityRelation(...)` in
// `fileServiceCommonModule`. The File-rooted, O(file's mods) reverse of the
// realm-wide `FileService.Relations.MODIFICATIONS` log — and the reverse of the
// forward `FileModification.Device`-style reference direction.
private val fileHistory: Property.Value<TList<FileModification>> =
    entityRelation(id = "${File.ID}/fileHistory", element = FileModification.Schema)

/**
 * This file's [FileModification] history — the File-rooted activity log for the
 * FileDetailsScreen, projected via `relations(File.FileHistory) { … }` (each row's
 * `relations(FileModification.Device)` etc. nest under it) and read via
 * `entity.get(File.FileHistory)`. Bounded by [FileModification.RETENTION_CAP] per
 * file; populated by `FileHistoryRelationMaintainer`.
 */
val File.Companion.FileHistory: Property.Value<TList<FileModification>> get() = fileHistory

// Dynamic — chain-computed; Preview shows the param-bearing case.

private val fileData: Property.Dynamic<TByteArray> = dynamicProperty<File, TByteArray>(
    id = "${File.ID}/data",
    receiver = File.Schema,
    traits = listOf(Syncable(SyncStrategy.OnDemand), PrimaryContent()),
) { getData() }

/**
 * The file's raw bytes, projected via `relations(File.Data)` and read via
 * `entity.get(File.Data)` (or `getProperty(File.Data.id)`).
 *
 * `entity.get(File.Data)` returns `null` in three cases callers treat uniformly
 * as "no local bytes":
 *
 *  - the property was never written (files synced before their bytes);
 *  - it materialised as `null` (a folder inherits `File.Data` from `File.Schema`
 *    but has no payload — `getProperty` returns `null`);
 *  - it is still a `PendingOnDemandProperty` placeholder (left by
 *    `SelectImplementation`, not yet substituted by `OnDemandMaterialiser`). The
 *    handle's null-tolerant `decode` (an `as?` cast) collapses the placeholder to
 *    `null` rather than crashing.
 */
val File.Companion.Data: Property.Dynamic<TByteArray> get() = fileData

// Derived name — the last path segment, chain-computed from `File.path` (which is
// the only stored field; the name is never persisted). A no-param, non-deferred
// dynamic property: its template is pure string manipulation, so it resolves
// synchronously and — unlike the deferred, IO-backed `Preview` — can be used
// inside a predicate (`root.files.traverse().filter<File> { name contains q }`
// resolves it per row during the scan). `deferred` stays false: flipping it would
// only change projection behaviour, and there is no IO cost to defer.
private val fileName: Property.Dynamic<TString> = dynamicProperty<File, TString>(
    id = "${File.ID}/name",
    receiver = File.Schema,
) { path.substringAfterLast("/") }

/**
 * The file or folder's display name — the last segment of [path], derived on read.
 * Projected via `relations(File.Name)`, read via `entity.get(File.Name)`, sorted via
 * `list.sortBy(File.Name)`, and — because it is a cheap synchronous dynamic property —
 * usable in a predicate via the [name] chain accessor. Registered in `fileServiceCommonModule`.
 */
val File.Companion.Name: Property.Dynamic<TString> get() = fileName

/**
 * Chain accessor that makes [File.Name] referenceable inside a predicate —
 * `.search<File> { name contains q }` / `.filter { … }` / `.searchAll { … }`. It
 * appends the dynamic-property reference to the chain (the engine resolves it per
 * row); it can't go through `getField`, which only surfaces stored schema fields,
 * nor through the projection `relations(…)` selector. Only valid on a chain RO,
 * mirroring `Entity<T>.relation` / `Entity<T>.select`.
 */
val File.name: TString
    get() {
        val ro = this as? RequestObject
            ?: error("File.name requires a chain RO (RequestObject); was called on $this")
        return ro.context.roProvider().createRequestObject(
            type = TString.Schema.toType(),
            requestChain = ro.requestChain.addReference(fileName.id),
            context = ro.context,
        )
    }

private val filePreview: Property.Dynamic<TByteArray> = dynamicProperty<File, TByteArray>(
    id = "${File.ID}/preview",
    receiver = File.Schema,
    params = listOf(TInteger.Schema, TInteger.Schema),
    // No `Syncable` trait: a dynamic (chain-computed) property is recomputed from
    // local bytes on every read and is never *written* into the entity, so
    // `SyncObserver` never sees a value to emit — a `Syncable` trait here would be
    // inert (it previously carried `Syncable(Eager)`, which did nothing). To reach
    // a device that holds no bytes, the thumbnail must be a *stored* value: see
    // [Thumbnail] + docs/proposals/drive-synced-thumbnails.md §2.1.
    //
    // `deferred = true`: the resolver does real IO (read + decrypt + decode +
    // scale — ~18 ms/row on the 2k-folder trace), so no projection may pay it
    // inline by default (`core-deferred-properties.md` §7 Phase 4). One-shot
    // callers that genuinely want the bytes wrap with `eager(...)` — see
    // `TenshuFilesRepository.getPreview`.
    deferred = true,
) { params ->
    val width = params[0] as Long
    val height = params[1] as Long
    getPreview(width = width.toInt(), height = height.toInt())
}

/**
 * A rendered preview thumbnail, **computed on read from this device's local
 * bytes** (so it resolves to `null` on a device that doesn't hold them). Used for
 * higher-fidelity surfaces where bytes are local (file details, on-open). For the
 * small list-tier thumbnail that must appear on *every* device, use [Thumbnail].
 * The no-arg handle is used at registration; bind dimensions for projection with
 * [Preview]`(width, height)`.
 */
val File.Companion.Preview: Property.Dynamic<TByteArray> get() = filePreview

/** Preview handle with `(width, height)` bound — `relations(File.Preview(100, 100))`. */
@Suppress("FunctionName")
fun File.Companion.Preview(width: Int, height: Int): Property.Dynamic<TByteArray> =
    Preview.withParams(width.toTInteger(), height.toTInteger())

private val fileViewerPreview: Property.Dynamic<TByteArray> = dynamicProperty<File, TByteArray>(
    id = "${File.ID}/viewerPreview",
    receiver = File.Schema,
    params = listOf(TInteger.Schema, TInteger.Schema),
    // Deferred for the same reason [Preview] is, and more so: this is the one that may read a whole
    // original. Nothing may pay it inline by default; the viewer wraps it in `eager(...)`.
    deferred = true,
) { params ->
    val width = params[0] as Long
    val height = params[1] as Long
    getPreview(width = width.toInt(), height = height.toInt(), preferOriginal = true)
}

/**
 * [Preview] for **the one photograph a viewer opened**, as opposed to a feed of many frames —
 * D-17's `GetPreview.Params.PREFER_ORIGINAL` (`drive-xxlarge-thumbnail-rung.md` §4.2, X-3).
 *
 * The difference is one rule: a stored rung that **covers** the request still wins (a downscale of
 * the same picture, far cheaper than reading a whole file), but a rung *stretched* up to
 * `GetStoredThumbnail.COVER_TOLERANCE` is refused, and a device holding the bytes renders the real
 * thing at the size asked for. ⚠⚠ That trade is right for one photograph on screen and wrong for a
 * grid: [Preview] keeps the stretch, because paying a whole-file read per tile is what the tolerance
 * exists to avoid. **Choose by surface, never by size.**
 *
 * ⚠ **On a device without the bytes it is identical to [Preview]** — there is nothing to render
 * from, so the ladder answers the same way. ⚠⚠ On a device *with* bytes it can be strictly worse:
 * where the request is inside the stretch band and the original is in a format this platform cannot
 * decode (HEIC off macOS), refusing the stretch leaves the render as the only route and it fails, so
 * this answers `null` where [Preview] would have served the rung. Rare, and not a regression — the
 * arithmetic this replaced took the same branch — but it is why a caller that merely wants *a*
 * picture should use [Preview]. That is what lets the viewer bind it unconditionally
 * instead of probing `HasLocalData` first, and it is why the phase that introduced it could delete
 * `renderEdgeOnByteHolder`, which forced a render by inflating the requested size.
 *
 * ⚠⚠ **A separate property, not a third parameter on [Preview].** The parameter list of a dynamic
 * property is positional schema, so widening `tenshu/file/preview` would reshape a property Drive,
 * Photos, Timeline and the web SDK all bind — every caller in lockstep for a flag only one of them
 * wants. Additive is what [ThumbnailXXLarge] did for the same reason.
 */
val File.Companion.ViewerPreview: Property.Dynamic<TByteArray> get() = fileViewerPreview

/** Viewer-preview handle with `(width, height)` bound — `relations(File.ViewerPreview(2048, 2048))`. */
@Suppress("FunctionName")
fun File.Companion.ViewerPreview(width: Int, height: Int): Property.Dynamic<TByteArray> =
    ViewerPreview.withParams(width.toTInteger(), height.toTInteger())

/**
 * The [org.tenshu.core.trait.ContentDerivation] namespace of every `File.Thumbnail*` rung: a rung's
 * content id is `sha256("thumbnail:$fileContentId:$class")`.
 *
 * ⚠⚠ Part of every stored thumbnail's address (it feeds `manifestContentRef`), so it can never
 * change — `content-plane-cr2-quorumless.md` §10.11.
 */
const val THUMBNAIL_CONTENT_NAMESPACE = "thumbnail"

private val fileThumbnail: Property.Dynamic<TByteArray> = dynamicProperty<File, TByteArray>(
    id = "${File.ID}/thumbnail",
    receiver = File.Schema,
    traits = listOf(Syncable(SyncStrategy.OnDemand), DerivedContent(THUMBNAIL_CONTENT_NAMESPACE, GetStoredThumbnail.CLASS_LIST)),
) { getStoredThumbnail(GetStoredThumbnail.CLASS_LIST) }

/**
 * A small preview thumbnail — **stored bytes on the content plane** (unlike the
 * compute-on-read [Preview]), generated once on the device that holds the file's
 * bytes at upload time and present even where the bytes are not (an `OnDemand` /
 * online-only file). `null` for non-image / un-renderable files, for files uploaded
 * before this property existed, and on a device that has not fetched the blob yet.
 *
 * ⚠⚠ **`OnDemand` since C7 (`sync-device-catch-up.md` §11.3), and this used to be an
 * eagerly-synced `Property.Value`.** The C6 census measured what that cost: across a
 * real store, [Thumbnail] + [ThumbnailLarge] were **97.26 % of every byte a `File`
 * row occupied** — ~18.4 KB against ~336 B of actual metadata. So every thumbnail of
 * every file rode the eager entity block to every paired device before that device
 * could be considered caught up, whether or not anything ever displayed it. The bytes
 * now live on the content plane and arrive only when something asks; [GetStoredThumbnail]
 * is the resolver.
 *
 * ⚠ The trade is **join cost for first-view latency** — deliberate. A read returns what
 * is local, `null` included, so a row shows its metadata immediately and its preview once
 * the blob arrives. ⚠⚠ Fetching one is not automatic and not every terminal does it:
 * [SyncStrategy.OnDemand] is the canonical account, and it is worth reading before adding
 * a consumer of this property.
 *
 * **Pure background machinery (drive-synced-thumbnails.md §10.1).** This handle is
 * *written* by thumbnail generation (`UploadFileImplementation`) and *read* only by
 * the `GetPreview` resolver as the byte-less fallback. Consumers do **not** read it
 * directly: [Preview] is the single "best available preview" API and selects between
 * the live, compute-from-bytes preview and this stored thumbnail internally. There is
 * intentionally no `relations(File.Thumbnail)` projection in app code anymore.
 * See docs/proposals/drive-synced-thumbnails.md.
 */
val File.Companion.Thumbnail: Property.Dynamic<TByteArray> get() = fileThumbnail

private val fileThumbnailLarge: Property.Dynamic<TByteArray> = dynamicProperty<File, TByteArray>(
    id = "${File.ID}/thumbnailLarge",
    receiver = File.Schema,
    traits = listOf(Syncable(SyncStrategy.OnDemand), DerivedContent(THUMBNAIL_CONTENT_NAMESPACE, GetStoredThumbnail.CLASS_LARGE)),
) { getStoredThumbnail(GetStoredThumbnail.CLASS_LARGE) }

/**
 * The **larger, JPEG-encoded** preview class (~256 px longest edge) — the second
 * thumbnail class (`LARGE`) of the thumbnail-class model
 * (drive-synced-thumbnails.md §10.2, Phase 3). Like [Thumbnail] (the small `LIST`
 * class) it is generated once on the device that holds the file's bytes, and is
 * sharp on a device that holds no bytes, where an upscaled `LIST` would be blurry.
 * `null` for non-image / un-renderable files, over-cap renders, files uploaded before
 * this property existed, and on a device that has not fetched the blob yet.
 *
 * ⚠⚠ **`OnDemand` since C7** — see [Thumbnail] for the measurement that motivated it and
 * for how a consumer actually gets the bytes. This class is the larger half of that
 * 97.26 %: ~11.0 KB of the ~18.4 KB.
 *
 * Being its own property is what keeps it **independently evictable** (offline-v3 §4)
 * — and C7 sharpens that: the two classes now resolve to *separate* content-plane
 * blobs under separate content ids, so dropping `LARGE` really does leave `LIST`
 * untouched. ⚠ `LARGE` itself is never evicted — the keeper's ceiling spends only the
 * backfilled classes above it (`drive-xxlarge-thumbnail-rung.md` §4.7), because a row
 * re-blanks without its small ones.
 *
 * **Pure background machinery (drive-synced-thumbnails.md §10.1).** Written by
 * thumbnail generation (`UploadFileImplementation`) and read **only** by the
 * `GetPreview` resolver, which picks the smallest class that satisfies the requested
 * size. Consumers never read it directly — [Preview] is the single "best available
 * preview" API.
 */
val File.Companion.ThumbnailLarge: Property.Dynamic<TByteArray> get() = fileThumbnailLarge

private val fileThumbnailXLarge: Property.Dynamic<TByteArray> = dynamicProperty<File, TByteArray>(
    id = "${File.ID}/thumbnailXLarge",
    receiver = File.Schema,
    traits = listOf(Syncable(SyncStrategy.OnDemand), DerivedContent(THUMBNAIL_CONTENT_NAMESPACE, GetStoredThumbnail.CLASS_XLARGE)),
) { getStoredThumbnail(GetStoredThumbnail.CLASS_XLARGE) }

/**
 * The **hero** preview class (`XLARGE`, ~1024 px JPEG) — the rung between [ThumbnailLarge] and the
 * file's whole original (`drive-synced-thumbnails.md` §12, T-1).
 *
 * ⚠⚠ **Why it exists.** Measured on a real 2304x4096 photo the stored ladder ran 100 px/4 KB,
 * 256 px/15 KB, then *nothing* until the 3 344 KB original — a **220x gap**. A hero band is
 * 840-1680 px, so on any device that did not upload the photo (every desktop, every second phone)
 * `GetPreview` had exactly two options: upscale the 256 px class 3-5x, or fetch the entire file.
 * This class is the missing middle.
 *
 * ⚠ Its weight is set by **its own dimensions, not the source's**: a 45 MP DSLR frame and a phone
 * snap both land near 139 KB here. That is the property that makes a class the right answer where
 * fetching originals is not — the latter grows without bound as cameras do.
 *
 * Same lifecycle as the other two classes: generated once at upload on the device that holds the
 * bytes, stored on the content plane under its own derived content id, `Syncable(OnDemand)` so it
 * travels only to a device that asks. `null` for non-image / un-renderable files, for over-cap
 * renders, for files uploaded before this class existed (⚠ nothing regenerates thumbnails — see
 * §12.6 T-2/T-3 for the two routes that close that), and on a device that has not fetched it yet.
 */
val File.Companion.ThumbnailXLarge: Property.Dynamic<TByteArray> get() = fileThumbnailXLarge

private val fileThumbnailXXLarge: Property.Dynamic<TByteArray> = dynamicProperty<File, TByteArray>(
    id = "${File.ID}/thumbnailXXLarge",
    receiver = File.Schema,
    traits = listOf(Syncable(SyncStrategy.OnDemand), DerivedContent(THUMBNAIL_CONTENT_NAMESPACE, GetStoredThumbnail.CLASS_XXLARGE)),
) { getStoredThumbnail(GetStoredThumbnail.CLASS_XXLARGE) }

/**
 * The **large** preview class (`XXLARGE`, 2048 px JPEG) — the rung between [ThumbnailXLarge] and the
 * file's whole original (`drive-xxlarge-thumbnail-rung.md`, D-12/D-13).
 *
 * ⚠⚠ **Why it exists.** [ThumbnailXLarge] is 1024 px, and a maximised viewer on a retina desktop
 * asks for ~2900 px: a 2.9x upscale of the hero, or the whole original — 18 MB for a DSLR frame,
 * which the web cannot fetch at all. A phone in portrait is a 1.4-1.9x upscale for the same reason.
 * 2048 px closes that band at 4-10 % of an original's bytes.
 *
 * ⚠ **Never a grid tile or a feed row** (§4.4): tiles cap at 1024 px, and at ~0.5 MB a row this
 * class would be §14.2's trap at 3x the cost. Only a full-screen viewer, the web lightbox and
 * Timeline's hero may name it.
 *
 * Same lifecycle as the rungs below it: minted at upload on the device holding the bytes, backfilled
 * onto older files once a device declares it wanted, stored on the content plane under its own
 * derived content id, `Syncable(OnDemand)` so it travels only to a device that asks. ⚠ Like every
 * backfilled class it is **evictable**, and at ~0.5 MB it is the heaviest of them — the rung the
 * keeper's ceiling was built for (§4.7). That ceiling spends them coldest-first (D-20), so `null`
 * also means "this device dropped it to stay under its cache limit" and a later read pulls it again.
 */
val File.Companion.ThumbnailXXLarge: Property.Dynamic<TByteArray> get() = fileThumbnailXXLarge

private val fileThumbnailMotion: Property.Dynamic<TByteArray> = dynamicProperty<File, TByteArray>(
    id = "${File.ID}/thumbnailMotion",
    receiver = File.Schema,
    traits = listOf(Syncable(SyncStrategy.OnDemand), DerivedContent(THUMBNAIL_CONTENT_NAMESPACE, GetStoredThumbnail.CLASS_MOTION)),
) { getStoredThumbnail(GetStoredThumbnail.CLASS_MOTION) }

/**
 * The **motion** preview class (`MOTION`) — a ~3 s, 256 px, silent H.264 loop of a video, so the
 * Photos grid can move the way Google Photos' does (`photos-video-support.md` M3).
 *
 * ⚠⚠ **Why a stored class rather than playing the file.** Google can stream the original for a
 * grid tile because its servers hold low-resolution transcodes; House Rule #1 forecloses that
 * permanently, so on any device that did not shoot the video the only alternatives were fetching
 * the whole original to animate a ~110 dp square, or nothing. This is the derivative that makes it
 * cheap — and, as with [ThumbnailXLarge], its weight is set by **its own** settings, not the
 * source's: measured across seven real sources spanning a **67x** range of source bitrate, output
 * varied by **1.11x** (§12.5). A 20-minute 4K recording and a 6-second phone clip cost the same.
 *
 * ⚠⚠ **Video only, and never a substitute for a still.** It is minted only for
 * [FileCategory.Video], and it is deliberately **outside [Preview]'s ladder** — `GetPreview`
 * answers "give me a bitmap" and a clip is not one. Putting it in that ladder would place a video
 * decoder behind a property every list row touches. A video's *still* preview continues to resolve
 * through [Thumbnail] / [ThumbnailLarge] / [ThumbnailXLarge] / [ThumbnailXXLarge] exactly as before,
 * from its poster
 * frame.
 *
 * ⚠ `null` for every non-video, for a video whose encode failed or was refused, for one uploaded
 * before this class existed (⚠ nothing regenerates thumbnails — `drive-synced-thumbnails.md` §12.6
 * T-2/T-3), for a clip **shorter than the poster offset** (§12.6 — a real library contains one),
 * and on a device that has not fetched the blob yet. A tile with no motion class shows its static
 * poster, which is what every video tile does today.
 *
 * Same lifecycle as the four still classes: generated once on the device that holds the bytes,
 * stored on the content plane under its own derived content id, `Syncable(OnDemand)` so it travels
 * only to a device that asks for it.
 */
val File.Companion.ThumbnailMotion: Property.Dynamic<TByteArray> get() = fileThumbnailMotion

interface FileDataIdProvider : CompositeDataIdProvider<File> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/file/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = File.Field.PATH
        )
    }
}
