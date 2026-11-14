import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { addToAlbum, removeFromAlbum, setFavourite } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { runBatch, type BatchOutcome, type SelectionState } from '../lib/selection'
import { outcomeMessage } from '../lib/errors'
import { AlbumPicker } from './AlbumPicker'
import { Button } from './ui'

// The selection bar over a grid (web-apps-layout.md §3.3 — the native multi-select sheet): shown
// whenever anything is selected, with the count, a clear (✕), and for the picked photographs "Add to
// album", "Add to favourites" and — inside an album — "Remove from album". There is no Select
// mode: a click selects (§3.2). A batch runs every write; the count and the first failure (by
// `kind`) are reported, never swallowed.
export function SelectionBar({ selection, albumId }: { selection: SelectionState; albumId?: string }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [picking, setPicking] = useState(false)
  const [result, setResult] = useState<BatchOutcome | null>(null)
  const [busy, setBusy] = useState(false)

  // A cleared selection drops its last report too — adjusted during render, React's pattern.
  if (selection.size === 0) {
    if (result) setResult(null)
    return null
  }

  // ⚠ No content id = cannot be filed: those are skipped up front rather than failing one by one.
  const fileable = selection.photos.filter((p) => p.contentId !== '')

  async function run(batch: () => Promise<BatchOutcome>) {
    setBusy(true)
    const outcome = await batch()
    setBusy(false)
    setResult(outcome.first ? outcome : null)
    if (!outcome.first) selection.clear()
    return outcome
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button size="sm" variant="ghost" aria-label={t('select.clear')} title={t('select.clear')} onClick={selection.clear}>
        ✕
      </Button>
      <span className="text-sm text-text-2">{t('select.count', { count: selection.size })}</span>
      <Button size="sm" disabled={busy || fileable.length === 0} onClick={() => setPicking(true)}>
        {t('select.addToAlbum')}
      </Button>
      <Button
        size="sm"
        variant="outline"
        disabled={busy || fileable.length === 0}
        onClick={() => void run(() => runBatch(fileable, (p) => setFavourite(p.contentId, true)))}
      >
        {t('select.favourite')}
      </Button>
      {albumId && (
        <Button
          size="sm"
          variant="outline"
          disabled={busy || fileable.length === 0}
          onClick={async () => {
            await run(() => runBatch(fileable, (p) => removeFromAlbum(albumId, p.contentId)))
            await queryClient.invalidateQueries({ queryKey: queryKeys.albumPhotos(albumId) })
          }}
        >
          {t('select.removeFromAlbum')}
        </Button>
      )}
      {result?.first && (
        <p role="alert" className="w-full text-sm text-danger">
          {t('select.partial', { done: result.done, total: result.total })} {outcomeMessage(t, result.first)}
        </p>
      )}
      {picking && (
        <AlbumPicker
          onClose={() => setPicking(false)}
          onPick={async (id) => {
            await run(() => runBatch(fileable, (p) => addToAlbum(id, p.path)))
            await queryClient.invalidateQueries({ queryKey: queryKeys.albumPhotos(id) })
            // The picker closes either way; the bar reports a partial batch.
            return null
          }}
        />
      )}
    </div>
  )
}
