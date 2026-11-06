import { useRef, useState, type ChangeEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Menu, MenuItem } from '@tenshu/web-tokens/ui'
import type { File as FsEntry } from '@tenshu/sdk/files'
import {
  createFolder,
  deleteEntry,
  listFiles,
  move,
  rename,
  shareFolder,
  uploadFile,
} from '../lib/proxy'
import { basename, formatBytes, formatDate, isFolder, joinPath } from '../lib/paths'
import { defaultDescending, loadPrefs, savePrefs, type ListingPrefs, type SortKey } from '../lib/listingPrefs'
import { queryKeys } from '../lib/queryKeys'
import { Breadcrumbs } from './Breadcrumbs'
import { FileDetails } from './FileDetails'
import { ConfirmDialog, PromptDialog } from './Dialog'
import { MovePicker } from './MovePicker'
import { ShareDialog } from './ShareDialog'
import { ManageAccessView } from './ManageAccessView'
import { FileThumbnail } from './FileThumbnail'
import { OfflineDot } from './OfflineDot'
import { useFileOpener } from '../lib/useFileOpener'

type PendingAction =
  | { type: 'newFolder' }
  | { type: 'rename'; entry: FsEntry }
  | { type: 'delete'; entry: FsEntry }
  | { type: 'deleteMany'; paths: string[] }
  | null

