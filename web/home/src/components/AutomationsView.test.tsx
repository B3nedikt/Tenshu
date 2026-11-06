import { act, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, resetProxy, failure, automation, device, streams } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { AutomationsView } = await import('./AutomationsView')

beforeEach(() => resetProxy())

function rows() {
  return screen.getAllByTestId('automation-row')
}

describe('AutomationsView', () => {
  it('lists what the iOS tab lists: parsed time-of-day and sun routines, nothing else', async () => {
    proxy.automations.mockResolvedValue({
      ok: true,
      automations: [
        automation('time'),
        automation('sun', { program: { trigger: { type: 'sun', event: 'sunset', offsetMinutes: -30 }, conditions: [], actions: [] } }),
        automation('device', {
          program: { trigger: { type: 'deviceEvent', deviceId: 'd', match: { field: 'motion', op: 'eq', value: '1' } }, conditions: [], actions: [] },
        }),
        automation('geo', { program: { trigger: { type: 'geofence', placeId: 'p', direction: 'arrive' }, conditions: [], actions: [] } }),
        automation('voice', { program: { trigger: { type: 'voicePhrase', phrase: 'lights' }, conditions: [], actions: [] } }),
        automation('newer', { programStatus: 'unsupportedVersion', program: null }),
        automation('broken', { programStatus: 'malformed', program: null }),
      ],
    })
    renderWithProviders(<AutomationsView />)
    await waitFor(() => expect(rows().map((r) => r.getAttribute('data-id'))).toEqual(['time', 'sun']))
  })

  it('summarises trigger · first action with household names, and reports enabled as a label', async () => {
    proxy.devices.mockResolvedValue({ ok: true, devices: [device('lamp', { name: 'Sofa lamp' })], rooms: [] })
    proxy.automations.mockResolvedValue({
      ok: true,
      automations: [
        automation('a', { name: 'Evening', enabled: true }),
        automation('b', {
          name: 'Dusk',
          enabled: false,
          program: {
            trigger: { type: 'sun', event: 'sunset', offsetMinutes: -30 },
            conditions: [],
            actions: [
              { type: 'setLevel', deviceId: 'gone', percent: 0 },
              { type: 'setPower', deviceId: 'lamp', on: true },
            ],
          },
        }),
        automation('c', {
          name: 'Sunrise',
          program: { trigger: { type: 'sun', event: 'sunrise', offsetMinutes: 15 }, conditions: [], actions: [{ type: 'notify', title: 'T', body: 'Good morning' }] },
        }),
        automation('d', {
          name: 'Chain',
          program: { trigger: { type: 'timeOfDay', minuteOfDay: 7 * 60 + 5, days: [1, 2] }, conditions: [], actions: [{ type: 'runRoutine', routineId: 'a' }] },
        }),
        automation('e', {
          name: 'Bare',
          program: { trigger: { type: 'sun', event: 'sunrise', offsetMinutes: 0 }, conditions: [], actions: [{ type: 'notify', title: 'Title only', body: ' ' }] },
        }),
      ],
    })
    renderWithProviders(<AutomationsView />)
    await waitFor(() => expect(rows()).toHaveLength(5))
    await waitFor(() => expect(rows()[0]).toHaveTextContent('At 22:30 · Turn on Sofa lamp'))
    expect(rows()[1]).toHaveTextContent('30 min before sunset · Turn off Unknown device')
    expect(rows()[2]).toHaveTextContent('15 min after sunrise · Notify: Good morning')
    expect(rows()[3]).toHaveTextContent('At 7:05 · Run automation')
    expect(rows()[4]).toHaveTextContent('At sunrise · Notify: Title only')
    const labels = screen.getAllByTestId('enabled-label').map((l) => l.textContent)
    expect(labels).toEqual(['On', 'Off', 'On', 'On', 'On'])
    // A label, not a control: nothing to toggle, edit, remove or run.
    expect(screen.queryAllByRole('button')).toEqual([])
    expect(screen.queryAllByRole('switch')).toEqual([])
    expect(screen.queryAllByRole('checkbox')).toEqual([])
    // Native shows no last-run time in the list, and neither does the web.
    expect(screen.queryByText(/last ran/i)).toBeNull()
  })

  it('an empty list says where automations come from', async () => {
    renderWithProviders(<AutomationsView />)
    expect(await screen.findByText('No automations yet')).toBeInTheDocument()
    expect(screen.getByText(/create in Home on Android or desktop/)).toBeInTheDocument()
  })

  it('only unlisted routines is the same empty state native shows', async () => {
    proxy.automations.mockResolvedValue({ ok: true, automations: [automation('x', { programStatus: 'malformed', program: null })] })
    renderWithProviders(<AutomationsView />)
    expect(await screen.findByText('No automations yet')).toBeInTheDocument()
  })

  it('a failed read is a failure by kind, never "no automations"', async () => {
    proxy.automations.mockResolvedValue(failure('error_home_service_unavailable'))
    renderWithProviders(<AutomationsView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_home_service_unavailable')
    expect(screen.queryByText('No automations yet')).toBeNull()
  })

  it('an unknown kind falls back to the generic sentence, never the raw key', async () => {
    proxy.automations.mockResolvedValue(failure('error_home_something_new'))
    renderWithProviders(<AutomationsView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Something went wrong. Please try again.')
    expect(alert).not.toHaveTextContent('error_home_something_new')
  })

  it('follows the stream, and a terminal end is shown by kind', async () => {
    renderWithProviders(<AutomationsView />)
    await screen.findByText('No automations yet')
    await waitFor(() => expect(streams.automations.open()).toBe(1))
    act(() => streams.automations.push({ ok: true, automations: [automation('new')] }))
    await waitFor(() => expect(rows()).toHaveLength(1))
    act(() => streams.automations.fail(new Error('error_home_service_unavailable: collector died')))
    expect(await screen.findByRole('alert')).toHaveAttribute('data-kind', 'error_home_service_unavailable')
  })
})
