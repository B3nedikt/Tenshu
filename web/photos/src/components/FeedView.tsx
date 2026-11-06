import { useCallback, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useFeed } from '../lib/feed'
import { monthSections } from '../lib/layout'
import { useSelection } from '../lib/selection'
import { PhotoGrid } from './PhotoGrid'
import { SelectionBar } from './SelectionBar'
import { Viewer } from './Viewer'
import { Button, ErrorPanel, Loading } from './ui'

// The library: the capture-date grid over the keyset pager, month headers and the scrubber from
// the capture-time index, multi-select, and the viewer over the same rows.
export function FeedView() {
  const { t } = useTranslation()
  const feed = useFeed()
  const selection = useSelection()
  const [open, setOpen] = useState<number | null>(null)
  const [returnTo, setReturnTo] = useState<number | null>(null)
  const { pager, page } = feed

  const times = feed.times.data?.ok ? feed.times.data.times : null
  const sections = useMemo(() => (times ? monthSections(times) : []), [times])
  const need = useCallback((index: number) => void pager.ensure(index), [pager])
  const photoAt = useCallback((index: number) => page.rows[index], [page.rows])

  if (feed.times.isPending) return <Loading />
  if (feed.times.error) return <ErrorPanel error={feed.times.error} onRetry={feed.refresh} />
  if (feed.times.data && !feed.times.data.ok) return <ErrorPanel failure={feed.times.data.error} onRetry={feed.refresh} />
  if (!times) return null

  // ⚠ The skeleton is the index; a row synced in after it was read is past its end until the user
  // refreshes (the feed has no push form). The pager never repeats a row either way.
  const total = times.length

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex items-center gap-2">
        <SelectionBar selection={selection} />
        <span className="flex-1" />
        <Button size="sm" variant="ghost" onClick={feed.refresh}>
          {t('feed.refresh')}
        </Button>
      </div>
      {page.failure && (
        <ErrorPanel
          failure={'failure' in page.failure ? page.failure.failure : null}
          error={'error' in page.failure ? page.failure.error : undefined}
          onRetry={() => void pager.retry()}
        />
      )}
      {total === 0 ? (
        <p className="text-sm text-text-2">{t('feed.empty')}</p>
      ) : (
        <PhotoGrid
          total={total}
          sections={sections}
          photoAt={photoAt}
          need={need}
          onOpen={setOpen}
          selection={selection}
          times={times}
          scrollToIndex={returnTo}
        />
      )}
      {open !== null && (
        <Viewer
          total={total}
          photoAt={photoAt}
          need={need}
          startIndex={open}
          onClose={(index) => {
            setOpen(null)
            setReturnTo(index)
          }}
          onTrashed={feed.refresh}
        />
      )}
    </div>
  )
}
