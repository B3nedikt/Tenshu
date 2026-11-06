import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Field } from '@tenshu/web-tokens/ui'
import { createAlbum } from '../lib/proxy'
import { useAlbums } from '../lib/live'
import { attempt, type Outcome } from '../lib/errors'
import { Button, Dialog, ErrorText, Loading, OutcomeText } from './ui'

// "Add to album": pick an album, or name a new one. [onPick] runs the write and resolves its
// outcome; the dialog stays open on a failure and shows it by `kind`.
export function AlbumPicker({ onPick, onClose }: { onPick: (albumId: string) => Promise<Outcome>; onClose: () => void }) {
  const { t } = useTranslation()
  const albums = useAlbums()
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)

  async function pick(albumId: string) {
    setBusy(true)
    setOutcome(null)
    const result = await onPick(albumId)
    setBusy(false)
    if (result) setOutcome(result)
    else onClose()
  }

  async function createAndPick() {
    const trimmed = name.trim()
    if (!trimmed) return
    setBusy(true)
    setOutcome(null)
    let albumId = ''
    const created = await attempt(async () => {
      const result = await createAlbum(trimmed)
      if (result.ok) albumId = result.albumId
      return result
    })
    if (created) {
      setOutcome(created)
      setBusy(false)
      return
    }
    await pick(albumId)
  }

  const data = albums.data
  return (
    <Dialog title={t('albums.addTo')} onClose={onClose}>
      <div className="space-y-4">
        {albums.isPending && <Loading />}
        {albums.error && <ErrorText error={albums.error} />}
        {data && !data.ok && <ErrorText failure={data.error} />}
        {data?.ok && data.albums.length > 0 && (
          <ul className="max-h-64 space-y-1 overflow-y-auto">
            {data.albums.map((album) => (
              <li key={album.albumId}>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void pick(album.albumId)}
                  className="w-full rounded-lg px-3 py-2 text-left text-sm text-text hover:bg-surface-3 disabled:opacity-50"
                >
                  {album.name} <span className="text-text-3">· {t('albums.count', { count: album.photoCount })}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
        <form
          className="flex items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            void createAndPick()
          }}
        >
          <div className="flex-1">
            <Field label={t('albums.newName')} value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <Button type="submit" disabled={busy || !name.trim()}>
            {t('albums.createAndAdd')}
          </Button>
        </form>
        <OutcomeText outcome={outcome} />
        <div className="flex justify-end">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
