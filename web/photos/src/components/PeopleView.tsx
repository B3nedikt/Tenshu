import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { Person } from '@tenshu/sdk/photos'
import { StreamNotice } from '@tenshu/app-kit/ui'
import { personPhotos } from '../lib/proxy'
import { usePeople } from '../lib/live'
import { useFeed } from '../lib/feed'
import { queryKeys } from '../lib/queryKeys'
import { useCoverUrl, useOnScreen } from '../lib/thumbnails'
import { useCloseViewerWhenEmpty, type ViewerRoute } from '../lib/viewerRoute'
import { PhotoListView } from './PhotoListView'
import { Button, ErrorPanel, Loading } from './ui'

// People (photos-app.md §42.7): the people someone NAMED on a device that groups faces, and their
// photographs. ⚠⚠ Read-only and named-only on purpose: grouping faces needs face templates, which
// never leave the device that made them (IN-D5), and a model the browser does not run. So there are
// no unnamed groups, no "Is this Anna?" and no naming here — the names arrive by sync.
//
// Under the app's URL the open person is the fragment's `p=<name>` (a name is the only identity a
// synced person has; the fragment never leaves the browser) and its viewer `open=<index>`.
export function PeopleView({
  name,
  onPerson,
  viewer,
}: {
  name?: string | null
  // Open a person (a history entry), or null for the list (its Back).
  onPerson: (name: string | null) => void
  viewer?: ViewerRoute
}) {
  const { t } = useTranslation()
  const people = usePeople()
  const data = people.data
  const open = name && data?.ok ? data.people.find((p) => p.name === name) : undefined
  if (name && open) return <PersonDetail person={open} onBack={() => onPerson(null)} viewer={viewer} />

  return (
    <div className="space-y-4">
      <StreamNotice live={people} copy={{ stopped: t('stream.stopped'), reconnect: t('stream.reconnect') }} />
      <div>
        <h2 className="text-lg font-semibold text-text">{t('nav.people')}</h2>
        <p className="mt-1 text-sm text-text-2">{t('people.hint')}</p>
      </div>
      {people.isPending && <Loading />}
      {people.error && <ErrorPanel error={people.error} onRetry={people.retry} />}
      {data && !data.ok && <ErrorPanel failure={data.error} onRetry={people.retry} />}
      {data?.ok && data.people.length === 0 && <p className="text-sm text-text-2">{t('people.empty')}</p>}
      {data?.ok && data.people.length > 0 && (
        <ul className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
          {data.people.map((p) => (
            <li key={p.name}>
              <PersonCard person={p} onOpen={() => onPerson(p.name)} />
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function PersonCard({ person, onOpen }: { person: Person; onOpen: () => void }) {
  const { t } = useTranslation()
  const ref = useRef<HTMLButtonElement>(null)
  const circle = useRef<HTMLDivElement>(null)
  const onScreen = useOnScreen(ref)
  const cover = useCoverUrl(person.coverPath, circle, onScreen)
  return (
    <button ref={ref} type="button" onClick={onOpen} className="block w-full text-center">
      <div ref={circle} className="mx-auto aspect-square w-full overflow-hidden rounded-full bg-surface-3">
        {cover && <img src={cover} alt="" className="size-full object-cover" />}
      </div>
      <div className="mt-2 truncate text-sm font-medium text-text">{person.name}</div>
      <div className="text-xs text-text-2">{t('people.count', { count: person.photoCount })}</div>
    </button>
  )
}

function PersonDetail({ person, onBack, viewer }: { person: Person; onBack: () => void; viewer?: ViewerRoute }) {
  const { t } = useTranslation()
  const feed = useFeed()
  const queryClient = useQueryClient()
  // A snapshot under ONE key, refetched when the pushed count moves (a name added on a device, a
  // photo trashed). ⚠ The count must not be in the key: a new key has no data, so the grid and an
  // open viewer would drop to a spinner on every change (as Albums, `AlbumsView`).
  const photos = useQuery({ queryKey: queryKeys.personPhotos(person.name), queryFn: () => personPhotos(person.name) })
  const count = person.photoCount
  useEffect(() => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.personPhotos(person.name) })
  }, [queryClient, person.name, count])
  const data = photos.data
  useCloseViewerWhenEmpty(viewer, !!data?.ok && data.photos.length === 0)
  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4">
      <div className="flex items-center gap-2">
        <Button size="sm" variant="ghost" onClick={onBack}>
          {t('people.back')}
        </Button>
        <h2 className="min-w-0 flex-1 truncate text-lg font-semibold text-text">{person.name}</h2>
      </div>
      {photos.isPending && <Loading />}
      {photos.error && <ErrorPanel error={photos.error} onRetry={() => void photos.refetch()} />}
      {data && !data.ok && <ErrorPanel failure={data.error} onRetry={() => void photos.refetch()} />}
      {data?.ok && data.photos.length === 0 && (
        <p className="text-sm text-text-2">{t('people.emptyPerson', { name: person.name })}</p>
      )}
      {data?.ok && data.photos.length > 0 && (
        <PhotoListView
          photos={data.photos}
          viewer={viewer}
          onTrashed={() => {
            feed.refresh()
            void photos.refetch()
          }}
        />
      )}
    </div>
  )
}
