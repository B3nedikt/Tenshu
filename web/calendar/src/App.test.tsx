import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from './i18n'
import { resetProxy } from './test/proxyMock'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { default: App } = await import('./App')

beforeEach(async () => {
  // The section is the URL path (useAppRoute): start every test at `/`, the default section.
  window.history.replaceState(null, '', '/')
  resetProxy()
  await i18n.changeLanguage('en')
})

describe('App', () => {
  it('renders its shell on the calendar, with no lease banner while this tab holds the runtime', async () => {
    renderWithQueryClient(<App />)
    expect(await screen.findByRole('grid', { name: 'Month' })).toBeInTheDocument()
    expect(screen.queryByText(/Tenshu is open in another tab/)).toBeNull()
  })

  it('names no headline of its own, and keeps its sections in a navigation landmark', async () => {
    // web-apps-layout.md §5 L1: the apex top bar names the app.
    renderWithQueryClient(<App />)
    await screen.findByRole('grid', { name: 'Month' })
    expect(screen.queryByRole('heading', { name: 'Calendar' })).toBeNull()
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull()
    const nav = screen.getByRole('navigation', { name: 'Calendar sections' })
    for (const name of ['Calendar', 'Calendars', 'Subscriptions']) {
      expect(within(nav).getByRole('button', { name })).toBeInTheDocument()
    }
    expect(within(nav).getByRole('button', { name: 'Calendar' })).toHaveAttribute('aria-current', 'page')
    // Import & export sits at the sidebar's foot, still inside the one navigation landmark.
    expect(within(nav).getByRole('button', { name: 'Import & export' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Import & export' })).toBeInTheDocument()
  })

  // web-apps-layout.md §3.4 / §5 L2: the section is the URL path; `/` is the calendar.
  describe('routes', () => {
    const nav = () => screen.getByRole('navigation', { name: 'Calendar sections' })

    it('opens the section its path names, footer sections included', async () => {
      window.history.replaceState(null, '', '/transfer')
      renderWithQueryClient(<App />)
      expect(within(nav()).getByRole('button', { name: 'Import & export' })).toHaveAttribute('aria-current', 'page')
      expect(screen.queryByRole('grid', { name: 'Month' })).toBeNull()
    })

    it('puts the clicked section in the path, and the calendar at /', async () => {
      renderWithQueryClient(<App />)
      await screen.findByRole('grid', { name: 'Month' })
      await userEvent.click(within(nav()).getByRole('button', { name: 'Calendars' }))
      expect(window.location.pathname).toBe('/calendars')
      await userEvent.click(within(nav()).getByRole('button', { name: 'Subscriptions' }))
      expect(window.location.pathname).toBe('/subscriptions')
      await userEvent.click(within(nav()).getByRole('button', { name: 'Import & export' }))
      expect(window.location.pathname).toBe('/transfer')
      await userEvent.click(within(nav()).getByRole('button', { name: 'Calendar' }))
      expect(window.location.pathname).toBe('/')
      expect(await screen.findByRole('grid', { name: 'Month' })).toBeInTheDocument()
    })

    it('opens the calendar on a path that is not a section', async () => {
      window.history.replaceState(null, '', '/nonsense')
      renderWithQueryClient(<App />)
      expect(await screen.findByRole('grid', { name: 'Month' })).toBeInTheDocument()
      expect(within(nav()).getByRole('button', { name: 'Calendar' })).toHaveAttribute('aria-current', 'page')
    })
  })

  it('switches between its screens', async () => {
    renderWithQueryClient(<App />)
    await userEvent.click(screen.getByRole('button', { name: 'Subscriptions' }))
    expect(await screen.findByRole('heading', { name: 'Subscribed calendars' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Import & export' }))
    expect(await screen.findByRole('heading', { name: 'Import an .ics file' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Calendars' }))
    expect(await screen.findByRole('heading', { name: 'Your calendars' })).toBeInTheDocument()
  })

  it('speaks German', async () => {
    await i18n.changeLanguage('de')
    renderWithQueryClient(<App />)
    expect(screen.getByRole('navigation', { name: 'Kalender-Bereiche' })).toBeInTheDocument()
    expect(await screen.findByRole('tab', { name: 'Monat' })).toBeInTheDocument()
  })
})
