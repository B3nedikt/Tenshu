// Shared section shell for the eight landing blocks. Keeps spacing,
// max-width, and alternating-background rhythm in one place so each
// block file stays focused on its copy and visual.
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

// In-page anchor link used between sections. Renders an optional
// label above a bouncing chevron — the dominant CTA on the hero
// ("Learn more" + chevron), the bridge after the big-tech problem
// section ("How it should be" + chevron), and the bare chevron between
// every subsequent section. Sized for prominence (it's the navigation
// thread between full-screen pages); label + chevron share
// `currentColor` so hover dims them together. The chevron sits a little
// quieter via `opacity-70`. Smooth scroll comes from
// `html { scroll-behavior: smooth }` in `index.css`
// (gated on prefers-reduced-motion).
export function NextSectionLink({
  to,
  label,
  ariaLabel,
}: {
  readonly to: string
  readonly label?: string
  readonly ariaLabel?: string
}) {
  const { t } = useTranslation('landing')
  return (
    <a
      href={to}
      aria-label={ariaLabel ?? (label !== undefined ? undefined : t('section.nextSectionAriaLabel'))}
      className="inline-flex flex-col items-center gap-3 text-lg font-semibold text-text transition-colors hover:text-text-2 sm:text-xl"
    >
      {label !== undefined && <span>{label}</span>}
      <svg
        width="32"
        height="32"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className="opacity-70 motion-safe:animate-bounce"
        aria-hidden="true"
      >
        <polyline points="6 9 12 15 18 9" />
      </svg>
    </a>
  )
}

export function Section({
  id,
  tone = 'plain',
  divider = true,
  screen = false,
  children,
}: {
  readonly id?: string
  readonly tone?: 'plain' | 'tinted'
  readonly divider?: boolean
  // When true the section fills the viewport below the fixed header
  // (the hero uses this) and its inner wrapper becomes a flex column so
  // children can grow to centre vertically and pin a footer affordance
  // to the bottom. The `3.5rem` offset matches the header's `h-14`.
  readonly screen?: boolean
  readonly children: ReactNode
}) {
  const bg =
    tone === 'tinted'
      ? 'bg-surface-2'
      : 'bg-surface'
  const border = divider ? 'border-b border-line' : ''
  const sectionLayout = screen ? 'flex min-h-[calc(100svh_-_3.5rem)]' : ''
  const innerLayout = screen ? 'flex flex-1 flex-col py-10' : 'py-16 sm:py-20'
  return (
    <section id={id} className={`${bg} ${border} ${sectionLayout}`}>
      <div className={`mx-auto w-full max-w-5xl px-6 ${innerLayout}`}>{children}</div>
    </section>
  )
}
