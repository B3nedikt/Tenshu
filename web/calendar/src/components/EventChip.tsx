// One occurrence as a clickable label: colour, start time (unless it continues from the day
// before), title, and the unknown-zone mark.
import { useTranslation } from 'react-i18next'
import type { Placed } from '../lib/placement'
import { formatTime } from '../lib/dates'
import { ColourDot } from './ui'

export function EventChip({
  placed,
  colour,
  onOpen,
  compact = false,
}: {
  placed: Placed
  colour: string
  onOpen: () => void
  compact?: boolean
}) {
  const { t } = useTranslation()
  const o = placed.occurrence
  const time = !placed.allDay && !placed.continuesBefore ? formatTime(placed.start) : null
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation()
        onOpen()
      }}
      data-event={o.eventId}
      className={`flex w-full min-w-0 items-center gap-1.5 rounded px-1.5 text-left text-xs text-text hover:bg-surface-3 ${compact ? 'py-0' : 'py-0.5'}`}
      style={placed.allDay ? { backgroundColor: `${colour}22` } : undefined}
    >
      <ColourDot colour={colour} size={8} />
      {time && <span className="shrink-0 tabular-nums text-text-3">{time}</span>}
      <span className="truncate">{o.title || t('event.untitled')}</span>
      {o.zoneUnresolved && <ZoneMark />}
    </button>
  )
}

export function ZoneMark() {
  const { t } = useTranslation()
  return (
    <span className="shrink-0 text-warning" role="img" aria-label={t('zone.unresolvedShort')} title={t('zone.unresolvedShort')}>
      ⚠
    </span>
  )
}
