// One place: its name (the user's, or the one stored with it), where it is, its coordinate, its
// confirmed role, its visits (from Timeline — §Web scope 5b), the map, and the two actions the web
// has for it: *Rename* (with a confirm) and *Copy coordinates* (no map-provider link, §Web scope 4).
//
// ⚠ `place.get` follows a folded id to its survivor (`redirectedFrom`), so every later call — the
// visits read and the rename — uses the id the read RETURNED, never the one the screen was opened with.
import { useMemo, useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { MapsPlace, MapsPlaceDetail } from '@tenshu/sdk/maps'
import { place as readPlace, placeVisits, renamePlace } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { attempt, type Outcome } from '../lib/outcome'
import { coordinateText, dateTimeText, lastVisitText, placeLabel, roleName, whereText } from '../lib/format'
import { OfflineMapsNote } from './OfflineMapsNote'
import { PlacesMap, PLACE_ZOOM } from './PlacesMap'
import { BackButton, Button, Dialog, Field, Loading, OutcomeText, ReadFailure, Section } from './ui'

export function PlaceDetail({ placeId, onBack }: { placeId: string; onBack: () => void }) {
  const { t } = useTranslation()
  const query = useQuery({ queryKey: queryKeys.place(placeId), queryFn: () => readPlace(placeId) })

  return (
    <div>
      <BackButton onBack={onBack} />
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ReadFailure error={query.error} onRetry={() => void query.refetch()} />
      ) : !query.data.ok ? (
        <ReadFailure failure={query.data.error} onRetry={() => void query.refetch()} />
      ) : query.data.place === null ? (
        <div className="rounded-lg border border-line bg-surface-2 px-4 py-4">
          <p className="text-sm font-medium text-text">{t('place.notFoundTitle')}</p>
          <p className="mt-1 text-sm text-text-2">{t('place.notFoundBody')}</p>
        </div>
      ) : (
        <PlaceBody detail={query.data.place} />
      )}
    </div>
  )
}

function PlaceBody({ detail }: { detail: MapsPlaceDetail }) {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const [renaming, setRenaming] = useState(false)
  const place = detail.place
  const label = placeLabel(t, i18n.language, place)
  const role = roleName(t, place.role)
  const where = whereText(i18n.language, place)
  const pinned = useMemo(() => [place], [place])
  const camera = useMemo(
    () => ({ kind: 'center' as const, center: { lat: place.latitude, lng: place.longitude }, zoom: PLACE_ZOOM }),
    [place],
  )
  // The list is pushed and updates on its own; the detail reads are snapshots.
  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.detail() })

  return (
    <article>
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-xl font-semibold text-text">{label.title}</h2>
        {role && <span className="rounded-full border border-line px-2 py-0.5 text-xs text-text-2">{role}</span>}
      </div>
      <p className="mt-0.5 text-xs text-text-2">
        {label.unnamed ? t('place.unnamedHint') : place.nameIsUsers ? t('place.nameIsUsers') : t('place.nameIsStored')}
      </p>
      {where && <p className="mt-1 text-sm text-text-2">{where}</p>}
      {detail.redirectedFrom && <p className="mt-3 text-sm text-text-2">{t('place.redirected')}</p>}
      {detail.mergedPlaceCount > 0 && (
        <p className="mt-1 text-xs text-text-2">{t('place.merged', { count: detail.mergedPlaceCount })}</p>
      )}

      <div className="mt-4 flex flex-wrap gap-2">
        <Button size="sm" variant="outline" onClick={() => setRenaming(true)}>
          {t('place.rename')}
        </Button>
      </div>

      <div className="mt-5">
        <PlacesMap places={pinned} camera={camera} />
      </div>

      <Section title={t('place.coordinateTitle')}>
        <CopyCoordinates place={place} />
      </Section>

      <Section title={t('place.visitsTitle')}>
        <Visits placeId={place.placeId} />
      </Section>

      <div className="mt-6">
        <OfflineMapsNote />
      </div>

      {renaming && <RenameDialog place={place} onRenamed={() => void refresh()} onClose={() => setRenaming(false)} />}
    </article>
  )
}

// ── visits (`timeline.placeVisitSummary`, §Web scope 5b) ────────────────────

