// The `agenda` and `stat` card layouts (docs/proposals/web-apex-home.md §3.6): Calendar's and
// Timeline's next/latest few rows, and Fit's number of the day. Data from the app, words from here.
// Accents use the card's app colour pair (`--a`/`--b`, set by GlanceCard).
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import type { GlanceAgendaRow, GlanceData } from '../../apps/registry'
import { addDays, civilDate } from '../../apps/registry/glanceTime'

export function AgendaBody({ rows }: { readonly rows: readonly GlanceAgendaRow[] }) {
  return (
    <ul className="flex flex-col px-5 pb-3 pt-1">
      {rows.map((row, i) => (
        <AgendaLine key={`${row.startLocal}-${i}`} row={row} />
      ))}
    </ul>
  )
}

function AgendaLine({ row }: { readonly row: GlanceAgendaRow }) {
  const { t, i18n } = useTranslation('control')
  return (
    <li className="flex gap-3 py-2.5">
      <span aria-hidden className="w-[3px] shrink-0 rounded-pill bg-(--a)" />
      <span className="min-w-0">
        <span className="block truncate text-sm font-medium text-text">{rowTitle(t, row)}</span>
        <span className="block truncate text-xs tabular-nums text-text-2">
          {row.detail === '' ? when(t, i18n.language, row) : `${when(t, i18n.language, row)} · ${row.detail}`}
        </span>
      </span>
    </li>
  )
}

export function StatBody({ data }: { readonly data: Extract<GlanceData, { layout: 'stat' }> }) {
  const { t, i18n } = useTranslation('control')
  return (
    <div className="flex flex-1 flex-col gap-4 px-5 pb-5 pt-2">
      <div className="flex items-center gap-4">
        {data.progress !== null && <GoalRing progress={data.progress} label={t('glance.goalAriaLabel')} />}
        <p>
          <span className="block text-3xl font-semibold tabular-nums tracking-tight">
            {formatNumber(i18n.language, data.value ?? 0)}
          </span>
          <span className="text-sm text-text-2">{t(`glance.unit.${data.unit}`)}</span>
        </p>
      </div>
      {data.footnote !== null && (
        <p className="truncate text-sm text-text-2">
          {t('glance.lastWorkout', { title: rowTitle(t, data.footnote), when: when(t, i18n.language, data.footnote) })}
        </p>
      )}
    </div>
  )
}

const RING_R = 32
const RING_C = 2 * Math.PI * RING_R

/** The day's goal as a ring in the app's colour; a meter to assistive tech. */
function GoalRing({ progress, label }: { readonly progress: number; readonly label: string }) {
  const pct = Math.round(Math.min(1, Math.max(0, progress)) * 100)
  return (
    <div role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} className="shrink-0">
      <svg aria-hidden viewBox="0 0 76 76" width={76} height={76}>
        <circle cx={38} cy={38} r={RING_R} fill="none" strokeWidth={8} className="stroke-well" />
        {pct > 0 && (
          <circle
            cx={38}
            cy={38}
            r={RING_R}
            fill="none"
            strokeWidth={8}
            strokeLinecap="round"
            strokeDasharray={`${(RING_C * pct) / 100} ${RING_C}`}
            transform="rotate(-90 38 38)"
            className="stroke-(--a)"
          />
        )}
      </svg>
    </div>
  )
}

function rowTitle(t: TFunction, row: GlanceAgendaRow): string {
  if (row.title !== '') return row.title
  return row.titleKey !== '' ? t(`glance.${row.titleKey}`, { defaultValue: t('glance.untitled') }) : t('glance.untitled')
}

/** "Today · 14:00", "Tomorrow", "Fri · 09:00" — the reader's day, from a local wall-time string. */
function when(t: TFunction, language: string, row: GlanceAgendaRow): string {
  const date = row.startLocal.slice(0, 10)
  const now = new Date()
  const day =
    date === civilDate(now)
      ? t('glance.when.today')
      : date === civilDate(addDays(now, 1))
        ? t('glance.when.tomorrow')
        : date === civilDate(addDays(now, -1))
          ? t('glance.when.yesterday')
          : new Date(`${date}T12:00`).toLocaleDateString(language, { weekday: 'short', day: 'numeric', month: 'short' })
  return row.allDay || row.startLocal.length < 16 ? day : `${day} · ${row.startLocal.slice(11, 16)}`
}

// `Intl.NumberFormat` throws on a malformed locale (i18next's `cimode`): fall back to the default.
function formatNumber(language: string, value: number): string {
  try {
    return new Intl.NumberFormat(language).format(value)
  } catch {
    return new Intl.NumberFormat().format(value)
  }
}
