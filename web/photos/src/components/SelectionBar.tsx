import { useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { addToAlbum, removeFromAlbum, setFavourite, trashPhoto } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { runBatch, type BatchOutcome, type SelectionState } from '../lib/selection'
import { AlbumPicker } from './AlbumPicker'
import { AlbumAddIcon, AlbumRemoveIcon, CloseIcon, HeartIcon, TrashIcon } from './icons'

// The selection's actions, inline in the grid's toolbar (web-apps-layout.md §4.4 "Selection model
// revised" — iCloud Photos' shape): a quiet "n selected", Add to album, Add to favourites, Remove
// from album inside an album, Move to trash, and a ✕ that clears. ⚠ Not a mode and not a bar: it
// sits in a toolbar row whose height is reserved, so it appearing never shifts the grid.
// A batch runs every write; a partial one is reported to [onReport] (rendered by the browser, by
// `kind`), never swallowed.
export function SelectionBar({
  selection,
  albumId,
  onReport,
  onTrashed,
}: {
  selection: SelectionState
  albumId?: string
  onReport: (outcome: BatchOutcome | null) => void
  onTrashed?: () => void
}) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [picking, setPicking] = useState(false)
  const [busy, setBusy] = useState(false)

  if (selection.size === 0) return null

  // ⚠ No content id = cannot be filed: those are skipped up front rather than failing one by one.
  const fileable = selection.photos.filter((p) => p.contentId !== '')

  async function run(batch: () => Promise<BatchOutcome>) {
    setBusy(true)
    const outcome = await batch()
    setBusy(false)
    onReport(outcome.first ? outcome : null)
    if (!outcome.first) selection.clear()
    return outcome
  }

  async function trashAll() {
    const photos = selection.photos
    // Each photograph that went leaves the selection at once; one that failed stays selected.
    const outcome = await run(() =>
      runBatch(photos, async (p) => {
        const result = await trashPhoto(p.path)
        if (result.ok) selection.drop(p)
        return result
      }),
    )
    if (outcome.done > 0) onTrashed?.()
  }

  return (
    <div role="group" aria-label={t('select.actions')} className="flex flex-none items-center gap-1 whitespace-nowrap">
      <span className="mr-1 text-sm text-text-2">{t('select.count', { count: selection.size })}</span>
      <IconButton label={t('select.addToAlbum')} disabled={busy || fileable.length === 0} onClick={() => setPicking(true)}>
        <AlbumAddIcon />
      </IconButton>
      <IconButton
        label={t('select.favourite')}
        disabled={busy || fileable.length === 0}
        onClick={() => void run(() => runBatch(fileable, (p) => setFavourite(p.contentId, true)))}
      >
        <HeartIcon />
      </IconButton>
      {albumId && (
        <IconButton
          label={t('select.removeFromAlbum')}
          disabled={busy || fileable.length === 0}
          onClick={async () => {
            await run(() => runBatch(fileable, (p) => removeFromAlbum(albumId, p.contentId)))
            await queryClient.invalidateQueries({ queryKey: queryKeys.albumPhotos(albumId) })
          }}
        >
          <AlbumRemoveIcon />
        </IconButton>
      )}
      <IconButton label={t('select.trash')} danger disabled={busy} onClick={() => void trashAll()}>
        <TrashIcon />
      </IconButton>
      <IconButton label={t('select.clear')} onClick={selection.clear}>
        <CloseIcon />
      </IconButton>
      {picking && (
        <AlbumPicker
          onClose={() => setPicking(false)}
          onPick={async (id) => {
            await run(() => runBatch(fileable, (p) => addToAlbum(id, p.path)))
            await queryClient.invalidateQueries({ queryKey: queryKeys.albumPhotos(id) })
            // The picker closes either way; the browser reports a partial batch.
            return null
          }}
        />
      )}
    </div>
  )
}

// A toolbar glyph button: its name is its aria-label (and the hover title), never the glyph.
export function IconButton({
  label,
  onClick,
  disabled,
  danger,
  pressed,
  children,
}: {
  label: string
  onClick: () => void
  disabled?: boolean
  danger?: boolean
  pressed?: boolean
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      disabled={disabled}
      onClick={onClick}
      className={`flex-none rounded-lg p-1.5 text-text-2 hover:bg-surface-3 disabled:opacity-40 disabled:hover:bg-transparent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${
        danger ? 'hover:text-danger' : 'hover:text-text'
      } ${pressed ? 'bg-surface-3 text-text' : ''}`}
    >
      {children}
    </button>
  )
}
