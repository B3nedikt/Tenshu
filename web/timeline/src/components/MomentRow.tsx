// One feed row — the web's `NamedMoment`, without the photo cluster (photos come later, §Web scope
// 3) and without the live "now" tag (a tab has no capture state, 4b). Rendered exactly as
// delivered: ⚠ the SDK decides how rows fold (run A: one row per stored moment; after the
// projection module: consecutive visits merged, station stops collapsed), never this component.
import { useTranslation } from 'react-i18next'
import type { TimelineDay, TimelineDayMoment } from '@tenshu/sdk/timeline'
import { dayHeading, momentMeta, momentPlaceLabel, momentTitle, timeText } from '../lib/format'
import { useUnits } from '../lib/units'
import type { Open } from '../lib/nav'

const KIND_GLYPH: Record<string, string> = {
  Visit: '●',
  Movement: '→',
  Activity: '▲',
  Event: '◆',
  Quiet: '○',
}

export function MomentRow({ moment, onOpen }: { moment: TimelineDayMoment; onOpen: Open }) {
  const { t, i18n } = useTranslation()
  const units = useUnits()
  const label = momentPlaceLabel(t, moment)
  const meta = momentMeta(t, moment, units)
  // A visit's secondary line: the coordinate of an unnamed place, else its city.
  const secondary = label?.secondary ?? ''
  return (
    <li>
      <button
        type="button"
        onClick={() => onOpen({ kind: 'moment', id: moment.momentId })}
        data-kind={moment.kind}
        className="flex w-full items-start gap-3 rounded-lg px-2 py-2 text-left hover:bg-surface-2"
      >
        <span className="w-14 shrink-0 pt-0.5 text-xs tabular-nums text-text-2">{timeText(i18n.language, moment.timestampMs)}</span>
        <span aria-hidden className="w-4 shrink-0 pt-0.5 text-center text-xs text-primary">
          {KIND_GLYPH[moment.kind] ?? '·'}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium text-text">{momentTitle(t, moment)}</span>
          {secondary && <span className="block truncate text-xs text-text-2">{secondary}</span>}
          {meta && <span className="block text-xs text-text-2">{meta}</span>}
        </span>
      </button>
    </li>
  )
}

// Days as delivered: newest day first, moments oldest first within a day (the SDK's order — not
// re-sorted here, so a folded or unfolded feed renders the same way it arrives).
export function DayList({ days, onOpen }: { days: readonly TimelineDay[]; onOpen: Open }) {
  const { i18n } = useTranslation()
  return (
    <div className="space-y-6">
      {days.map((day) => (
        <section key={day.date} aria-label={dayHeading(i18n.language, day.date)}>
          <h2 className="mb-1 text-sm font-semibold text-text">{dayHeading(i18n.language, day.date)}</h2>
          <ul className="space-y-0.5">
            {day.moments.map((m) => (
              <MomentRow key={m.momentId} moment={m} onOpen={onOpen} />
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}
