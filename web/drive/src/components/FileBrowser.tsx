import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ContextMenu, Menu, MenuItem, type MenuEntry } from '@tenshu/web-tokens/ui'
import type { File as FsEntry } from '@tenshu/sdk/files'
import { deleteEntry, listFiles, move, rename, shareFolder } from '../lib/proxy'
import { basename, formatBytes, formatDate, isFolder } from '../lib/paths'
import { defaultDescending, loadPrefs, savePrefs, type ListingPrefs, type SortKey } from '../lib/listingPrefs'
import { queryKeys } from '../lib/queryKeys'
import { downloadFile } from '../lib/downloadFile'
import { Breadcrumbs } from './Breadcrumbs'
import { ConfirmDialog, PromptDialog } from './Dialog'
import { MovePicker } from './MovePicker'
import { ShareDialog } from './ShareDialog'
import { ManageAccessView } from './ManageAccessView'
import { FileThumbnail } from './FileThumbnail'
import { FileViewer } from './FileViewer'
import { OfflineDot } from './OfflineDot'

type PendingAction =
  | { type: 'rename'; entry: FsEntry }
  | { type: 'delete'; entry: FsEntry }
  | { type: 'deleteMany'; paths: string[] }
  | null

export interface FileBrowserProps {
  // The open folder, owned by the caller (App keeps it in the URL fragment, web-apps-layout.md
  // §3.4). Omitted, the browser keeps its own, starting at [initialPath].
  readonly path?: string
  readonly onPathChange?: (path: string) => void
  readonly initialPath?: string
  // The file open in the viewer, owned by the caller (the URL, D6: opening is a history entry,
  // moving within the viewer is not). Omitted, the browser keeps its own.
  readonly viewing?: string | null
  readonly onView?: (path: string | null, how: 'open' | 'move' | 'close') => void
  // The details pane (D2): its open state and content live with the caller (App's AppLayout pane).
  readonly paneOpen?: boolean
  readonly onTogglePane?: () => void
  // The one selected entry the pane describes, or null (nothing, or several, selected).
  readonly onFocusChange?: (entry: FsEntry | null) => void
  // The pane content for an entry, reused beside the viewer.
  readonly details?: (entry: FsEntry) => ReactNode
}