// The Drive file browser (Phases C1–C3): navigate folders, upload, open a file
// for Details + Activity, and manage entries — New folder / Rename / Move /
// Delete, single or multi-selected. All reads/writes go through the apex
// bridge; mutations invalidate the current folder's listing.
export function FileBrowser({ initialPath = '/' }: { initialPath?: string } = {}) {
  // Sort + layout, remembered on this browser only — see listingPrefs for why not the
  // PreferenceService.
  const [prefs, setPrefsState] = useState<ListingPrefs>(loadPrefs)
  const setPrefs = (next: ListingPrefs) => {
    setPrefsState(next)
    savePrefs(next)
  }
  // ⚠ Initial value only, deliberately. Remounting on every change would discard the browser's
  // own navigation; App keys this component when a search hit needs to land somewhere new.
  const [path, setPath] = useState(initialPath)
  const [selectedPath, setSelectedPath] = useState<string | null>(null)
  const [checked, setChecked] = useState<Set<string>>(new Set())
  const [action, setAction] = useState<PendingAction>(null)
  const [mover, setMover] = useState<{ sources: string[] } | null>(null)
  const [sharing, setSharing] = useState<string | null>(null)
  // MB-4: the realm being managed right after a folder share completes.
  const [managing, setManaging] = useState<{ realmId: string; name: string } | null>(null)
  // Opening a file previews it inline (image / pdf / text) or downloads it —
  // the primary file action, matching native. Details live behind the row menu.
  const opener = useFileOpener()
  const queryClient = useQueryClient()

  const { data, isPending, error, isError } = useQuery({
    queryKey: queryKeys.files(path),
    queryFn: () => listFiles(path),
  })

  const entries = data ? sortEntries(data, prefs) : []

  function refresh() {
    queryClient.invalidateQueries({ queryKey: queryKeys.files(path) })
  }

  // Navigating changes the listing, so the per-folder selection is cleared.
  function goTo(target: string) {
    setPath(target)
    setChecked(new Set())
  }

  function toggleChecked(target: string) {
    setChecked((prev) => {
      const next = new Set(prev)
      if (next.has(target)) next.delete(target)
      else next.add(target)
      return next
    })
  }

  // Each handler resolves null on success (the dialog/picker closes) or an
  // error message to show. A mutated entry whose details drawer is open is
  // closed, since its path may no longer be valid.
  async function onCreateFolder(name: string): Promise<string | null> {
    const ok = await createFolder(joinPath(path, name))
    if (!ok) return `Couldn't create “${name}” — it may already exist.`
    refresh()
    return null
  }

  async function onRename(entry: FsEntry, newName: string): Promise<string | null> {
    if (newName === basename(entry.path)) return null
    const ok = await rename(entry.path, newName)
    if (!ok) return `Couldn't rename — “${newName}” may already exist.`
    if (selectedPath === entry.path) setSelectedPath(null)
    refresh()
    return null
  }

  async function onDelete(entry: FsEntry): Promise<string | null> {
    const ok = await deleteEntry(entry.path)
    if (!ok) return `Couldn't delete “${basename(entry.path)}”.`
    if (selectedPath === entry.path) setSelectedPath(null)
    refresh()
    return null
  }

  async function onMoveTo(sources: readonly string[], destFolder: string): Promise<string | null> {
    const results = await Promise.all(sources.map((p) => move(p, destFolder)))
    const failures = results.filter((ok) => !ok).length
    if (selectedPath && sources.includes(selectedPath)) setSelectedPath(null)
    setChecked(new Set())
    refresh()
    if (failures > 0) return `${failures} item${failures === 1 ? '' : 's'} couldn't be moved.`
    return null
  }

  // MB-4: share a folder out — bootstraps a realm, populates it, self-mounts.
  // On success, open Manage access so the user can add people right away. The
  // mutation's `variables` is the in-flight folder path, so a row can show its
  // own "Sharing…" state without a separate piece of state.
  const shareFolderMutation = useMutation({
    mutationFn: async (folderPath: string) => {
      const result = await shareFolder(folderPath)
      // ⚠⚠ Naming the step is not cosmetic: 'share' and 'mount' both mean a shared realm was
      // already created on the relay, so the user's next attempt is not starting from nothing.
      // The old bare null said only "didn't work" and left that partial state invisible.
      if (!result.ok) {
        const where = result.error.kind === 'realm' ? '' : ' (a shared realm was already created)'
        throw new Error(`Couldn't share “${basename(folderPath)}”${where}.`)
      }
      return result.item
    },
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.sharedFolders() })
      setManaging({ realmId: result.sharedRealmId, name: result.name })
    },
  })

  async function onDeleteMany(paths: readonly string[]): Promise<string | null> {
    const results = await Promise.all(paths.map((p) => deleteEntry(p)))
    const failures = results.filter((ok) => !ok).length
    if (selectedPath && paths.includes(selectedPath)) setSelectedPath(null)
    setChecked(new Set())
    refresh()
    if (failures > 0) return `${failures} item${failures === 1 ? '' : 's'} couldn't be deleted.`
    return null
  }

  // Managing a freshly-shared folder takes over the browser.
  if (managing) {
    return (
      <ManageAccessView
        realmId={managing.realmId}
        folderName={managing.name}
        onBack={() => setManaging(null)}
      />
    )
  }

  return (
    <div className="space-y-3">
      {shareFolderMutation.isError && (
        <p className="rounded-lg bg-danger/10 px-4 py-2 text-sm text-danger">
          {shareFolderMutation.error.message}
        </p>
      )}
      <div className="flex items-center justify-between gap-4">
        <Breadcrumbs path={path} onNavigate={goTo} />
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setAction({ type: 'newFolder' })}
            className="rounded-lg border border-line px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3"
          >
            New folder
          </button>
          <UploadButton parentPath={path} onUploaded={refresh} />
        </div>
      </div>

      <div className="mb-3 flex items-center justify-end gap-2">
        <label className="sr-only" htmlFor="sort-key">Sort by</label>
        <select
          id="sort-key"
          value={prefs.key}
          onChange={(e) => {
            const key = e.target.value as SortKey
            // ⚠ A freshly picked key takes ITS OWN default direction, not the previous key's:
            // "sort by date" means newest first, while "sort by name" means A→Z. Carrying the
            // old direction over is how you get "sort by date" showing the oldest file.
            setPrefs({ ...prefs, key, descending: defaultDescending(key) })
          }}
          className="rounded-lg border border-line bg-surface px-2 py-1.5 text-sm text-text"
        >
          <option value="name">Name</option>
          <option value="modified">Last modified</option>
          <option value="created">Created</option>
          <option value="size">Size</option>
        </select>
        <button
          type="button"
          aria-label={prefs.descending ? 'Sort ascending' : 'Sort descending'}
          onClick={() => setPrefs({ ...prefs, descending: !prefs.descending })}
          className="rounded-lg border border-line px-2 py-1.5 text-sm text-text hover:bg-surface-3"
        >
          {prefs.descending ? '↓' : '↑'}
        </button>
        <button
          type="button"
          aria-label={prefs.layout === 'list' ? 'Switch to grid' : 'Switch to list'}
          onClick={() => setPrefs({ ...prefs, layout: prefs.layout === 'list' ? 'grid' : 'list' })}
          className="rounded-lg border border-line px-2 py-1.5 text-sm text-text hover:bg-surface-3"
        >
          {prefs.layout === 'list' ? '▦' : '☰'}
        </button>
      </div>

      {checked.size > 0 && (
        <div className="flex items-center justify-between rounded-lg bg-primary px-4 py-2 text-sm text-on-primary">
          <span>{checked.size} selected</span>
          <div className="flex items-center gap-4">
            <button
              type="button"
              onClick={() => setMover({ sources: [...checked] })}
              className="font-medium hover:underline"
            >
              Move
            </button>
            <button
              type="button"
              onClick={() => setAction({ type: 'deleteMany', paths: [...checked] })}
              className="font-medium hover:underline"
            >
              Delete
            </button>
            <button
              type="button"
              onClick={() => setChecked(new Set())}
              className="text-on-primary/70 hover:text-on-primary"
            >
              Clear
            </button>
          </div>
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-line">
        {isPending ? (
          <Message>Loading…</Message>
        ) : isError ? (
          <Message tone="error">{String(error)}</Message>
        ) : entries.length === 0 ? (
          <Message>This folder is empty.</Message>
        ) : (
          <ul
            className={
              // ⚠ Grid changes the CONTAINER only — the row keeps its own markup, so selection,
              // the overflow menu and every action behave identically in both layouts. A separate
              // grid row component would be two surfaces to keep in step.
              prefs.layout === 'grid'
                ? 'grid grid-cols-[repeat(auto-fill,minmax(13rem,1fr))] gap-px bg-line'
                : 'divide-y divide-line'
            }
          >
            {entries.map((entry) => (
              <FileRow
                key={entry.path}
                entry={entry}
                checked={checked.has(entry.path)}
                onToggle={() => toggleChecked(entry.path)}
                onOpen={
                  isFolder(entry)
                    ? () => goTo(entry.path)
                    : () => opener.open(entry.path)
                }
                opening={opener.opening === entry.path}
                onDetails={isFolder(entry) ? undefined : () => setSelectedPath(entry.path)}
                onRename={() => setAction({ type: 'rename', entry })}
                onMove={() => setMover({ sources: [entry.path] })}
                onDelete={() => setAction({ type: 'delete', entry })}
                onShare={
                  isFolder(entry)
                    ? () => shareFolderMutation.mutate(entry.path)
                    : () => setSharing(entry.path)
                }
                shareLabel={isFolder(entry) ? 'Share folder' : 'Share'}
                sharing={
                  shareFolderMutation.isPending && shareFolderMutation.variables === entry.path
                }
              />
            ))}
          </ul>
        )}
      </div>

      {opener.overlay}

      {selectedPath && (
        <FileDetails path={selectedPath} onClose={() => setSelectedPath(null)} />
      )}

      {sharing && <ShareDialog path={sharing} onClose={() => setSharing(null)} />}

      {mover && (
        <MovePicker
          sourcePaths={mover.sources}
          onMove={(dest) => onMoveTo(mover.sources, dest)}
          onClose={() => setMover(null)}
        />
      )}

      {action?.type === 'newFolder' && (
        <PromptDialog
          title="New folder"
          label="Folder name"
          submitLabel="Create"
          onSubmit={onCreateFolder}
          onClose={() => setAction(null)}
        />
      )}
      {action?.type === 'rename' && (
        <PromptDialog
          title="Rename"
          label="New name"
          initialValue={basename(action.entry.path)}
          submitLabel="Rename"
          onSubmit={(name) => onRename(action.entry, name)}
          onClose={() => setAction(null)}
        />
      )}
      {action?.type === 'delete' && (
        <ConfirmDialog
          title={isFolder(action.entry) ? 'Delete folder' : 'Delete file'}
          confirmLabel="Delete"
          message={
            <>
              Delete “<strong>{basename(action.entry.path)}</strong>”?
              {isFolder(action.entry) && ' This removes the folder and everything in it.'}
            </>
          }
          onConfirm={() => onDelete(action.entry)}
          onClose={() => setAction(null)}
        />
      )}
      {action?.type === 'deleteMany' && (
        <ConfirmDialog
          title="Delete items"
          confirmLabel="Delete"
          message={
            <>
              Delete <strong>{action.paths.length}</strong> selected item
              {action.paths.length === 1 ? '' : 's'}? Folders are removed with their contents.
            </>
          }
          onConfirm={() => onDeleteMany(action.paths)}
          onClose={() => setAction(null)}
        />
      )}
    </div>
  )
}

