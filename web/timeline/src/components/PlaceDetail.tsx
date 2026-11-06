// One place: its name, role and visits, and the four writes the web has for it — rename (the
// web's naming route: no geocoder, §Web scope 2), the user's role answer, merge, and unmerge.
//
// ⚠⚠ Unmerge takes a FOLDED place's id from `foldedPlaces`, never this place's: the survivor has
// no merge row, so `unmergePlace(place.placeId)` is `error_timeline_not_merged` (run A fix 1).
// ⚠ Merge's survivor is min(rootA, rootB), not the place the user started from — the screen
// follows `survivorId`.
import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { TimelineFoldedPlace, TimelinePlace, TimelinePlaceDetail, TimelinePlaceRole } from '@tenshu/sdk/timeline'
import { mergePlaces, place as readPlace, renamePlace, setPlaceRole, unmergePlace } from '../lib/proxy'
import { queryKeys } from '../lib/queryKeys'
import { usePlaces } from '../lib/live'
import { attempt, type Outcome } from '../lib/outcome'
import { dateTimeText, placeLabel, roleName } from '../lib/format'
import type { Detail, Open } from '../lib/nav'
import { placeOverlay } from '../lib/route'
import { AppMap } from './AppMap'
import { MomentRow } from './MomentRow'
import { BackButton, Button, ConfirmDialog, Dialog, Loading, OutcomeText, PromptDialog, ReadFailure, Section } from './ui'

export function PlaceDetail({
  placeId,
  onBack,
  onOpen,
  onReplace,
}: {
  placeId: string
  onBack: () => void
  onOpen: Open
  // Swap this screen for another (after a merge whose survivor is the other place).
  onReplace: (detail: Detail) => void
}) {
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
        <p className="text-sm text-text-2">{t('place.notFound')}</p>
      ) : (
        <PlaceBody detail={query.data.place} onOpen={onOpen} onReplace={onReplace} />
      )}
    </div>
  )
}

type Dialog =
  | { readonly kind: 'rename' }
  | { readonly kind: 'role' }
  | { readonly kind: 'merge' }
  | { readonly kind: 'unmerge'; readonly folded: TimelineFoldedPlace }
  | null

