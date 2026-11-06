// The Tenshu chrome lockup: "Tenshu" wordmark + the primary Outline cat
// mark (ADR 0004), used in every navbar/header so the brand reads
// identically across marketing, onboarding, and the control plane.
//
// The mark sits to the right of the wordmark (where the old "apex"
// label used to be). It is decorative — the visible "Tenshu" text is
// the accessible name, so the mark is wrapped aria-hidden to avoid a
// doubled "Tenshu Tenshu" announcement. Renders no link; each shell
// keeps its own <Link> wrapper and destination.
import { MarkOutline } from './marks/Outline'

export function BrandLockup({ className }: { readonly className?: string }) {
  return (
    <span className={`flex items-center gap-2.5 ${className ?? ''}`}>
      <span className="text-base font-semibold tracking-tight">Tenshu</span>
      <span aria-hidden="true" className="flex">
        <MarkOutline className="h-8 w-8" />
      </span>
    </span>
  )
}