// Folders before files, each group alphabetical by name (case-insensitive).
// ⚠⚠ Folders first, ALWAYS — the chosen key only orders within each group. This is the same
// rule native applies, and it is why Drive's listing cannot simply be paged: `foldersFirst`
// reorders the whole list, so a page is a slice of the wrong thing. That is an open problem on
// native too (`core-ordered-window-fusion.md` — "Outstanding: Drive's foldersFirst two-source
// paging"), which is why W5 sorts a whole folder rather than paging one.
function sortEntries(entries: readonly FsEntry[], prefs: ListingPrefs): FsEntry[] {
  const dir = prefs.descending ? -1 : 1
  return [...entries].sort((a, b) => {
    const folderDelta = Number(isFolder(b)) - Number(isFolder(a))
    if (folderDelta !== 0) return folderDelta
    switch (prefs.key) {
      case 'modified':
        return (a.lastModified - b.lastModified) * dir
      case 'created':
        return (a.createdAt - b.createdAt) * dir
      case 'size':
        // ⚠ Folders have no meaningful size (always 0), so within the folder group this falls
        // through to name — otherwise every folder ties and the order looks random.
        if (isFolder(a) && isFolder(b)) break
        return (a.size - b.size) * dir
      case 'name':
        break
    }
    return (
      basename(a.path).localeCompare(basename(b.path), undefined, { sensitivity: 'base' }) * dir
    )
  })
}

