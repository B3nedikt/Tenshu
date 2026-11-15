import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { AppLayout } from './AppLayout'

type Section = 'home' | 'files' | 'settings'
const NAV = [
  { id: 'home', label: 'Home' },
  { id: 'files', label: 'Files' },
] as const
const FOOTER = [{ id: 'settings', label: 'Settings' }] as const

function renderLayout(
  props: { active?: Section; pane?: React.ReactNode; onNavigate?: (id: Section) => void; onPaneClose?: () => void } = {},
) {
  return render(
    <AppLayout<Section>
      navLabel="Drive sections"
      nav={NAV}
      navFooter={FOOTER}
      active={props.active ?? 'files'}
      onNavigate={props.onNavigate ?? (() => {})}
      banners={<div role="status">Offline</div>}
      pane={props.pane}
      onPaneClose={props.onPaneClose}
    >
      <p>Content</p>
    </AppLayout>,
  )
}

describe('AppLayout', () => {
  it('renders no headline — the apex top bar names the app', () => {
    renderLayout()
    expect(screen.queryByRole('heading')).toBeNull()
  })

  it('marks the active section and every section is reachable once', () => {
    renderLayout({ active: 'files' })
    const nav = screen.getByRole('navigation', { name: 'Drive sections' })
    expect(within(nav).getByRole('button', { name: 'Files' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('button', { name: 'Home' })).not.toHaveAttribute('aria-current')
    // Footer sections are in the same landmark, rendered once (not duplicated for narrow).
    expect(within(nav).getByRole('button', { name: 'Settings' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Settings' })).toHaveLength(1)
  })

  it('navigates on click, including footer sections', async () => {
    const onNavigate = vi.fn()
    renderLayout({ onNavigate })
    await userEvent.click(screen.getByRole('button', { name: 'Home' }))
    await userEvent.click(screen.getByRole('button', { name: 'Settings' }))
    expect(onNavigate.mock.calls).toEqual([['home'], ['settings']])
  })

  it('shows banners above the content, inside main', () => {
    renderLayout()
    const main = screen.getByRole('main')
    expect(within(main).getByRole('status')).toHaveTextContent('Offline')
    expect(within(main).getByText('Content')).toBeInTheDocument()
  })

  it('renders the pane only when given one', () => {
    const { unmount } = renderLayout()
    expect(screen.queryByRole('complementary', { name: 'Details' })).toBeNull()
    unmount()
    renderLayout({ pane: <p>Lake.jpg</p> })
    expect(screen.getByRole('complementary', { name: 'Details' })).toHaveTextContent('Lake.jpg')
  })

  it('closes the pane on Escape inside it, and on the narrow scrim', async () => {
    const onPaneClose = vi.fn()
    renderLayout({ pane: <button type="button">Share</button>, onPaneClose })
    screen.getByRole('button', { name: 'Share' }).focus()
    await userEvent.keyboard('{Escape}')
    expect(onPaneClose).toHaveBeenCalledTimes(1)
    // The scrim is pointer-only: hidden from assistive tech, so no extra "button" in the tree.
    const scrim = document.querySelector('[aria-hidden].fixed.inset-0') as HTMLElement
    await userEvent.click(scrim)
    expect(onPaneClose).toHaveBeenCalledTimes(2)
  })

  it('draws a group heading above an item, inside the same landmark', () => {
    render(
      <AppLayout<'a' | 'b'>
        navLabel="X sections"
        nav={[{ id: 'a', label: 'Library' }, { id: 'b', label: 'Years', heading: 'Collections' }]}
        active="a"
        onNavigate={() => {}}
      >
        <p>Content</p>
      </AppLayout>,
    )
    const nav = screen.getByRole('navigation', { name: 'X sections' })
    expect(within(nav).getByText('Collections')).toBeInTheDocument()
    // A label, not a section: still exactly two buttons.
    expect(within(nav).getAllByRole('button')).toHaveLength(2)
  })

  it('has no width cap on the content', () => {
    renderLayout()
    expect(screen.getByRole('main').className).not.toMatch(/max-w-/)
  })
})
