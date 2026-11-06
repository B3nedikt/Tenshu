// Coverage for the marketing header chrome: the Vision, Pricing, Roadmap, Docs and Support links +
// smart CTA are present, and the phone-only "…" menu is a <details>
// disclosure carrying the CTA (top), Docs and Support. jsdom doesn't
// evaluate Tailwind's responsive `hidden`/`md:` classes, so the desktop
// links and CTA are always in the DOM, and so is the menu's content (a
// closed <details> keeps its children; the browser hides them).
import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { MarketingHeader } from './MarketingHeader'
import { LandingCtaContext, type LandingCtaState } from './landingCtaContext'
import { DOCS_HREF, PRICING_HREF, ROADMAP_HREF, SUPPORT_HREF, VISION_HREF, type MarketingNavProps } from './marketingNav'

const FIRST_TIME: LandingCtaState = { kind: 'resolved', session: { kind: 'firstTime' } }

function renderHeader(props?: MarketingNavProps) {
  return render(
    <MemoryRouter>
      <LandingCtaContext.Provider value={FIRST_TIME}>
        <MarketingHeader {...props} />
      </LandingCtaContext.Provider>
    </MemoryRouter>,
  )
}

describe('MarketingHeader', () => {
  it('shows the Vision, Pricing, Roadmap, Docs and Support links and the smart CTA', () => {
    renderHeader()
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).getAllByRole('link').map((a) => a.textContent)).toEqual(['Vision', 'Pricing', 'Roadmap', 'Docs', 'Support'])
    expect(within(nav).getByRole('link', { name: 'Vision' })).toHaveAttribute('href', VISION_HREF)
    expect(VISION_HREF).toBe('/vision')
    // The roadmap is a static page: the language-neutral URL, which Caddy sends to a language.
    expect(within(nav).getByRole('link', { name: 'Roadmap' })).toHaveAttribute('href', ROADMAP_HREF)
    expect(ROADMAP_HREF).toBe('/roadmap')
    // Pricing is a static page the same way.
    expect(within(nav).getByRole('link', { name: 'Pricing' })).toHaveAttribute('href', PRICING_HREF)
    expect(PRICING_HREF).toBe('/pricing')
    expect(within(nav).getByRole('link', { name: 'Docs' })).toHaveAttribute('href', DOCS_HREF)
    // On the landing page Support is a plain link to a static page, not the current page.
    const support = within(nav).getByRole('link', { name: 'Support' })
    expect(support).toHaveAttribute('href', SUPPORT_HREF)
    expect(SUPPORT_HREF).toBe('/apps/support')
    expect(support).not.toHaveAttribute('aria-current')
    expect(screen.getAllByRole('link', { name: 'Get started' })[0]).toHaveAttribute('href', '/start')
  })

  it('marks Support current on a support page, in the bar and in the menu', () => {
    const { container } = renderHeader({ supportHref: '/apps/control/support', current: 'support' })
    const supports = screen.getAllByRole('link', { name: 'Support' })
    expect(supports).toHaveLength(2)
    for (const link of supports) {
      expect(link).toHaveAttribute('href', '/apps/control/support')
      expect(link).toHaveAttribute('aria-current', 'page')
    }
    expect(container.querySelectorAll('[aria-current]')).toHaveLength(2)
  })

  it('marks Vision current on the Vision page, in the bar and in the menu', () => {
    const { container } = renderHeader({ current: 'vision' })
    const visions = screen.getAllByRole('link', { name: 'Vision' })
    expect(visions).toHaveLength(2)
    for (const link of visions) expect(link).toHaveAttribute('aria-current', 'page')
    expect(container.querySelectorAll('[aria-current]')).toHaveLength(2)
  })

  it('marks Roadmap current on the roadmap, in the bar and in the menu', () => {
    const { container } = renderHeader({ current: 'roadmap' })
    const roadmaps = screen.getAllByRole('link', { name: 'Roadmap' })
    expect(roadmaps).toHaveLength(2)
    for (const link of roadmaps) expect(link).toHaveAttribute('aria-current', 'page')
    expect(container.querySelectorAll('[aria-current]')).toHaveLength(2)
  })

  it('collapses into a "…" <details> menu holding the CTA, Vision, Pricing, Roadmap, Docs and Support', () => {
    const { container } = renderHeader()
    const details = container.querySelector('details')!
    const summary = within(details).getByLabelText('Menu')
    expect(summary.tagName).toBe('SUMMARY')
    expect(details.open).toBe(false)

    // The menu's content is in the markup even when closed, so it works with JavaScript off
    // (the static support pages never hydrate): the CTA first, then Vision, Pricing, Roadmap, Docs and Support.
    const menu = within(details).getAllByRole('link').map((a) => a.textContent)
    expect(menu).toEqual(['Get started', 'Vision', 'Pricing', 'Roadmap', 'Docs', 'Support'])

    // With React running, opening it is tracked and a click on an item closes it again.
    details.open = true
    fireEvent(details, new Event('toggle'))
    expect(details.open).toBe(true)
    fireEvent.click(within(details).getByRole('link', { name: 'Docs' }))
    expect(details.open).toBe(false)
  })

  it('closes the open menu on Escape', () => {
    const { container } = renderHeader()
    const details = container.querySelector('details')!
    details.open = true
    fireEvent(details, new Event('toggle'))
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(details.open).toBe(false)
  })
})
