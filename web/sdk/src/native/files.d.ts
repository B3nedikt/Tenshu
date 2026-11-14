// `FileSystem` — Drive's personal file system.
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

type NativeFileBytes =
  | { readonly ok: true; readonly bytes: Int8Array | null }
  | { readonly ok: false; readonly error: { readonly kind: string; readonly message?: string } }

// X-5: the large-preview read. Carries two things `NativeFileBytes` throws away, because the
// lightbox's choice between drawing this and downloading the whole original cannot be made from
// bytes alone (`drive-xxlarge-thumbnail-rung.md` §4.8).
type NativeLargePreview =
  | {
      readonly ok: true
      readonly bytes: Int8Array | null
      // Which stored class supplied `bytes`: 'xxlarge' | 'xlarge' | 'large' | 'list', or null.
      readonly thumbnailClass: string | null
      // ⚠⚠ The state of the largest class that could cover the request. 'absent' means the read
      // succeeded and it genuinely is not here; 'unknown' means we could not tell. ONLY 'absent'
      // licenses downloading the original — 'unknown' read as absence spends 6–18 MB on a network
      // blip, and read as presence leaves the picture blurry for ever.
      readonly coveringRung: 'present' | 'absent' | 'unknown'
    }
  | { readonly ok: false; readonly error: { readonly kind: string; readonly message?: string } }

// Control → Data → Files: the drive's totals (`FileSystem.inventory()`). Byte counts are JS
// numbers; `newestModifiedMillis` is 0 when there are no files. `byExtension` is largest-first,
// `extension` lower-cased and '' for none.
interface NativeFileInventory {
  readonly fileCount: number
  readonly folderCount: number
  readonly totalBytes: number
  readonly newestModifiedMillis: number
  readonly byExtension: ReadonlyArray<{
    readonly extension: string
    readonly count: number
    readonly bytes: number
  }>
}

interface FileSystemInstance {
  list(path: string): Promise<NativeFile[]>
  // The `limit` most-recently-modified entries across the whole drive,
  // newest first (the Home "recent" surface). Same walker shape as `list`.
  // Mirrors the native `FilesRepository.recentFiles`. ⚠ Rejects on a failed read — never `[]` for one.
  recentFiles(limit: number): Promise<NativeFile[]>
  // W4: files whose NAME contains `query`, case-insensitively, anywhere in the drive. Empty
  // query resolves []. The predicate runs proxy-side — no client-side re-filter.
  search(query: string): Promise<NativeFile[]>
  // W6: are this file's bytes on this device? ⚠ Per visible row only — never eagerly across a
  // listing (the op's contract).
  hasLocalData(path: string): Promise<boolean>
  // Creates a folder at `path`. Resolves `true` on success, `false` on
  // any error (including AlreadyExistsError) — seed-style callers can
  // treat it as idempotent.
  createFolder(path: string): Promise<boolean>
  // Uploads bytes as a new file at `parentPath/fileName`. Resolves
  // `true` on success, `false` on any error. Bytes persist across
  // reloads via IndexedDB. JS callers pass an `Int8Array` (the JS
  // representation of Kotlin `ByteArray`); convert from a browser
  // `File` via `new Int8Array(await file.arrayBuffer())`.
  uploadFile(
    parentPath: string,
    fileName: string,
    data: Int8Array,
  ): Promise<boolean>
  // Reads the bytes of the file at `path`. ⚠ Result-shaped: `bytes` is null when this device
  // legitimately has none (no such file, or the content is not fetched here), while a failed
  // read is `ok: false`. The old `Int8Array | null` meant both and callers assumed the first.
  getData(path: string): Promise<NativeFileBytes>
  // A preview thumbnail of the file at `path`, fit within `width × height` preserving aspect
  // ratio (a source already smaller keeps its dimensions). ⚠ Same three answers: `bytes` null
  // means "no preview for this file" (a folder, an .ics, an undecodable blob) — a real answer —
  // and `ok: false` means the read or decode broke.
  getPreview(
    path: string,
    width: number,
    height: number,
  ): Promise<NativeFileBytes>
  // X-5: the large preview for a full-screen surface — names the XXLARGE/XLARGE stored classes
  // by relation and reports which one answered, plus whether the covering class is absent or
  // merely unknown. See `NativeLargePreview`.
  getLargePreview(path: string, px: number): Promise<NativeLargePreview>
  // Renames the entry at `path` to `newName`, keeping it in the same
  // parent. Resolves `true` on success, `false` on any error (including
  // a name collision). Mirrors the native `FilesRepository.renameFile`.
  rename(path: string, newName: string): Promise<boolean>
  // Moves the entry at `sourcePath` into `destinationFolderPath` (a folder
  // path; `"/"` for the root), keeping its name. Resolves `true` on
  // success, `false` on any error. Mirrors `FilesRepository.moveFile`.
  move(sourcePath: string, destinationFolderPath: string): Promise<boolean>
  // Deletes the entry at `path` (recursive for folders). Resolves `true`
  // on success, `false` on any error. Mirrors `FilesRepository.deleteFile`.
  delete(path: string): Promise<boolean>
  // Metadata for the entry at `path` — `{ path, size, createdAt,
  // lastModified, files? }` (raw bytes + epoch-millis). Resolves `null`
  // when no entry exists at `path`. Mirrors `FilesRepository.fileDetails`.
  // ⚠ `row` null = no entry at that path (an answer); `ok: false` = the listing read failed.
  details(path: string): Promise<{ readonly ok: true; readonly row: NativeFileDetails | null } | NativeFailure>
  // Modification history for the file at `path`, newest first; empty on any
  // error or no history. Mirrors `FilesRepository.fileActivity`.
  activity(path: string): Promise<NativeFileActivity[]>
  // The drive's inventory for Control → Data → Files. ⚠⚠ Result-shaped: an empty drive is
  // `ok: true` with zero counts, a failed tree read is `ok: false` (kind 'read') — never zeros.
  // Projects no preview and no bytes (`drive-web-parity.md` §1.2i).
  inventory(): Promise<{ readonly ok: true; readonly inventory: NativeFileInventory } | NativeFailure>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const FileSystem: { getInstance(): FileSystemInstance }
}
