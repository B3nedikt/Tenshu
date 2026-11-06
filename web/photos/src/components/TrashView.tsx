import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TrashedPhoto } from '@tenshu/sdk/photos'
import { StreamNotice } from '@tenshu/app-kit/ui'
import { deleteForever, restorePhoto } from '../lib/proxy'
import { useTrash } from '../lib/live'
import { useFeed } from '../lib/feed'
import { attempt, type Outcome } from '../lib/errors'
import { useOnScreen, useThumbnail } from '../lib/thumbnails'
import { Button, ConfirmDialog, ErrorPanel, Loading, OutcomeText } from './ui'

// The trash, pushed over `photo.trash.observe`: restore, or delete forever behind a confirm.
// ⚠⚠ Delete forever is irreversible, so it only ever runs from the dialog's confirm.
export function TrashView() {
  const { t } = useTranslation()
  const trash = useTrash()
  const [confirming, setConfirming] = useState<TrashedPhoto | null>(null)
  const data = trash.data
  return (
    <div className="space-y-4">
      <StreamNotice live={trash} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />
      <p className="text-sm text-text-2">{t('trash.explain')}</p>
      {trash.isPending && <Loading />}
      {trash.error && <ErrorPanel error={trash.error} onRetry={trash.retry} />}
      {data && !data.ok && <ErrorPanel failure={data.error} onRetry={trash.retry} />}
      {data?.ok && data.items.length === 0 && <p className="text-sm text-text-2">{t('trash.empty')}</p>}
      {data?.ok && data.items.length > 0 && (
        <ul className="divide-y divide-line rounded-lg border border-line">
          {data.items.map((item) => (
            <TrashRow key={item.contentId} item={item} onDelete={() => setConfirming(item)} />
          ))}
        </ul>
      )}
      {confirming && (
        <ConfirmDialog
          title={t('trash.deleteTitle')}
          message={t('trash.deleteBody', { name: confirming.photo.name })}
          confirmLabel={t('trash.deleteForever')}
          onConfirm={() => attempt(() => deleteForever(confirming.contentId))}
          onClose={() => setConfirming(null)}
        />
      )}
    </div>
  )
}

function TrashRow({ item, onDelete }: { item: TrashedPhoto; onDelete: () => void }) {
  const { t, i18n } = useTranslation()
  const feed = useFeed()
  const ref = useRef<HTMLLIElement>(null)
  const onScreen = useOnScreen(ref)
  const thumb = useThumbnail(item.photo.path, onScreen)
  const [outcome, setOutcome] = useState<Outcome>(null)
  const [busy, setBusy] = useState(false)
  const when = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium' }).format(item.trashedAtMs)

  return (
    <li ref={ref} className="flex flex-wrap items-center gap-3 px-3 py-2">
      <div className="size-12 shrink-0 overflow-hidden rounded bg-surface-3">
        {thumb && <img src={thumb} alt="" className="size-full object-cover" />}
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm text-text">{item.photo.name}</div>
        <div className="truncate text-xs text-text-2">{t('trash.trashedOn', { date: when, path: item.originalPath })}</div>
      </div>
      <Button
        size="sm"
        variant="outline"
        disabled={busy}
        onClick={async () => {
          setBusy(true)
          const result = await attempt(() => restorePhoto(item.contentId))
          setBusy(false)
          setOutcome(result)
          // Back in the feed: the feed has no push form, so it refetches.
          if (!result) feed.refresh()
        }}
      >
        {t('trash.restore')}
      </Button>
      <Button size="sm" variant="danger-ghost" disabled={busy} onClick={onDelete}>
        {t('trash.deleteForever')}
      </Button>
      {outcome && (
        <div className="w-full">
          <OutcomeText outcome={outcome} />
        </div>
      )}
    </li>
  )
}
