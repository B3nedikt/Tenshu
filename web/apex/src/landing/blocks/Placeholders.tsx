// Shared placeholder primitives for the apps + hardware landing
// sections (web-about-tenshu-public.md §8b / §9b). These stand in for
// assets that don't exist yet: real product screenshots (Apps) and
// device photography (Hardware). Both are intentionally minimal and
// obviously-unfinished so nobody mistakes them for the real thing —
// swap them for real imagery as it lands.
import { useTranslation } from 'react-i18next'

// A muted tile that reads clearly as "image goes here." Used as the
// screenshot thumbnails in the app galleries and as the device-photo
// stand-ins in the Hardware section. Defaults to a portrait phone-ish
// aspect; pass `aspectClassName` for landscape (desktop UI, hardware).
export function ScreenshotPlaceholder({
  label,
  aspectClassName = 'aspect-[3/4]',
}: {
  readonly label?: string
  readonly aspectClassName?: string
}) {
  const { t } = useTranslation('landing')
  const resolvedLabel = label ?? t('placeholders.screenshotDefaultLabel')
  return (
    <div
      role="img"
      aria-label={t('placeholders.screenshotAriaLabel', { label: resolvedLabel })}
      className={`flex ${aspectClassName} w-full flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-line bg-surface-2 text-text-3`}
    >
      <svg
        width="28"
        height="28"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.75"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <rect x="3" y="3" width="18" height="18" rx="2" />
        <circle cx="8.5" cy="8.5" r="1.5" />
        <path d="M21 15l-5-5L5 21" />
      </svg>
      <span className="text-xs font-medium">{resolvedLabel}</span>
    </div>
  )
}

// Small status pill with a colored dot. `available` = shipping today,
// `progress` = under active development, `planning` = designed but not
// started. Kept in the site's restrained grayscale, the dot carries
// the only color so the signal reads at a glance without shouting.
export function StatusBadge({
  status,
}: {
  readonly status: 'available' | 'progress' | 'planning'
}) {
  const { t } = useTranslation('landing')
  const dotClass = {
    available: 'bg-success',
    progress: 'bg-warning',
    planning: 'bg-text-3',
  }[status]
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-line px-2.5 py-0.5 text-xs font-medium text-text-2">
      <span className={`h-1.5 w-1.5 rounded-full ${dotClass}`} aria-hidden="true" />
      {t(`placeholders.status.${status}`)}
    </span>
  )
}