function FileRow({
  entry,
  checked,
  onToggle,
  onOpen,
  opening = false,
  onDetails,
  onRename,
  onMove,
  onDelete,
  onShare,
  shareLabel = 'Share',
  sharing = false,
}: {
  entry: FsEntry
  checked: boolean
  onToggle: () => void
  onOpen: () => void
  opening?: boolean
  onDetails?: () => void
  onRename: () => void
  onMove: () => void
  onDelete: () => void
  onShare?: () => void
  shareLabel?: string
  sharing?: boolean
}) {
  const folder = isFolder(entry)
  const name = basename(entry.path)
  const meta = folder
    ? `${entry.itemCount} item${entry.itemCount === 1 ? '' : 's'}`
    : [formatBytes(entry.size), formatDate(entry.lastModified)].filter(Boolean).join(' · ')
  const status = sharing ? 'Sharing…' : opening ? 'Opening…' : meta

  return (
    <li className="flex items-center gap-1 pr-2 hover:bg-surface">
      <input
        type="checkbox"
        aria-label={`Select ${name}`}
        checked={checked}
        onChange={onToggle}
        className="ml-3 size-4 rounded border-line"
      />
      <button
        type="button"
        onClick={onOpen}
        className="flex min-w-0 flex-1 items-center gap-3 px-3 py-3 text-left"
      >
        {folder ? <FolderGlyph /> : <FileThumbnail path={entry.path} name={name} />}
        <OfflineDot path={entry.path} isFolder={folder} />
        <span className="min-w-0 flex-1 truncate text-sm text-text">{name}</span>
        <span className="shrink-0 text-xs text-text-3">{status}</span>
      </button>
      <RowMenu
        name={name}
        onDetails={onDetails}
        onRename={onRename}
        onMove={onMove}
        onDelete={onDelete}
        onShare={onShare}
        shareLabel={shareLabel}
      />
    </li>
  )
}

function RowMenu({
  name,
  onDetails,
  onRename,
  onMove,
  onDelete,
  onShare,
  shareLabel = 'Share',
}: {
  name: string
  onDetails?: () => void
  onRename: () => void
  onMove: () => void
  onDelete: () => void
  onShare?: () => void
  shareLabel?: string
}) {
  return (
    <Menu
      trigger={
        <button
          type="button"
          aria-label={`Actions for ${name}`}
          className="rounded p-1.5 text-text-3 hover:bg-surface-3 hover:text-text-2"
        >
          <DotsGlyph />
        </button>
      }
    >
      {onShare && <MenuItem onSelect={onShare}>{shareLabel}</MenuItem>}
      {onDetails && <MenuItem onSelect={onDetails}>Details</MenuItem>}
      <MenuItem onSelect={onRename}>Rename</MenuItem>
      <MenuItem onSelect={onMove}>Move</MenuItem>
      <MenuItem danger onSelect={onDelete}>
        Delete
      </MenuItem>
    </Menu>
  )
}

// A folder pick arrives as a FLAT list; the tree exists only in `webkitRelativePath`
// ("photos/2024/a.jpg"). A plain file pick leaves it empty, so the name is the whole path.
function relativePathOf(file: File): string {
  const relative = (file as File & { webkitRelativePath?: string }).webkitRelativePath
  return relative && relative.length > 0 ? relative : file.name
}

