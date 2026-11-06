// Shared centred-card chrome for every onboarding screen. Implements
// the "centred card on large screens, full-bleed on mobile" wireframe
// principle from UX §9. Screens compose the shell; layout-level
// concerns (max width, vertical centring, logo strip) live here so each
// screen file stays focused on copy + interaction.
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { BrandLockup } from '../components/BrandLockup'

export function OnboardingShell({ children }: { readonly children: ReactNode }) {
  return (
    <div className="min-h-screen bg-surface text-text">
      <header className="border-b border-line">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-6 py-4">
          <Link to="/">
            <BrandLockup />
          </Link>
        </div>
      </header>
      <main className="mx-auto flex w-full max-w-2xl flex-col px-6 py-12">{children}</main>
    </div>
  )
}

export function OnboardingCard({ children }: { readonly children: ReactNode }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-8 shadow-sm">
      {children}
    </div>
  )
}

export function OnboardingTitle({ children }: { readonly children: ReactNode }) {
  return <h1 className="text-2xl font-semibold tracking-tight">{children}</h1>
}

export function OnboardingProse({ children }: { readonly children: ReactNode }) {
  return <div className="mt-4 space-y-3 text-sm text-text-2">{children}</div>
}

export function OnboardingActions({ children }: { readonly children: ReactNode }) {
  return <div className="mt-8 flex flex-wrap items-center gap-3">{children}</div>
}

export function PrimaryButton({
  children,
  onClick,
  type = 'button',
  disabled,
}: {
  readonly children: ReactNode
  readonly onClick?: () => void
  readonly type?: 'button' | 'submit'
  readonly disabled?: boolean
}) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:bg-surface-3"
    >
      {children}
    </button>
  )
}

export function SecondaryButton({
  children,
  onClick,
  type = 'button',
  disabled,
}: {
  readonly children: ReactNode
  readonly onClick?: () => void
  readonly type?: 'button' | 'submit'
  readonly disabled?: boolean
}) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className="rounded-md border border-line bg-surface px-4 py-2 text-sm font-medium text-text-2 shadow-sm transition-colors hover:bg-surface-2 disabled:cursor-not-allowed disabled:opacity-60"
    >
      {children}
    </button>
  )
}

export function LinkButton({
  children,
  onClick,
  type = 'button',
}: {
  readonly children: ReactNode
  readonly onClick?: () => void
  readonly type?: 'button' | 'submit'
}) {
  return (
    <button
      type={type}
      onClick={onClick}
      className="text-sm font-medium text-text-2 underline-offset-4 hover:text-text hover:underline"
    >
      {children}
    </button>
  )
}

export function Hint({ children }: { readonly children: ReactNode }) {
  return <p className="mt-3 text-xs text-text-2">{children}</p>
}
