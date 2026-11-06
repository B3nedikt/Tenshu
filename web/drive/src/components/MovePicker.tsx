import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listFiles } from '../lib/proxy'
import { basename, isFolder, parentDir } from '../lib/paths'
import { queryKeys } from '../lib/queryKeys'
import { Breadcrumbs } from './Breadcrumbs'
import { Dialog } from './Dialog'

// Destination-folder chooser for Move. Navigate the folder tree (folders only),
// then "Move here" drops the source(s) into the folder currently shown. A
// source folder is hidden from the list (you can't move it into itself), and
// "Move here" is disabled when every source already lives in the shown folder.
export function MovePicker({
  sourcePaths,
  onMove,
  onClose,
}: {
  sourcePaths: readonly string[]
  onMove: (destFolder: string) => Promise<string | null>
  onClose: () => void
}) {
  const [pickerPath, setPickerPath] = useState('/')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const { data, isPending } = useQuery({
    queryKey: queryKeys.files(pickerPath),
    queryFn: () => listFiles(pickerPath),
  })

  const sources = new Set(sourcePaths)
  const folders = (data ?? [])
    .filter((entry) => isFolder(entry) && !sources.has(entry.path))
    .sort((a, b) =>
      basename(a.path).localeCompare(basename(b.path), undefined, { sensitivity: 'base' }),
    )

  const allAlreadyHere = sourcePaths.every((p) => parentDir(p) === pickerPath)
  const count = sourcePaths.length

  async function move() {
    setBusy(true)
    setError(null)
    const err = await onMove(pickerPath)
    if (err) {
      setError(err)
      setBusy(false)
    } else {
      onClose()
    }
  }

  return (
    <Dialog title={`Move ${count} item${count === 1 ? '' : 's'}`} onClose={onClose}>
      <Breadcrumbs path={pickerPath} onNavigate={setPickerPath} />
      <div className="mt-3 max-h-64 min-h-32 overflow-y-auto rounded-lg border border-line">
        {isPending ? (
          <p className="px-4 py-6 text-center text-sm text-text-3">Loading…</p>
        ) : folders.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-text-3">No subfolders here.</p>
        ) : (
          <ul className="divide-y divide-line">
            {folders.map((entry) => (
              <li key={entry.path}>
                <button
                  type="button"
                  onClick={() => setPickerPath(entry.path)}
                  className="flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm text-text hover:bg-surface"
                >
                  <FolderGlyph />
                  <span className="min-w-0 flex-1 truncate">{basename(entry.path)}</span>
                  <span aria-hidden className="text-text-3">›</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      {error && <p className="mt-3 text-sm text-danger">{error}</p>}
      <div className="mt-4 flex justify-end gap-2">
        <button
          type="button"
          onClick={onClose}
          className="rounded-lg px-3 py-1.5 text-sm font-medium text-text-2 hover:bg-surface-3"
        >
          Cancel
        </button>
        <button
          type="button"
          disabled={busy || allAlreadyHere}
          onClick={move}
          className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90 disabled:opacity-50"
        >
          {busy ? 'Moving…' : 'Move here'}
        </button>
      </div>
    </Dialog>
  )
}

function FolderGlyph() {
  return (
    <svg className="size-5 shrink-0 text-warning" viewBox="0 0 20 20" fill="currentColor" aria-hidden>
      <path d="M3 5a2 2 0 0 1 2-2h3.2a2 2 0 0 1 1.6.8l.6.8H15a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5Z" />
    </svg>
  )
}
