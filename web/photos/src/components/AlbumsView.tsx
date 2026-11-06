import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { PhotoAlbum } from '@tenshu/sdk/photos'
import { StreamNotice } from '@tenshu/app-kit/ui'
import { albumPhotos, createAlbum, deleteAlbum, renameAlbum } from '../lib/proxy'
import { useAlbums } from '../lib/live'
import { useFeed } from '../lib/feed'
import { queryKeys } from '../lib/queryKeys'
import { attempt } from '../lib/errors'
import { useOnScreen, useThumbnail } from '../lib/thumbnails'
import { PhotoListView } from './PhotoListView'
import { Button, ConfirmDialog, ErrorPanel, Loading, PromptDialog } from './ui'

// Albums: the list (pushed over `photo.albums.observe`), create, and one album open — rename,
// delete, its photographs, and removing from it.
export function AlbumsView() {
  const { t } = useTranslation()
  const albums = useAlbums()
  const [openId, setOpenId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const data = albums.data

  const open = data?.ok ? data.albums.find((a) => a.albumId === openId) : undefined
  if (openId && open) return <AlbumDetail album={open} onBack={() => setOpenId(null)} />

  return (
    <div className="space-y-4">
      <StreamNotice live={albums} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-text">{t('nav.albums')}</h2>
        <Button size="sm" onClick={() => setCreating(true)}>
          {t('albums.create')}
        </Button>
      </div>
      {albums.isPending && <Loading />}
      {albums.error && <ErrorPanel error={albums.error} onRetry={albums.retry} />}
      {data && !data.ok && <ErrorPanel failure={data.error} onRetry={albums.retry} />}
      {data?.ok && data.albums.length === 0 && <p className="text-sm text-text-2">{t('albums.empty')}</p>}
      {data?.ok && data.albums.length > 0 && (
        <ul className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          {data.albums.map((album) => (
            <li key={album.albumId}>
              <AlbumCard album={album} onOpen={() => setOpenId(album.albumId)} />
            </li>
          ))}
        </ul>
      )}
      {creating && (
        <PromptDialog
          title={t('albums.create')}
          label={t('albums.name')}
          submitLabel={t('albums.createSubmit')}
          onSubmit={(name) => attempt(() => createAlbum(name))}
          onClose={() => setCreating(false)}
        />
      )}
    </div>
  )
}

function AlbumCard({ album, onOpen }: { album: PhotoAlbum; onOpen: () => void }) {
  const { t } = useTranslation()
  const ref = useRef<HTMLButtonElement>(null)
  const onScreen = useOnScreen(ref)
  const cover = useThumbnail(album.coverPath, onScreen)
  return (
    <button ref={ref} type="button" onClick={onOpen} className="block w-full text-left">
      <div className="aspect-square overflow-hidden rounded-lg bg-surface-3">
        {cover && <img src={cover} alt="" className="size-full object-cover" />}
      </div>
      <div className="mt-2 truncate text-sm font-medium text-text">{album.name}</div>
      <div className="text-xs text-text-2">{t('albums.count', { count: album.photoCount })}</div>
    </button>
  )
}

function AlbumDetail({ album, onBack }: { album: PhotoAlbum; onBack: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const feed = useFeed()
  const [renaming, setRenaming] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const photos = useQuery({ queryKey: queryKeys.albumPhotos(album.albumId), queryFn: () => albumPhotos(album.albumId) })

  // `albumPhotos` is a snapshot. A push of the album list (a membership change from another device,
  // or this one) moves the count, so the open album refetches when it does.
  const count = album.photoCount
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.albumPhotos(album.albumId) })
  }, [queryClient, album.albumId, count])

  const data = photos.data
  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4">
      <div className="flex items-center gap-2">
        <Button size="sm" variant="ghost" onClick={onBack}>
          {t('albums.back')}
        </Button>
        <h2 className="min-w-0 flex-1 truncate text-lg font-semibold text-text">{album.name}</h2>
        <Button size="sm" variant="outline" onClick={() => setRenaming(true)}>
          {t('albums.rename')}
        </Button>
        <Button size="sm" variant="danger-ghost" onClick={() => setDeleting(true)}>
          {t('albums.delete')}
        </Button>
      </div>
      {photos.isPending && <Loading />}
      {photos.error && <ErrorPanel error={photos.error} onRetry={() => void photos.refetch()} />}
      {data && !data.ok && <ErrorPanel failure={data.error} onRetry={() => void photos.refetch()} />}
      {data?.ok && data.photos.length === 0 && <p className="text-sm text-text-2">{t('albums.emptyAlbum')}</p>}
      {data?.ok && data.photos.length > 0 && (
        <PhotoListView
          photos={data.photos}
          albumId={album.albumId}
          onTrashed={() => {
            feed.refresh()
            void photos.refetch()
          }}
        />
      )}
      {renaming && (
        <PromptDialog
          title={t('albums.rename')}
          label={t('albums.name')}
          initialValue={album.name}
          submitLabel={t('common.save')}
          onSubmit={(name) => attempt(() => renameAlbum(album.albumId, name))}
          onClose={() => setRenaming(false)}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title={t('albums.deleteTitle')}
          message={t('albums.deleteBody', { name: album.name })}
          confirmLabel={t('albums.delete')}
          onConfirm={async () => {
            const outcome = await attempt(() => deleteAlbum(album.albumId))
            if (!outcome) onBack()
            return outcome
          }}
          onClose={() => setDeleting(false)}
        />
      )}
    </div>
  )
}