function PlaceBody({
  detail,
  onOpen,
  onReplace,
}: {
  detail: TimelinePlaceDetail
  onOpen: Open
  onReplace: (detail: Detail) => void
}) {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const [dialog, setDialog] = useState<Dialog>(null)
  const place = detail.place
  const label = placeLabel(t, place)
  const confirmedRole = place.roleIsSuggestion ? null : roleName(t, place.role)
  // Every detail may show this place (a visit, a trip's day), so a write refetches them all; the
  // feed and the places list are pushed and update on their own.
  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.detail() })

  return (
    <article>
      <h2 className="text-xl font-semibold text-text">{label.title}</h2>
      {confirmedRole && place.name.trim() && <p className="mt-0.5 text-sm text-text">{place.name.trim()}</p>}
      {label.secondary && <p className="mt-0.5 text-sm text-text-2">{label.secondary}</p>}
      <p className="mt-1 text-xs text-text-2">
        {[
          t('places.visits', { count: place.visitCount }),
          place.lastVisitMs !== null ? t('place.lastVisit', { when: dateTimeText(i18n.language, place.lastVisitMs) }) : null,
        ]
          .filter(Boolean)
          .join(' · ')}
      </p>
      {detail.redirectedFrom && <p className="mt-3 text-sm text-text-2">{t('place.redirected')}</p>}

      <PlaceMap place={place} />

      <RolePrompt place={place} onAnswered={refresh} />

      <div className="mt-5 flex flex-wrap gap-2">
        <Button size="sm" variant="outline" onClick={() => setDialog({ kind: 'rename' })}>
          {t('place.rename')}
        </Button>
        <Button size="sm" variant="outline" onClick={() => setDialog({ kind: 'role' })}>
          {t('place.setRole')}
        </Button>
        <Button size="sm" variant="outline" onClick={() => setDialog({ kind: 'merge' })}>
          {t('place.merge')}
        </Button>
      </div>

      {detail.foldedPlaces.length > 0 && (
        <Section title={t('place.folded', { count: detail.mergedPlaceCount })}>
          <ul className="divide-y divide-line">
            {detail.foldedPlaces.map((folded) => (
              <li key={folded.placeId} className="flex items-center justify-between gap-3 py-2">
                <span className="truncate text-sm text-text">{folded.name.trim() || t('place.unnamed')}</span>
                <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: 'unmerge', folded })}>
                  {t('place.unmerge')}
                </Button>
              </li>
            ))}
          </ul>
        </Section>
      )}

      <Section title={t('place.visitsTitle')}>
        {detail.visits.length === 0 ? (
          <p className="text-sm text-text-2">{t('place.noVisits')}</p>
        ) : (
          <ul className="space-y-0.5">
            {detail.visits.map((m) => (
              <MomentRow key={m.momentId} moment={m} onOpen={onOpen} />
            ))}
          </ul>
        )}
      </Section>

      {dialog?.kind === 'rename' && (
        <PromptDialog
          title={t('rename.title')}
          label={t('rename.label')}
          initialValue={place.name}
          submitLabel={t('rename.confirm')}
          onSubmit={(name) => attempt(() => renamePlace(place.placeId, name), () => void refresh())}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === 'role' && (
        <RoleDialog place={place} onDone={() => void refresh()} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'merge' && (
        <MergeDialog
          place={place}
          onMerged={(survivorId) => {
            void refresh()
            if (survivorId !== place.placeId) onReplace({ kind: 'place', id: survivorId })
          }}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === 'unmerge' && (
        <ConfirmDialog
          title={t('unmerge.title')}
          body={t('unmerge.body', { name: dialog.folded.name.trim() || t('place.unnamed') })}
          confirmLabel={t('unmerge.confirm')}
          onConfirm={() => attempt(() => unmergePlace(dialog.folded.placeId), () => void refresh())}
          onClose={() => setDialog(null)}
        />
      )}
    </article>
  )
}

// The place's single pin (native `PlaceMap`, centred at its hero zoom). None for a place without a
// coordinate. ⚠ Keyed by the place, so a merge that swaps this screen to the survivor re-centres.
function PlaceMap({ place }: { place: TimelinePlace }) {
  const { t } = useTranslation()
  const { placeId, latE7, lngE7 } = place
  const overlay = useMemo(() => placeOverlay({ placeId, latE7, lngE7 }), [placeId, latE7, lngE7])
  if (!overlay) return null
  return (
    <div className="mt-4">
      <AppMap
        key={placeId}
        label={t('map.place')}
        camera={overlay.camera}
        pins={overlay.pins}
        className="h-[220px] w-full overflow-hidden rounded-lg border border-line bg-surface-2"
      />
    </div>
  )
}

// An INFERRED role is a question ("Is this your home?"), answered yes or with a real refusal —
// "Not my home" writes `none` as the user's answer, so it does not come back (`PlaceRolePrompt`).
function RolePrompt({ place, onAnswered }: { place: TimelinePlace; onAnswered: () => void }) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(null)
  if (!place.roleIsSuggestion || (place.role !== 'home' && place.role !== 'work')) return null
  const role = place.role

  async function answer(value: TimelinePlaceRole) {
    setBusy(true)
    setOutcome(null)
    const result = await attempt(() => setPlaceRole(place.placeId, value), onAnswered)
    setBusy(false)
    setOutcome(result)
  }

  return (
    <div className="mt-4 rounded-lg border border-line bg-surface-2 px-4 py-3">
      <p className="text-sm text-text">{t(`role.prompt.${role}`)}</p>
      <div className="mt-2 flex gap-2">
        <Button size="sm" variant="outline" disabled={busy} onClick={() => void answer('none')}>
          {t(`role.refuse.${role}`)}
        </Button>
        <Button size="sm" disabled={busy} onClick={() => void answer(role)}>
          {t('role.yes')}
        </Button>
      </div>
      <OutcomeText outcome={outcome} />
    </div>
  )
}