function Visits({ placeId }: { placeId: string }) {
  const { t, i18n } = useTranslation()
  const query = useQuery({ queryKey: queryKeys.visits(placeId), queryFn: () => placeVisits(placeId) })
  // "3 days ago" is measured from when the screen opened, so a re-render never changes it.
  const [now] = useState(() => Date.now())
  if (query.isPending) return <Loading />
  if (query.isError) return <ReadFailure error={query.error} onRetry={() => void query.refetch()} />
  if (!query.data.ok) return <ReadFailure failure={query.data.error} onRetry={() => void query.refetch()} />
  const stats = query.data.summary
  // ⚠ `summary: null` from Timeline is the same answer as a place with no visits: Maps already found
  // the place, so "no visits" is true either way — never a failure.
  if (!stats || stats.visitCount === 0) return <p className="text-sm text-text-2">{t('visits.none')}</p>
  return (
    <p className="text-sm text-text">
      <span>{t('visits.count', { count: stats.visitCount })}</span>
      {stats.lastVisitMs !== null && (
        <>
          {' · '}
          <time dateTime={new Date(stats.lastVisitMs).toISOString()} title={dateTimeText(i18n.language, stats.lastVisitMs)}>
            {t('visits.last', { when: lastVisitText(t, now, stats.lastVisitMs) })}
          </time>
        </>
      )}
    </p>
  )
}

// ── copy coordinates (§Web scope 4: the Clipboard API, no egress) ───────────

type CopyState = 'idle' | 'copied' | 'failed'

function CopyCoordinates({ place }: { place: MapsPlace }) {
  const { t } = useTranslation()
  const [state, setState] = useState<CopyState>('idle')
  const text = coordinateText(place.latitude, place.longitude)

  async function copy() {
    try {
      // Absent on an insecure origin and in some embeddings; a refused permission rejects.
      if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
      await navigator.clipboard.writeText(text)
      setState('copied')
    } catch {
      setState('failed')
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-3">
      <code className="text-sm text-text">{text}</code>
      <Button size="sm" variant="outline" onClick={() => void copy()}>
        {t('copy.action')}
      </Button>
      {state === 'copied' && (
        <span role="status" className="text-xs text-text-2">
          {t('copy.done')}
        </span>
      )}
      {/* The coordinate stays on screen to select by hand. */}
      {state === 'failed' && (
        <span role="alert" className="text-xs text-danger">
          {t('copy.failed')}
        </span>
      )}
    </div>
  )
}

// ── rename (two steps: the name, then a confirm) ────────────────────────────

function RenameDialog({ place, onRenamed, onClose }: { place: MapsPlace; onRenamed: () => void; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const [value, setValue] = useState(place.name)
  const [confirming, setConfirming] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)
  const current = placeLabel(t, i18n.language, place).title

  function next(event: FormEvent) {
    event.preventDefault()
    const trimmed = value.trim()
    if (!trimmed) return
    setOutcome(null)
    setConfirming(trimmed)
  }

  async function confirm(name: string) {
    setBusy(true)
    setOutcome(null)
    // ⚠ The id the read returned (a survivor's, after a fold), never the one the screen opened with.
    const result = await attempt(() => renamePlace(place.placeId, name), onRenamed)
    if (result) {
      setOutcome(result)
      setBusy(false)
    } else {
      onClose()
    }
  }

  if (confirming !== null) {
    return (
      <Dialog title={t('rename.confirmTitle')} onClose={onClose}>
        <div className="space-y-4">
          <p className="text-sm text-text-2">{t('rename.confirmBody', { from: current, to: confirming })}</p>
          <OutcomeText outcome={outcome} />
          <div className="flex justify-end gap-2">
            <Button variant="ghost" disabled={busy} onClick={() => setConfirming(null)}>
              {t('common.back')}
            </Button>
            <Button disabled={busy} onClick={() => void confirm(confirming)}>
              {busy ? t('common.working') : t('rename.confirm')}
            </Button>
          </div>
        </div>
      </Dialog>
    )
  }

  return (
    <Dialog title={t('rename.title')} onClose={onClose}>
      <form onSubmit={next} className="space-y-4">
        <Field autoFocus label={t('rename.label')} value={value} onChange={(e) => setValue(e.target.value)} />
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={!value.trim() || value.trim() === place.name.trim()}>
            {t('rename.next')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
