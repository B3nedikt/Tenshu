// The Vision page (/vision): the one-sentence vision, the four problems, the three technologies,
// and the header marking Vision as the current page.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { VisionPage } from './VisionPage'
import i18n from '../i18n'

function renderPage() {
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  return render(
    <MemoryRouter initialEntries={['/vision']}>
      <VisionPage />
    </MemoryRouter>,
  )
}

describe('VisionPage', () => {
  afterEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('leads with the vision, then the four problems and the three technologies', () => {
    renderPage()
    expect(
      screen.getByRole('heading', { level: 1, name: 'Tenshu aims to put you in full control of your digital life.' }),
    ).toBeInTheDocument()
    const sections = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    expect(sections).toEqual([
      'Privacy',
      'Security',
      'Interoperability',
      'Sovereignty',
      'What Tenshu does differently.',
      'Privacy by design.',
    ])
    const tech = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    expect(tech).toEqual(['The Control App', 'The Tenshu IDL', 'Tenshu Rei'])
    expect(screen.getByText('Semantic interfaces at its core')).toBeInTheDocument()
    expect(screen.getByText('The open trust layer')).toBeInTheDocument()
    // The page states its case; it carries no CTA of its own (the header has one).
    expect(screen.queryByRole('link', { name: 'Write to me' })).not.toBeInTheDocument()
  })

  it('marks Vision as the current page in the header', () => {
    renderPage()
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Vision' })).toHaveAttribute('aria-current', 'page')
  })

  it('renders in German', async () => {
    await i18n.changeLanguage('de')
    renderPage()
    expect(screen.getByRole('heading', { level: 2, name: 'Souveränität' })).toBeInTheDocument()
  })
})
