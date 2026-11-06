// Smart CTA per web-apex-landing.md §4. Reads the shared probe result
// from `LandingCtaProvider` and renders the right label + destination
// for the visitor's local IndexedDB state. Used in Block 1, Block 8,
// and the marketing header — they all share state via context, no
// per-instance re-probe.
//
// `slot` selects which label set + secondary affordance to render:
// - `hero`     → Block 1 above-the-fold CTA, plain primary button
// - `signup`   → Block 8 sign-up card, with the "I already have Tenshu"
//                fallback link visible only for empty-IDB visitors
// - `header`   → top-right marketing-header chip; quieter visual weight
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { useLandingCta } from './useLandingCta'
import { landingCtaShape } from './landingCtaShape'
import { signupOpen } from '../lib/signupGate'

export function LandingCta({ slot }: { readonly slot: 'hero' | 'signup' | 'header' }) {
  const { t } = useTranslation('landing')
  const state = useLandingCta()
  const cta = landingCtaShape(state, signupOpen())
  const loading = state.kind === 'loading'

  if (slot === 'header') {
    return (
      <Link
        to={cta.to}
        aria-disabled={loading}
        className="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
      >
        {t(cta.label)}
      </Link>
    )
  }

  return (
    <div
      className={`flex flex-wrap items-center gap-3 ${slot === 'hero' ? 'justify-center' : ''}`}
    >
      <PrimaryCta to={cta.to} disabled={loading} size={slot === 'hero' ? 'lg' : 'md'}>
        {t(cta.label)}
      </PrimaryCta>
      {slot === 'signup' && cta.secondary !== null && (
        <Link
          to={cta.secondary.to}
          className="text-sm font-medium text-text-2 underline-offset-4 hover:text-text hover:underline"
        >
          {t(cta.secondary.label)}
        </Link>
      )}
    </div>
  )
}

function PrimaryCta({
  to,
  children,
  disabled,
  size = 'md',
}: {
  readonly to: string
  readonly children: ReactNode
  readonly disabled: boolean
  // `lg` is the prominent above-the-fold hero button; `md` is the
  // standard size used by the sign-up card.
  readonly size?: 'md' | 'lg'
}) {
  const sizeCls = size === 'lg' ? 'px-8 py-4 text-lg' : 'px-5 py-2.5 text-sm'
  const cls = `inline-flex items-center rounded-md bg-primary ${sizeCls} font-semibold text-on-primary shadow-sm transition-colors hover:bg-primary/90`
  if (disabled) {
    return (
      <span aria-disabled="true" className={`${cls} cursor-wait opacity-70`}>
        {children}
      </span>
    )
  }
  return (
    <Link to={to} className={cls}>
      {children}
    </Link>
  )
}
