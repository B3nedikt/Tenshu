import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { addToAlbum, removeFromAlbum, setFavourite } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { runBatch, type BatchOutcome, type SelectionState } from '../lib/selection'
import { outcomeMessage } from '../lib/errors'
import { AlbumPicker } from './AlbumPicker'
import { Button } from './ui'

// The multi-select toolbar over a grid: Select / Cancel, and for the picked photographs "Add to
// album", "Add to favourites" and — inside an album — "Remove from album". A batch runs every
// write; the count and the first failure (by `kind`) are reported, never swallowed.
export function SelectionBar({ selection, albumId }: { selection: SelectionState; albumId?: string }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [picking, setPicking] = useState(false)
  const [result, setResult] = useState<BatchOutcome | null>(null)
  const [busy, setBusy] = useState(false)

  if (!selection.active) {
    return (
      <Button
        size="sm"
        variant="outline"
        onClick={() => {
          setResult(null)
          selection.start()
        }}
      >
        {t('select.start')}
      </Button>
    )
  }

  // ⚠ No content id = cannot be filed: those are skipped up front rather than failing one by one.
  const fileable = selection.photos.filter((p) => p.contentId !== '')

  async function run(batch: () => Promise<BatchOutcome>) {
    setBusy(true)
    const outcome = await batch()
    setBusy(false)
    setResult(outcome)
    if (!outcome.first) selection.stop()
    return outcome
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-sm text-text-2">{t('select.count', { count: selection.photos.length })}</span>
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
      <Button size="sm" variant="ghost" onClick={selection.stop}>
        {t('common.cancel')}
      </Button>
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
