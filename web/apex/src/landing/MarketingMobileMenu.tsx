// Phone-only nav collapse (web-apex-landing.md §5). On narrow screens
// the header is just the brand on the left and this "…" button on the
// right; tapping it drops down the smart CTA (the primary action, so it
// sits at the top) and the Vision, Pricing, Roadmap, Docs and Support links below. Hidden at `md`
// and up, where the header lays the links out in the centre and the CTA
// on the right.
//
// A native <details>/<summary> disclosure, so it opens and closes with
// JavaScript off — the static support pages render this component to HTML
// and never hydrate it. With React running, the effects below add what
// <details> lacks: Escape, a click outside, and a click on an item close it.
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { LandingCta } from './LandingCta'
import { DOCS_HREF, PRICING_HREF, ROADMAP_HREF, SUPPORT_HREF, VISION_HREF, type MarketingNavProps } from './marketingNav'

const ITEM = 'rounded-md px-3 py-2 text-sm font-medium transition-colors hover:bg-surface-2 hover:text-text'

export function MarketingMobileMenu({ supportHref = SUPPORT_HREF, current }: MarketingNavProps = {}) {
  const { t } = useTranslation('landing')
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDetailsElement>(null)

  // Close on Escape or a click/tap outside the menu.
  useEffect(() => {
    if (!open) return
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false)
    }
    function onPointerDown(e: MouseEvent) {
      if (ref.current !== null && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('keydown', onKeyDown)
    document.addEventListener('mousedown', onPointerDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('mousedown', onPointerDown)
    }
  }, [open])

  return (
    <details
      ref={ref}
      open={open}
      onToggle={(e) => setOpen(e.currentTarget.open)}
      className="relative"
    >
      <summary
        aria-label={t('header.menuAriaLabel')}
        className="inline-flex h-9 w-9 cursor-pointer list-none items-center justify-center rounded-md text-text-2 transition-colors hover:bg-surface-2 hover:text-text [&::-webkit-details-marker]:hidden"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <circle cx="5" cy="12" r="1.75" />
          <circle cx="12" cy="12" r="1.75" />
          <circle cx="19" cy="12" r="1.75" />
        </svg>
      </summary>
      <div
        id="marketing-mobile-menu"
        onClick={() => setOpen(false)}
        className="absolute right-0 mt-2 flex w-52 flex-col gap-1 rounded-lg border border-line bg-surface p-2 shadow-lg"
      >
        <LandingCta slot="header" />
        <a
          href={VISION_HREF}
          aria-current={current === 'vision' ? 'page' : undefined}
          className={`${ITEM} ${current === 'vision' ? 'bg-surface-2 text-text' : 'text-text-2'}`}
        >
          {t('header.vision')}
        </a>
        <a
          href={PRICING_HREF}
          aria-current={current === 'pricing' ? 'page' : undefined}
          className={`${ITEM} ${current === 'pricing' ? 'bg-surface-2 text-text' : 'text-text-2'}`}
        >
          {t('header.pricing')}
        </a>
        <a
          href={ROADMAP_HREF}
          aria-current={current === 'roadmap' ? 'page' : undefined}
          className={`${ITEM} ${current === 'roadmap' ? 'bg-surface-2 text-text' : 'text-text-2'}`}
        >
          {t('header.roadmap')}
        </a>
        <a href={DOCS_HREF} className={`${ITEM} text-text-2`}>
          {t('header.docs')}
        </a>
        <a
          href={supportHref}
          aria-current={current === 'support' ? 'page' : undefined}
          className={`${ITEM} ${current === 'support' ? 'bg-surface-2 text-text' : 'text-text-2'}`}
        >
          {t('header.support')}
        </a>
      </div>
    </details>
  )
}
