import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { Photo } from '@tenshu/sdk/photos'
import { useFavourites } from '../lib/live'
import { previewPathOf } from '../lib/media'
import { useThumbnail } from '../lib/thumbnails'
import type { PhotoActions, PhotoFocus } from '../lib/pane'
import { PhotoDetails } from './PhotoDetails'
import { Button } from './ui'

// The Info pane's content (web-apps-layout.md §3.2, D2 — Drive's details pane, sticky): the ONE
// selected photograph — its thumbnail, name, capture date, the stored details the viewer shows,
// and Favourite · Add to album · Download · Move to trash — else a short hint.
export function PhotoPane({ focus }: { focus: PhotoFocus | null }) {
  const { t } = useTranslation()
  if (focus?.photo) return <PhotoInfo key={focus.photo.path} photo={focus.photo} actions={focus.actions} />
  return (
    <p className="text-sm text-text-3">
      {focus && focus.count > 1 ? t('pane.several', { count: focus.count }) : t('pane.none')}
    </p>
  )
}

function PhotoInfo({ photo, actions }: { photo: Photo; actions: PhotoActions }) {
  const { t, i18n } = useTranslation()
  const url = useThumbnail(previewPathOf(photo), true)
  const favourites = useFavourites().data
  const isFavourite = useMemo(
    () => !!favourites?.ok && favourites.photos.some((p) => p.contentId === photo.contentId),
    [favourites, photo.contentId],
  )
  // ⚠ A file predating content addressing has no content id: it cannot be filed (as the menu).
  const fileable = photo.contentId !== ''
  const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(photo.capturedAtMs)
  return (
    <div className="flex flex-col gap-4 text-sm">
      <div className="aspect-square w-full overflow-hidden rounded-lg bg-surface-3">
        {url && <img src={url} alt="" draggable={false} className="size-full object-cover" />}
      </div>
      <div>
        <h2 className="break-all font-semibold text-text">{photo.name}</h2>
        <p className="text-text-2">{date}</p>
      </div>
      <div className="flex flex-wrap gap-2">
        {fileable && (
          <>
            <Button size="sm" variant="outline" onClick={() => actions.setFavourite(photo, !isFavourite)}>
              {isFavourite ? t('menu.unfavourite') : t('menu.favourite')}
            </Button>
            <Button size="sm" variant="outline" onClick={() => actions.addToAlbum(photo)}>
              {t('select.addToAlbum')}
            </Button>
          </>
        )}
        <Button size="sm" variant="outline" onClick={() => actions.download(photo)}>
          {t('menu.download')}
        </Button>
        <Button size="sm" variant="danger-ghost" onClick={() => actions.trash(photo)}>
          {t('menu.trash')}
        </Button>
      </div>
      <PhotoDetails photo={photo} tone="surface" showName={false} />
    </div>
  )
}
