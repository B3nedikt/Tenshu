import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { proxy, resetProxy, device, automation, run, hub } = await import('./test/proxyMock')
const { renderWithQueryClient } = await import('@tenshu/app-kit/testing')
const { default: i18n } = await import('./i18n')
const { default: App } = await import('./App')

beforeEach(() => {
  // The section is the URL path (useAppRoute): start every test at `/`, the default section.
  window.history.replaceState(null, '', '/')
  resetProxy()
  proxy.runtimeLease.mockResolvedValue('held')
  void i18n.changeLanguage('en')
})

describe('App', () => {
  it('opens on the devices tab, with no lease banner while this tab holds the runtime', async () => {
    renderWithQueryClient(<App />)
    expect(await screen.findByText('No devices yet')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Home' })).toHaveAttribute('aria-current', 'page')
  })

  // web-apps-layout.md §3.1/§5 L1: the apex top bar names the app, so no headline does; the
  // sections sit in the sidebar's navigation landmark.
  it('renders no headline naming the app, and its sections in a navigation landmark', async () => {
    renderWithQueryClient(<App />)
    await screen.findByText('No devices yet')
    expect(screen.queryAllByRole('heading', { level: 1 })).toEqual([])
    expect(screen.queryByRole('heading', { name: 'Home' })).toBeNull()
    const nav = screen.getByRole('navigation', { name: 'Home sections' })
    expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual(['Home', 'Automations', 'Activity'])
  })

  // web-apps-layout.md §3.4 / §5 L2: the section is the URL path; `/` is the devices tab.
  describe('routes', () => {
    const nav = () => screen.getByRole('navigation', { name: 'Home sections' })

    it('opens the section its path names', async () => {
      window.history.replaceState(null, '', '/activity')
      renderWithQueryClient(<App />)
      expect(within(nav()).getByRole('button', { name: 'Activity' })).toHaveAttribute('aria-current', 'page')
      expect(screen.queryByText('No devices yet')).toBeNull()
    })

    it('puts the clicked section in the path, and the devices tab at /', async () => {
      const user = userEvent.setup()
      renderWithQueryClient(<App />)
      await screen.findByText('No devices yet')
      await user.click(within(nav()).getByRole('button', { name: 'Automations' }))
      expect(window.location.pathname).toBe('/automations')
      await user.click(within(nav()).getByRole('button', { name: 'Activity' }))
      expect(window.location.pathname).toBe('/activity')
      await user.click(within(nav()).getByRole('button', { name: 'Home' }))
      expect(window.location.pathname).toBe('/')
      expect(await screen.findByText('No devices yet')).toBeInTheDocument()
    })

    it('opens the devices tab on a path that is not a section', async () => {
      window.history.replaceState(null, '', '/nonsense')
      renderWithQueryClient(<App />)
      expect(await screen.findByText('No devices yet')).toBeInTheDocument()
      expect(within(nav()).getByRole('button', { name: 'Home' })).toHaveAttribute('aria-current', 'page')
    })
  })

  it('moves between the three tabs; the streams are subscribed once, at the root', async () => {
    const user = userEvent.setup()
    proxy.automations.mockResolvedValue({ ok: true, automations: [automation('a', { name: 'Evening' })] })
    proxy.activityPage.mockResolvedValue({ ok: true, events: [run('f1', Date.now())], nextCursor: null })
    renderWithQueryClient(<App />)
    await user.click(screen.getByRole('button', { name: 'Automations' }))
    expect(await screen.findByText('Evening')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Activity' }))
    expect(await screen.findByTestId('activity-row')).toBeInTheDocument()
    expect(proxy.subscribeDevices).toHaveBeenCalledTimes(1)
    expect(proxy.subscribeAutomations).toHaveBeenCalledTimes(1)
    expect(proxy.subscribeHubStorage).toHaveBeenCalledTimes(1)
  })

  // ⚠⚠ READ-ONLY: on every tab, with data, the only buttons are navigation, the activity filter and
  // nothing else — no switch, slider, menu, or a word that suggests control.
  it('offers no control anywhere', async () => {
    const user = userEvent.setup()
    proxy.devices.mockResolvedValue({ ok: true, devices: [device('lamp')], rooms: [] })
    proxy.hubStorage.mockResolvedValue({ ok: true, hub: hub({ freshAsOfMs: Date.now() }) })
    proxy.automations.mockResolvedValue({ ok: true, automations: [automation('a')] })
    proxy.activityPage.mockResolvedValue({ ok: true, events: [run('f1', Date.now())], nextCursor: null })
    renderWithQueryClient(<App />)
    const NAV = ['Home', 'Automations', 'Activity']
    const FILTERS = ['All', 'Devices', 'Automations', 'Voice']
    const forbidden = /^(add|edit|remove|delete|run( now)?|pair|turn on|turn off|save|create|new)\b/i
    const check = () => {
      expect(screen.queryAllByRole('switch')).toEqual([])
      expect(screen.queryAllByRole('checkbox')).toEqual([])
      expect(screen.queryAllByRole('slider')).toEqual([])
      expect(screen.queryAllByRole('textbox')).toEqual([])
      for (const b of screen.getAllByRole('button')) {
        expect([...NAV, ...FILTERS], b.textContent ?? '').toContain(b.textContent)
        expect(b.textContent).not.toMatch(forbidden)
      }
    }
    await screen.findByTestId('device-row')
    check()
    await user.click(screen.getByRole('button', { name: 'Automations' }))
    await screen.findByTestId('automation-row')
    check()
    await user.click(screen.getByRole('button', { name: 'Activity' }))
    await waitFor(() => expect(screen.getByTestId('activity-row')).toBeInTheDocument())
    check()
  })
})
