import { useTranslation } from 'react-i18next'
import { StreamNotice } from '@tenshu/app-kit/ui'
import { useFavourites } from '../lib/live'
import { useFeed } from '../lib/feed'
import { PhotoListView } from './PhotoListView'
import { ErrorPanel, Loading } from './ui'

// Favourites, pushed over `photo.favourites.observe`. ⚠ On web a favourited video is a favourite
// too; native's list is images only (photos-app.md §42.6).
export function FavouritesView() {
  const { t } = useTranslation()
  const favourites = useFavourites()
  const feed = useFeed()
  const data = favourites.data
  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4">
      <StreamNotice live={favourites} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />
      {favourites.isPending && <Loading />}
      {favourites.error && <ErrorPanel error={favourites.error} onRetry={favourites.retry} />}
      {data && !data.ok && <ErrorPanel failure={data.error} onRetry={favourites.retry} />}
      {data?.ok && data.photos.length === 0 && <p className="text-sm text-text-2">{t('favourites.empty')}</p>}
      {data?.ok && data.photos.length > 0 && <PhotoListView photos={data.photos} onTrashed={feed.refresh} />}
    </div>
  )
}