function UploadButton({
  parentPath,
  onUploaded,
}: {
  parentPath: string
  onUploaded: () => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const folderInputRef = useRef<HTMLInputElement>(null)
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null)

  const uploadMutation = useMutation({
    mutationFn: async (files: File[]) => {
      // W7. Every directory the pick implies, each created once and parents before children —
      // `uploadFile` puts a file in an existing folder, it does not make the path on the way.
      const directories = new Set<string>()
      for (const file of files) {
        const relative = relativePathOf(file)
        const cut = relative.lastIndexOf('/')
        if (cut < 0) continue
        let accumulated = ''
        for (const segment of relative.slice(0, cut).split('/')) {
          accumulated = accumulated ? `${accumulated}/${segment}` : segment
          directories.add(accumulated)
        }
      }
      // Parents before children, stated as depth because that is the actual requirement.
      // (Sorting by string length would also hold — a parent is always a strict prefix, so
      // always shorter — but that is a coincidence of the encoding, not the rule.)
      const ordered = [...directories].sort(
        (a, b) => a.split('/').length - b.split('/').length,
      )
      for (const directory of ordered) {
        // ⚠ Return value ignored deliberately — `createFolder` answers false both for a real
        // failure and for "it already exists" (documented idempotent), so it cannot tell us
        // anything here. A directory that genuinely could not be created surfaces as the
        // upload into it failing, which is reported.
        await createFolder(joinPath(parentPath, directory))
      }

      setProgress({ done: 0, total: files.length })
      for (const [index, file] of files.entries()) {
        const relative = relativePathOf(file)
        const cut = relative.lastIndexOf('/')
        const target = cut < 0 ? parentPath : joinPath(parentPath, relative.slice(0, cut))
        // ⚠ Still one whole file in memory at a time: `uploadFile` takes a complete byte
        // array and there is no chunked upload op to bridge. W7 fixed the download side only.
        const data = new Int8Array(await file.arrayBuffer())
        const ok = await uploadFile(target, file.name, data)
        if (!ok) throw new Error(`Couldn't upload “${file.name}”`)
        setProgress({ done: index + 1, total: files.length })
      }
    },
    onSuccess: () => onUploaded(),
    onSettled: () => setProgress(null),
  })

  function onPick(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? [])
    event.target.value = '' // allow re-picking the same file
    if (files.length > 0) uploadMutation.mutate(files)
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <button
        type="button"
        disabled={uploadMutation.isPending}
        onClick={() => inputRef.current?.click()}
        className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90 disabled:opacity-50"
      >
        {uploadMutation.isPending
          ? progress && progress.total > 1
            ? `Uploading ${progress.done}/${progress.total}…`
            : 'Uploading…'
          : 'Upload'}
      </button>
      <button
        type="button"
        disabled={uploadMutation.isPending}
        onClick={() => folderInputRef.current?.click()}
        className="text-xs text-text-2 underline underline-offset-2 hover:text-text disabled:opacity-50"
      >
        Upload folder
      </button>
      <input ref={inputRef} type="file" multiple className="hidden" onChange={onPick} />
      {/* `webkitdirectory` is the only way to pick a directory from an <input>; React does not
          know the attribute, hence the cast. Supported in every browser Drive targets. */}
      <input
        ref={folderInputRef}
        type="file"
        multiple
        className="hidden"
        onChange={onPick}
        {...({ webkitdirectory: '' } as Record<string, string>)}
      />
      {uploadMutation.isError && (
        <p className="text-xs text-danger">{uploadMutation.error.message}</p>
      )}
    </div>
  )
}

function Message({
  children,
  tone = 'muted',
}: {
  children: React.ReactNode
  tone?: 'muted' | 'error'
}) {
  return (
    <p className={`px-4 py-8 text-center text-sm ${tone === 'error' ? 'text-danger' : 'text-text-3'}`}>
      {children}
    </p>
  )
}

function FolderGlyph() {
  return (
    <svg className="size-5 shrink-0 text-warning" viewBox="0 0 20 20" fill="currentColor" aria-hidden>
      <path d="M3 5a2 2 0 0 1 2-2h3.2a2 2 0 0 1 1.6.8l.6.8H15a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5Z" />
    </svg>
  )
}

function DotsGlyph() {
  return (
    <svg className="size-5" viewBox="0 0 20 20" fill="currentColor" aria-hidden>
      <circle cx="10" cy="4" r="1.5" />
      <circle cx="10" cy="10" r="1.5" />
      <circle cx="10" cy="16" r="1.5" />
    </svg>
  )
}
