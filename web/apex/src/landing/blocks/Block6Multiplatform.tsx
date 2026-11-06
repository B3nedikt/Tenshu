// Block 6 — Multiplatform. Copy: web-about-tenshu-public.md §8.
// Tenshu is built on Kotlin Multiplatform: one end-to-end-encrypted
// core, native clients per platform. All four platforms are part of the
// first release, so the cards carry no availability badge (decided
// 2026-10-16: per-card status read as confusing).
import { useTranslation } from 'react-i18next'
import { Section } from './Section'

// Data-driven copy pattern (ADR 0016 / agent.md §19): the static array
// carries IDs only — strings come from the catalogue at render time via
// `t('block6.platforms.<id>.{name,detail,blurb}')`. Adding a platform
// = one entry here + three keys in `landing.json`.
type PlatformId = 'android' | 'desktop' | 'web' | 'ios'

type DeviceKind = 'phone' | 'laptop' | 'browser'

interface Platform {
  readonly id: PlatformId
  readonly device: DeviceKind
  /** Store page to install from; the card shows `block6.platforms.<id>.store` as its link. */
  readonly storeUrl?: string
}

// On Android you start with Control: it holds the realm, and every other app joins through it.
// iOS gets its link once Control has an App Store id (`TENSHU_CONTROL_APP_STORE_ID`, unset so far).
const CONTROL_PLAY_URL = 'https://play.google.com/store/apps/details?id=app.tenshu.control.android'

// Phones first, then computer, then browser.
const PLATFORMS: readonly Platform[] = [
  { id: 'android', device: 'phone', storeUrl: CONTROL_PLAY_URL },
  { id: 'ios', device: 'phone' },
  { id: 'desktop', device: 'laptop' },
  { id: 'web', device: 'browser' },
]

export function Block6Multiplatform() {
  const { t } = useTranslation('landing')
  return (
    <Section id="multiplatform">
      <div className="mx-auto max-w-3xl text-center">
        <h2 className="text-3xl font-semibold tracking-tight">{t('block6.heading')}</h2>
        <p className="mt-6 text-lg text-text-2">{t('block6.lede')}</p>
      </div>
      <div className="mx-auto mt-12 grid max-w-4xl gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {PLATFORMS.map(platform => (
          <PlatformCard key={platform.id} platform={platform} />
        ))}
      </div>
    </Section>
  )
}

function PlatformCard({ platform }: { readonly platform: Platform }) {
  const { t } = useTranslation('landing')
  return (
    <div className="flex flex-col rounded-xl border border-line bg-surface p-5">
      <DeviceIcon kind={platform.device} />
      <h3 className="mt-4 text-base font-semibold text-text">
        {t(`block6.platforms.${platform.id}.name`)}
      </h3>
      <p className="mt-0.5 text-xs uppercase tracking-wide text-text-2">
        {t(`block6.platforms.${platform.id}.detail`)}
      </p>
      <p className="mt-3 flex-1 text-sm text-text-2">
        {t(`block6.platforms.${platform.id}.blurb`)}
      </p>
      {platform.storeUrl && (
        <a
          href={platform.storeUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="mt-4 text-sm font-medium text-text underline-offset-4 hover:underline"
        >
          {t(`block6.platforms.${platform.id}.store`)}
        </a>
      )}
    </div>
  )
}

// Generic device outlines, drawn here in the site's single-stroke style. ⚠ Never
// swap in platform logos (Apple, Windows, …): their trademark rules forbid it on
// a third-party site, and a mix of allowed and missing logos reads worse than none.
function DeviceIcon({ kind }: { readonly kind: DeviceKind }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="28"
      height="28"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="text-text"
    >
      {kind === 'phone' && (
        <>
          <rect x="6.5" y="2.5" width="11" height="19" rx="2.5" />
          <path d="M10.5 18.5h3" />
        </>
      )}
      {kind === 'laptop' && (
        <>
          <rect x="4" y="5" width="16" height="11" rx="1.5" />
          <path d="M2 19.5h20" />
        </>
      )}
      {kind === 'browser' && (
        <>
          <rect x="2.5" y="4" width="19" height="16" rx="2" />
          <path d="M2.5 8.5h19" />
          <path d="M5.5 6.25h.01M7.75 6.25h.01M10 6.25h.01" />
        </>
      )}
    </svg>
  )
}
