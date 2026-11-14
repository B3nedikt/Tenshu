// One app's preview card on the overview (docs/proposals/web-apex-home.md §3.6). The apex draws it
// from the app's `glance` data through one of a few fixed layouts; the header opens the app.
//
// - Loads after first paint, on its own (one slow app never holds up another), and again when the
//   window regains focus (at most every 30 s) — keeping what it shows meanwhile. A failed REFRESH
//   keeps the last good data without a sign; the next focus retries. Only a failed first load (or
//   retry) shows the failure.
// - ⚠⚠ A failed load is a FAILURE, never an empty card: the header stays, the body says it could not
//   load, with a retry. "Nothing here yet" is a separate, real empty state.
// - A glass card on the wallpaper (ADR 0027); its meters and bars take the app's own colour pair.
//   ⚠ Text on glass is `text-2` at the lightest: `text-3` falls under AA there (brand tokens/glass.ts).
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { AppIcon } from '../../apps/AppIcon'
import { appColorStyle } from '../../apps/appColor'
import { appLabel } from '../../apps/appLabel'
import type { AppGlance, GlanceCall, GlanceData, HostedApp } from '../../apps/registry'
// The quota-scale formatter, as every platform's storage figures use ("5 GB", not "5120.00 MB").
import { formatStorageBytes as formatBytes } from '../../lib/format'
import { AgendaBody, StatBody } from './AgendaStatBodies'
import { GridBody } from './GridBody'

const REFRESH_MS = 30_000

type CardState =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly data: GlanceData }
  | { readonly kind: 'failed' }

export function GlanceCard({
  app,
  glance,
  call,
  spanClass = glance.size === 'wide' ? 'lg:col-span-3' : 'lg:col-span-2',
}: {
  readonly app: HostedApp
  readonly glance: AppGlance
  readonly call: GlanceCall
  /** Its column span on large screens; the grid packs rows (`packSpans`). */
  readonly spanClass?: string
}) {
  const { t } = useTranslation('control')
  const [state, setState] = useState<CardState>({ kind: 'loading' })
  // Answers from a load that a newer one (or unmount) superseded are dropped.
  const generation = useRef(0)

  const load = useCallback(() => {
    const mine = ++generation.current
    glance.load(call).then(
      data => {
        if (generation.current === mine) setState({ kind: 'ready', data })
      },
      () => {
        if (generation.current === mine) setState(s => (s.kind === 'ready' ? s : { kind: 'failed' }))
      },
    )
  }, [glance, call])

  useEffect(() => {
    const gen = generation
    load()
    // Refresh on focus, at most every REFRESH_MS: a load can be a whole-drive walk (`recentFiles`),
    // and quick alt-tabbing would start several at once.
    let last = Date.now()
    const onFocus = () => {
      if (Date.now() - last < REFRESH_MS) return
      last = Date.now()
      load()
    }
    window.addEventListener('focus', onFocus)
    return () => {
      // Invalidates every load in flight: its answer must not land on an unmounted card.
      gen.current++
      window.removeEventListener('focus', onFocus)
    }
  }, [load])

  const label = appLabel(t, app)
  return (
    <article
      aria-labelledby={`glance-${app.id}`}
      style={appColorStyle(app.id)}
      // ⚠ No backdrop blur here: over the FIXED wallpaper every card would re-blur on each scroll frame,
      // and the wallpaper is a smooth gradient the blur barely changes. The sticky header keeps it.
      className={`flex min-h-56 flex-col overflow-hidden rounded-[22px] border border-glass-line bg-glass shadow-glass ${spanClass}`}
    >
      <Link
        to={app.manifest.launchPath}
        className="flex items-center gap-3 rounded-t-[22px] bg-glass-head px-5 pb-3 pt-4 text-text hover:bg-well focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-line-strong"
      >
        <AppIcon appId={app.id} label={label} size="md" />
        <span className="min-w-0 flex-1">
          <span id={`glance-${app.id}`} className="block text-base font-semibold">
            {label}
          </span>
          <span className="block text-sm text-text-2">{t(`glance.subtitle.${glance.subtitle}`)}</span>
        </span>
        <span className="flex items-center text-sm font-medium text-text-2">
          {t('glance.open')}
          <svg aria-hidden viewBox="0 0 20 20" width={16} height={16} fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
            <path d="M8 5l5 5-5 5" />
          </svg>
        </span>
      </Link>
      <div className="flex flex-1 flex-col">
        {state.kind === 'loading' && <div aria-busy="true" aria-label={t('shell.loading')} className="flex-1" />}
        {state.kind === 'failed' && (
          <div role="status" className="flex flex-1 flex-col items-center justify-center gap-3 p-6 text-sm text-text-2">
            {t('glance.failed')}
            <button
              type="button"
              onClick={() => {
                setState({ kind: 'loading' })
                load()
              }}
              className="rounded-pill bg-well px-4 py-1.5 font-medium text-text hover:opacity-80"
            >
              {t('glance.retry')}
            </button>
          </div>
        )}
        {state.kind === 'ready' && <Body data={state.data} call={call} appId={app.id} />}
      </div>
    </article>
  )
}