const ROLE_CHOICES: readonly TimelinePlaceRole[] = ['home', 'work', 'none']

// The user's own answer, from scratch. Always written user-authored (the SDK has no other way).
function RoleDialog({ place, onDone, onClose }: { place: TimelinePlace; onDone: () => void; onClose: () => void }) {
  const { t } = useTranslation()
  const current = !place.roleIsSuggestion && (place.role === 'home' || place.role === 'work') ? place.role : 'none'
  const [choice, setChoice] = useState<TimelinePlaceRole>(current)
  return (
    <ConfirmDialog
      title={t('roleDialog.title')}
      body={t('roleDialog.body')}
      confirmLabel={t('roleDialog.confirm')}
      onConfirm={() => attempt(() => setPlaceRole(place.placeId, choice), onDone)}
      onClose={onClose}
    >
      <fieldset className="space-y-1.5 text-sm">
        <legend className="sr-only">{t('roleDialog.title')}</legend>
        {ROLE_CHOICES.map((value) => (
          <label key={value} className="flex items-center gap-2 text-text">
            <input type="radio" name="role" value={value} checked={choice === value} onChange={() => setChoice(value)} />
            {value === 'none' ? t('role.none') : t(`role.${value}`)}
          </label>
        ))}
      </fieldset>
    </ConfirmDialog>
  )
}

// Pick the other place, then confirm. ⚠ The survivor is the SDK's (min root), so the copy never
// promises which name is kept.
function MergeDialog({
  place,
  onMerged,
  onClose,
}: {
  place: TimelinePlace
  onMerged: (survivorId: string) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const live = usePlaces()
  const others = live.data?.ok ? live.data.places.filter((p) => p.placeId !== place.placeId) : []
  const [otherId, setOtherId] = useState('')
  const other = others.find((p) => p.placeId === otherId)
  const [confirming, setConfirming] = useState(false)

  if (confirming && other) {
    return (
      <ConfirmDialog
        title={t('merge.confirmTitle')}
        body={t('merge.confirmBody', { a: placeLabel(t, place).title, b: placeLabel(t, other).title })}
        confirmLabel={t('merge.confirm')}
        onConfirm={() => attempt(() => mergePlaces(place.placeId, other.placeId), (r) => onMerged(r.survivorId))}
        onClose={onClose}
      />
    )
  }

  return (
    <Dialog title={t('merge.title')} onClose={onClose}>
      <div className="space-y-4">
        <p className="text-sm text-text-2">{t('merge.body')}</p>
        {!live.data ? (
          <Loading />
        ) : !live.data.ok ? (
          <ReadFailure failure={live.data.error} onRetry={live.retry} />
        ) : others.length === 0 ? (
          <p className="text-sm text-text-2">{t('merge.noOthers')}</p>
        ) : (
          <label className="block text-sm">
            <span className="text-text-2">{t('merge.with')}</span>
            <select
              className="mt-1 block w-full rounded-lg border border-line bg-surface px-2 py-1.5 text-text"
              value={otherId}
              onChange={(e) => setOtherId(e.target.value)}
            >
              <option value="">{t('merge.choose')}</option>
              {others.map((p) => {
                const l = placeLabel(t, p)
                return (
                  <option key={p.placeId} value={p.placeId}>
                    {l.secondary ? `${l.title} — ${l.secondary}` : l.title}
                  </option>
                )
              })}
            </select>
          </label>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button disabled={!other} onClick={() => setConfirming(true)}>
            {t('merge.next')}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
