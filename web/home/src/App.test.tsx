import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { proxy, resetProxy, device, automation, run, hub } = await import('./test/proxyMock')
const { renderWithQueryClient } = await import('@tenshu/app-kit/testing')
const { default: i18n } = await import('./i18n')
const { default: App } = await import('./App')

beforeEach(() => {
  resetProxy()
  proxy.runtimeLease.mockResolvedValue('held')
  void i18n.changeLanguage('en')
})

describe('App', () => {
  it('opens on the devices tab, with no lease banner while this tab holds the runtime', async () => {
    renderWithQueryClient(<App />)
    expect(screen.getByRole('heading', { name: 'Home', level: 1 })).toBeInTheDocument()
    expect(await screen.findByText('No devices yet')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Home' })).toHaveAttribute('aria-current', 'page')
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