// The Drive file browser, with desktop pointer rules (web-apps-layout.md §3.2/§3.3): a click
// selects (and fills the details pane), a double-click / Enter / Space opens — a folder in place,
// a file in the viewer — ⌘/Ctrl- and Shift-click extend the selection, and right-click or the
// row's ⋯ opens the item menu (the native file-actions sheet). Several selected items get the
// selection bar (the multi-select sheet). Upload and New folder are the app's "+ New" menu.
// All reads/writes go through the apex bridge; mutations invalidate the current folder's listing.
export function FileBrowser({
  path: controlledPath,
  onPathChange,
  initialPath = '/',
  viewing: controlledViewing,
  onView,
  paneOpen = false,
  onTogglePane,
  onFocusChange,
  details,
}: FileBrowserProps = {}) {
  // Sort + layout, remembered on this browser only — see listingPrefs for why not the
  // PreferenceService.
  const [prefs, setPrefsState] = useState<ListingPrefs>(loadPrefs)
  const setPrefs = (next: ListingPrefs) => {
    setPrefsState(next)
    savePrefs(next)
  }
  const [ownPath, setOwnPath] = useState(initialPath)
  const path = controlledPath ?? ownPath
  const [ownViewing, setOwnViewing] = useState<string | null>(null)
  const viewing = controlledViewing === undefined ? ownViewing : controlledViewing
  const view = (target: string | null, how: 'open' | 'move' | 'close') => {
    if (onView) onView(target, how)
    else setOwnViewing(target)
  }

  // The selection, in this folder. `anchor` is where a Shift-click range starts; `bulk` is whether
  // the selection was made as a set (a checkbox, ⌘ or Shift) — only then does the selection bar
  // show for a single item, so a plain click does not flash a bar.
  const [picked, setSelected] = useState<ReadonlySet<string>>(new Set())
  const [anchor, setAnchor] = useState<string | null>(null)
  const [bulk, setBulk] = useState(false)
  // The selection belongs to one folder. A move that did not come through goTo (Back, a link)
  // clears it too — adjusted during render, React's pattern for state that follows a prop.
  const [selectedIn, setSelectedIn] = useState(path)
  if (selectedIn !== path) {
    setSelectedIn(path)
    setSelected(new Set())
    setAnchor(null)
    setBulk(false)
  }
  const [action, setAction] = useState<PendingAction>(null)
  const [mover, setMover] = useState<{ sources: string[] } | null>(null)
  const [sharing, setSharing] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  // MB-4: the realm being managed right after a folder share completes.
  const [managing, setManaging] = useState<{ realmId: string; name: string } | null>(null)
  const queryClient = useQueryClient()

  const { data, isPending, error, isError } = useQuery({
    queryKey: queryKeys.files(path),
    queryFn: () => listFiles(path),
  })

  const entries = data ? sortEntries(data, prefs) : []
  const files = entries.filter((entry) => !isFolder(entry))
  // The selection as it stands in the listing: an entry that vanished (deleted elsewhere, a
  // refetch) drops out of the count, the bar, the pane and every bulk action.
  const selected: ReadonlySet<string> = new Set(entries.filter((entry) => picked.has(entry.path)).map((e) => e.path))
  const focused = selected.size === 1 ? (entries.find((entry) => selected.has(entry.path)) ?? null) : null

  // The pane describes the one selected entry. Reported when what it shows could change (path,
  // item count, modification) — not for every refetch's fresh object.
  const focusKey = focused ? `${focused.path}\u0000${focused.itemCount}\u0000${focused.lastModified}` : null
  useEffect(() => {
    onFocusChange?.(focused)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed by what the pane shows (above)
  }, [focusKey, onFocusChange])

  // A viewer whose file is not in the (loaded) folder — a deep link to a deleted file, or one
  // deleted elsewhere while open — closes rather than showing a counterless, inert viewer.
  const viewingMissing = !!data && viewing !== null && !files.some((entry) => entry.path === viewing)
  // Controlled, the owner's URL is moved off it; either way nothing is shown for it meanwhile.
  useEffect(() => {
    if (viewingMissing) onView?.(null, 'close')
    // eslint-disable-next-line react-hooks/exhaustive-deps -- fires on the transition only
  }, [viewingMissing])

  // Closing the viewer puts focus back on the row of the file it last showed, so the keyboard keeps
  // its place (and Space can open it again).
  const lastViewed = useRef<string | null>(null)
  const listRef = useRef<HTMLUListElement>(null)
  useEffect(() => {
    if (viewing) {
      lastViewed.current = viewing
      return
    }
    const path = lastViewed.current
    lastViewed.current = null
    if (!path) return
    const row = [...(listRef.current?.querySelectorAll<HTMLElement>('[data-row-main]') ?? [])].find(
      (el) => el.dataset.path === path,
    )
    row?.focus()
  }, [viewing])

  function refresh() {
    queryClient.invalidateQueries({ queryKey: queryKeys.files(path) })
  }

  function goTo(target: string) {
    if (onPathChange) onPathChange(target)
    else setOwnPath(target)
  }

  function open(entry: FsEntry) {
    if (isFolder(entry)) goTo(entry.path)
    else view(entry.path, 'open')
  }

  function clearSelection() {
    setSelected(new Set())
    setAnchor(null)
    setBulk(false)
  }

  // Click: select just this. ⌘/Ctrl: toggle it. Shift: the range from the anchor, in display order.
  function onRowClick(entry: FsEntry, event: MouseEvent) {
    const from = anchor ? entries.findIndex((e) => e.path === anchor) : -1
    // A Shift-click from an anchor that is no longer listed is a plain click.
    if (event.shiftKey && from >= 0) {
      const to = entries.findIndex((e) => e.path === entry.path)
      const [lo, hi] = from < to ? [from, to] : [to, from]
      setSelected(new Set(entries.slice(lo, hi + 1).map((e) => e.path)))
      setBulk(true)
      return
    }
    if (event.metaKey || event.ctrlKey) {
      toggle(entry.path)
      return
    }
    setSelected(new Set([entry.path]))
    setAnchor(entry.path)
    setBulk(false)
  }

  function toggle(target: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(target)) next.delete(target)
      else next.add(target)
      return next
    })
    setAnchor(target)
    setBulk(true)
  }

  // Each handler resolves null on success (the dialog/picker closes) or an error message to show.
  async function onRename(entry: FsEntry, newName: string): Promise<string | null> {
    if (newName === basename(entry.path)) return null
    const ok = await rename(entry.path, newName)
    if (!ok) return `Couldn't rename — “${newName}” may already exist.`
    clearSelection()
    refresh()
    return null
  }

  async function onDelete(entry: FsEntry): Promise<string | null> {
    const ok = await deleteEntry(entry.path)
    if (!ok) return `Couldn't delete “${basename(entry.path)}”.`
    clearSelection()
    if (viewing === entry.path) view(null, 'close')
    refresh()
    return null
  }

  async function onMoveTo(sources: readonly string[], destFolder: string): Promise<string | null> {
    const results = await Promise.all(sources.map((p) => move(p, destFolder)))
    const failures = results.filter((ok) => !ok).length
    clearSelection()
    refresh()
    if (failures > 0) return `${failures} item${failures === 1 ? '' : 's'} couldn't be moved.`
    return null
  }

  async function onDeleteMany(paths: readonly string[]): Promise<string | null> {
    const results = await Promise.all(paths.map((p) => deleteEntry(p)))
    const failures = results.filter((ok) => !ok).length
    clearSelection()
    refresh()
    if (failures > 0) return `${failures} item${failures === 1 ? '' : 's'} couldn't be deleted.`
    return null
  }

  async function download(paths: readonly string[]) {
    for (const target of paths) {
      const problem = await downloadFile(target)
      if (problem) {
        setNotice(problem)
        return
      }
    }
  }

  // MB-4: share a folder out — bootstraps a realm, populates it, self-mounts. On success, open
  // Manage access so the user can add people right away. The mutation's `variables` is the
  // in-flight folder path, so a row can show its own "Sharing…" state.
  const shareFolderMutation = useMutation({
    mutationFn: async (folderPath: string) => {
      const result = await shareFolder(folderPath)
      // ⚠⚠ Naming the step is not cosmetic: 'share' and 'mount' both mean a shared realm was
      // already created on the relay, so the user's next attempt is not starting from nothing.
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

  function share(entry: FsEntry) {
    if (isFolder(entry)) shareFolderMutation.mutate(entry.path)
    else setSharing(entry.path)
  }

  // The item menu — the native file-actions sheet (§3.3). "Details & activity" is the pane now
  // (Get info), and "Make available offline" stays hidden: the browser keeps no bytes.
  function menuFor(entry: FsEntry): MenuEntry[] {
    const folder = isFolder(entry)
    const info: MenuEntry = {
      label: 'Get info',
      shortcut: '⌘I',
      groupStart: true,
      onSelect: () => {
        setSelected(new Set([entry.path]))
        setAnchor(entry.path)
        setBulk(false)
        if (!paneOpen) onTogglePane?.()
      },
    }
    return [
      { label: 'Open', shortcut: '↵', onSelect: () => open(entry) },
      ...(folder ? [] : [{ label: 'Download', onSelect: () => void download([entry.path]) }]),
      { label: folder ? 'Share folder' : 'Share', groupStart: true, onSelect: () => share(entry) },
      { label: 'Move', groupStart: true, onSelect: () => setMover({ sources: [entry.path] }) },
      { label: 'Rename', shortcut: 'F2', onSelect: () => setAction({ type: 'rename', entry }) },
      ...(onTogglePane ? [info] : []),
      { label: 'Delete', shortcut: '⌫', danger: true, groupStart: true, onSelect: () => setAction({ type: 'delete', entry }) },
    ]
  }

  const dialogOpen = action !== null || mover !== null || sharing !== null || viewing !== null

  // Keys on the list (§3.2). On the <ul>, so the toolbar, breadcrumbs and selection bar keep
  // their own keys; and only while nothing modal is up — a dialog or the viewer owns them.
  // ⚠ Menus are portalled but React still bubbles their keys here: anything a menu (or anyone)
  // already handled is skipped, and Enter/Space/F2/⌫ act on the ROW that has focus — never on a
  // selection elsewhere, which is how "Enter on Delete" used to also open the selected folder.
  function onKeyDown(event: KeyboardEvent<HTMLUListElement>) {
    if (dialogOpen || event.defaultPrevented) return
    const target = event.target as HTMLElement
    if (target.closest('[role="menu"]')) return
    const mod = event.metaKey || event.ctrlKey
    const rowPath = (target.closest('[data-row-main]') as HTMLElement | null)?.dataset.path
    const row = rowPath ? (entries.find((entry) => entry.path === rowPath) ?? null) : null
    if (event.key === 'Escape' && selected.size > 0) {
      event.preventDefault()
      clearSelection()
    } else if ((event.key === 'Enter' || event.key === ' ') && row) {
      // Space is Quick Look (files); Enter opens either kind.
      if (event.key === ' ' && isFolder(row)) return
      event.preventDefault()
      open(row)
    } else if (event.key === 'F2' && row) {
      event.preventDefault()
      setAction({ type: 'rename', entry: row })
    } else if ((event.key === 'Delete' || event.key === 'Backspace') && row) {
      event.preventDefault()
      // The focused row is part of a set: the set. Otherwise just that row.
      if (selected.has(row.path) && selected.size > 1) setAction({ type: 'deleteMany', paths: [...selected] })
      else setAction({ type: 'delete', entry: row })
    } else if (mod && event.key.toLowerCase() === 'a' && entries.length > 0) {
      event.preventDefault()
      setSelected(new Set(entries.map((e) => e.path)))
      setBulk(true)
    } else if (mod && event.key.toLowerCase() === 'i' && onTogglePane) {
      event.preventDefault()
      onTogglePane()
    }
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

  const selectedEntries = entries.filter((entry) => selected.has(entry.path))
  const selectedBytes = selectedEntries.reduce((sum, entry) => sum + (isFolder(entry) ? 0 : entry.size), 0)
  const showBar = selected.size > 1 || (bulk && selected.size === 1)
  const viewerEntry = viewing ? (files.find((entry) => entry.path === viewing) ?? null) : null

  return (
    <div className="space-y-3">
      {shareFolderMutation.isError && (
        <p className="rounded-lg bg-danger/10 px-4 py-2 text-sm text-danger">
          {shareFolderMutation.error.message}
        </p>
      )}
      {notice && (
        <p role="alert" className="flex items-center justify-between rounded-lg bg-danger/10 px-4 py-2 text-sm text-danger">
          {notice}
          <button type="button" onClick={() => setNotice(null)} aria-label="Dismiss" className="ml-4">
            ✕
          </button>
        </p>
      )}

      {showBar ? (
        // The multi-select sheet (§3.3), replacing the toolbar while a set is selected.
        <div
          role="toolbar"
          aria-label="Selection"
          className="flex min-h-11 items-center gap-3 rounded-xl border border-primary-soft bg-primary-soft/40 px-3 text-sm text-text"
        >
          <button type="button" onClick={clearSelection} aria-label="Clear selection" className="rounded p-1 hover:bg-surface-3">
            ✕
          </button>
          <span className="font-medium">{selected.size} selected</span>
          {selectedBytes > 0 && <span className="text-text-3">· {formatBytes(selectedBytes)}</span>}
          <div className="ml-auto flex items-center gap-1">
            {selectedEntries.every((entry) => !isFolder(entry)) && (
              <BarButton onClick={() => void download([...selected])}>Download</BarButton>
            )}
            <BarButton onClick={() => setMover({ sources: [...selected] })}>Move</BarButton>
            <BarButton danger onClick={() => setAction({ type: 'deleteMany', paths: [...selected] })}>
              Delete
            </BarButton>
          </div>
        </div>
      ) : (
        <div className="flex min-h-11 items-center justify-between gap-4">
          <Breadcrumbs path={path} onNavigate={goTo} />
          <div className="flex items-center gap-2">
            <label className="sr-only" htmlFor="sort-key">Sort by</label>
            <select
              id="sort-key"
              value={prefs.key}
              onChange={(e) => {
                const key = e.target.value as SortKey
                // ⚠ A freshly picked key takes ITS OWN default direction, not the previous key's:
                // "sort by date" means newest first, while "sort by name" means A→Z.
                setPrefs({ ...prefs, key, descending: defaultDescending(key) })
              }}
              className="rounded-lg border border-line bg-surface px-2 py-1.5 text-sm text-text"
            >
              <option value="name">Name</option>
              <option value="modified">Last modified</option>
              <option value="created">Created</option>
              <option value="size">Size</option>
            </select>
            <ToolButton
              label={prefs.descending ? 'Sort ascending' : 'Sort descending'}
              onClick={() => setPrefs({ ...prefs, descending: !prefs.descending })}
            >
              {prefs.descending ? '↓' : '↑'}
            </ToolButton>
            <ToolButton
              label={prefs.layout === 'list' ? 'Switch to grid' : 'Switch to list'}
              onClick={() => setPrefs({ ...prefs, layout: prefs.layout === 'list' ? 'grid' : 'list' })}
            >
              {prefs.layout === 'list' ? '▦' : '☰'}
            </ToolButton>
            {onTogglePane && (
              <ToolButton label="Details" pressed={paneOpen} onClick={onTogglePane}>
                ⓘ
              </ToolButton>
            )}
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
            ref={listRef}
            aria-label="Files"
            onKeyDown={onKeyDown}
            className={
              // ⚠ Grid changes the CONTAINER only — the row keeps its own markup, so selection,
              // the menus and every action behave identically in both layouts.
              prefs.layout === 'grid'
                ? 'grid grid-cols-[repeat(auto-fill,minmax(13rem,1fr))] gap-px bg-line'
                : 'divide-y divide-line'
            }
          >
            {entries.map((entry) => (
              <FileRow
                key={entry.path}
                entry={entry}
                selected={selected.has(entry.path)}
                selecting={selected.size > 0}
                onClick={(event) => onRowClick(entry, event)}
                // Right-click acts on what it points at: an unselected row becomes the selection
                // first, so the pane and the menu agree (a selected set is kept, as in Finder).
                onContextSelect={() => {
                  if (selected.has(entry.path)) return
                  setSelected(new Set([entry.path]))
                  setAnchor(entry.path)
                  setBulk(false)
                }}
                onOpen={() => open(entry)}
                onToggle={() => toggle(entry.path)}
                menu={menuFor(entry)}
                sharing={shareFolderMutation.isPending && shareFolderMutation.variables === entry.path}
              />
            ))}
          </ul>
        )}
      </div>

      {viewing && !viewingMissing && (
        <FileViewer
          files={files}
          path={viewing}
          onNavigate={(target) => view(target, 'move')}
          onClose={() => view(null, 'close')}
          onShare={(target) => setSharing(target)}
          onDelete={(target) => {
            const entry = files.find((e) => e.path === target)
            if (entry) setAction({ type: 'delete', entry })
          }}
          detailsOpen={paneOpen}
          onToggleDetails={() => onTogglePane?.()}
          details={viewerEntry && details ? details(viewerEntry) : undefined}
        />
      )}

      {sharing && <ShareDialog path={sharing} onClose={() => setSharing(null)} />}

      {mover && (
        <MovePicker
          sourcePaths={mover.sources}
          onMove={(dest) => onMoveTo(mover.sources, dest)}
          onClose={() => setMover(null)}
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
  selected,
  selecting,
  onClick,
  onContextSelect,
  onOpen,
  onToggle,
  menu,
  sharing = false,
}: {
  entry: FsEntry
  selected: boolean
  // Something in the folder is selected: every row shows its select circle, not just on hover.
  selecting: boolean
  onClick: (event: MouseEvent) => void
  onContextSelect: () => void
  onOpen: () => void
  onToggle: () => void
  menu: readonly MenuEntry[]
  sharing?: boolean
}) {
  const folder = isFolder(entry)
  const name = basename(entry.path)
  const meta = folder
    ? `${entry.itemCount} item${entry.itemCount === 1 ? '' : 's'}`
    : [formatBytes(entry.size), formatDate(entry.lastModified)].filter(Boolean).join(' · ')

  return (
    <ContextMenu items={menu}>
      <li
        data-selected={selected || undefined}
        onContextMenu={onContextSelect}
        className={`group flex items-center gap-1 pr-2 ${selected ? 'bg-primary-soft/50' : 'bg-surface-2/0 hover:bg-surface-2'}`}
      >
        <input
          type="checkbox"
          aria-label={`Select ${name}`}
          checked={selected}
          onChange={onToggle}
          className={`ml-3 size-4 rounded border-line ${selecting || selected ? '' : 'opacity-0 group-hover:opacity-100 focus:opacity-100'}`}
        />
        <button
          type="button"
          onClick={onClick}
          onDoubleClick={onOpen}
          // Selection is announced by the button's pressed state: aria-selected is not valid on a
          // plain list item, and a listbox may not hold the row's other controls.
          aria-pressed={selected}
          data-row-main=""
          data-path={entry.path}
          className="flex min-w-0 flex-1 items-center gap-3 px-3 py-3 text-left select-none"
        >
          {folder ? <FolderGlyph /> : <FileThumbnail path={entry.path} name={name} />}
          <OfflineDot path={entry.path} isFolder={folder} />
          <span className="min-w-0 flex-1 truncate text-sm text-text">{name}</span>
          <span className="shrink-0 text-xs text-text-3">{sharing ? 'Sharing…' : meta}</span>
        </button>
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
          {menu.map((item) => (
            <MenuItem key={item.label} danger={item.danger} onSelect={item.onSelect}>
              {item.label}
            </MenuItem>
          ))}
        </Menu>
      </li>
    </ContextMenu>
  )
}

function ToolButton({
  label,
  onClick,
  pressed,
  children,
}: {
  label: string
  onClick: () => void
  pressed?: boolean
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={pressed}
      onClick={onClick}
      className={`rounded-lg border border-line px-2 py-1.5 text-sm text-text hover:bg-surface-3 ${pressed ? 'bg-surface-3' : ''}`}
    >
      {children}
    </button>
  )
}

function BarButton({ onClick, danger = false, children }: { onClick: () => void; danger?: boolean; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-lg px-3 py-1.5 font-medium hover:bg-surface-3 ${danger ? 'text-danger' : 'text-text'}`}
    >
      {children}
    </button>
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