function Body({ data, call, appId }: { readonly data: GlanceData; readonly call: GlanceCall; readonly appId: string }) {
  const { t } = useTranslation('control')
  if (isEmpty(data)) {
    return (
      <p className="flex flex-1 items-center justify-center p-6 text-sm text-text-2">
        {t(`glance.empty.${appId}`, { defaultValue: t('glance.empty.default') })}
      </p>
    )
  }
  switch (data.layout) {
    case 'grid':
      return <GridBody tiles={data.tiles} call={call} />
    case 'agenda':
      return <AgendaBody rows={data.rows} />
    case 'stat':
      return <StatBody data={data} />
    case 'list':
      return <ListBody rows={data.rows} usage={data.usage} />
  }
}

function isEmpty(data: GlanceData): boolean {
  switch (data.layout) {
    case 'grid':
      return data.tiles.length === 0
    case 'stat':
      return data.value === null
    case 'list':
    case 'agenda':
      return data.rows.length === 0
  }
}

function ListBody({
  rows,
  usage,
}: {
  readonly rows: Extract<GlanceData, { layout: 'list' }>['rows']
  readonly usage: Extract<GlanceData, { layout: 'list' }>['usage']
}) {
  const { t, i18n } = useTranslation('control')
  const when = (ms: number) =>
    new Date(ms).toLocaleDateString(i18n.language, { day: 'numeric', month: 'short' })
  return (
    <>
      <ul className="grid gap-x-6 px-5 py-1 sm:grid-cols-2">
        {rows.map((row, i) => (
          <li key={`${row.title}-${i}`} className="flex min-w-0 items-center gap-3 border-b border-well py-2.5 last:border-b-0 sm:[&:nth-last-child(2):nth-child(odd)]:border-b-0">
            <span aria-hidden className="grid h-[34px] w-[34px] shrink-0 place-items-center rounded-[9px] bg-well text-[9.5px] font-bold tracking-wide text-text-2">
              {row.badge}
            </span>
            <span className="min-w-0">
              <span className="block truncate text-sm text-text">{row.title}</span>
              <span className="block text-xs tabular-nums text-text-2">
                {row.sizeBytes === null ? when(row.timeMs) : `${when(row.timeMs)} · ${formatBytes(row.sizeBytes)}`}
              </span>
            </span>
          </li>
        ))}
      </ul>
      {usage !== null && usage.limitBytes > 0 && (
        <div className="mt-auto flex items-center gap-3 px-5 pb-4 pt-2">
          <div
            role="meter"
            aria-label={t('glance.storageAriaLabel')}
            aria-valuemin={0}
            aria-valuemax={usage.limitBytes}
            aria-valuenow={usage.usedBytes}
            className="h-1.5 flex-1 overflow-hidden rounded-pill bg-well"
          >
            <div
              className="h-full rounded-pill bg-linear-90 from-(--a) to-(--b)"
              style={{ width: `${Math.min(100, (usage.usedBytes / usage.limitBytes) * 100)}%` }}
            />
          </div>
          <span className="shrink-0 text-xs tabular-nums text-text-2">
            {t('glance.storageUsed', { used: formatBytes(usage.usedBytes), limit: formatBytes(usage.limitBytes) })}
          </span>
        </div>
      )}
    </>
  )
}
