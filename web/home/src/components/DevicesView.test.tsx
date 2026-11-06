import { act, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

const { proxy, resetProxy, failure, device, state, hub, streams } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { DevicesView } = await import('./DevicesView')

beforeEach(() => resetProxy())

function rowText(id: string): string {
  return screen.getAllByTestId('device-row').find((r) => r.getAttribute('data-id') === id)!.textContent ?? ''
}

describe('DevicesView', () => {
  it('groups devices by room, in the rooms order, with the unassigned ones last', async () => {
    proxy.devices.mockResolvedValue({
      ok: true,
      devices: [
        device('lamp', { name: 'Sofa lamp', room: 'Living room' }),
        device('plug', { name: 'Plug', kind: 'plug', room: '' }),
        device('thermo', { name: 'Thermostat', kind: 'thermostat', room: 'Bedroom', state: state({ temperatureDeciC: 215 }) }),
        device('lamp2', { name: 'Reading lamp', room: 'Living room' }),
      ],
      rooms: ['Living room', 'Bedroom'],
    })
    renderWithProviders(<DevicesView />)
    const living = await screen.findByRole('list', { name: 'Living room' })
    expect(within(living).getAllByTestId('device-row').map((r) => r.getAttribute('data-id'))).toEqual(['lamp', 'lamp2'])
    expect(within(screen.getByRole('list', { name: 'Bedroom' })).getByText('21.5°')).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Unassigned' })).getAllByTestId('device-row')).toHaveLength(1)
    const headings = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)
    expect(headings).toEqual(['Living room', 'Bedroom', 'Unassigned'])
  })

  it('shows state as stored, per kind — and "No reading yet" for a state that has not synced, never a default', async () => {
    proxy.devices.mockResolvedValue({
      ok: true,
      devices: [
        device('on', { state: state({ power: 'on', levelPercent: 70 }) }),
        device('onNoLevel', { state: state({ power: 'on' }) }),
        device('off', { state: state({ power: 'off', levelPercent: 70 }) }),
        device('unsynced', { state: null }),
        device('speaker', { kind: 'speaker', state: state({ online: false }) }),
        device('camera', { kind: 'camera', state: state({ online: true }) }),
        device('sensor', { kind: 'sensor', state: state({ temperatureDeciC: 195, humidityPercent: 45 }) }),
        device('future', { kind: 'doorbell', state: state({ humidityPercent: 50 }) }),
      ],
      rooms: [],
    })
    renderWithProviders(<DevicesView />)
    await screen.findAllByTestId('device-row')
    expect(rowText('on')).toContain('On · 70%')
    expect(rowText('onNoLevel')).toMatch(/^onNoLevelOnLight$/)
    expect(rowText('off')).toContain('Off')
    expect(rowText('unsynced')).toContain('No reading yet')
    expect(rowText('unsynced')).not.toContain('Off')
    expect(rowText('speaker')).toContain('Disconnected')
    expect(screen.getByText('Disconnected')).toHaveClass('text-danger')
    expect(rowText('camera')).toContain('Connected')
    expect(rowText('sensor')).toContain('19.5° · 45%')
    // A kind a newer hub knows is a generic reading, never dropped.
    expect(rowText('future')).toContain('50%')
    expect(rowText('future')).toContain('Device')
  })

  it('says plainly that control happens elsewhere, and offers nothing to press', async () => {
    proxy.devices.mockResolvedValue({ ok: true, devices: [device('lamp', { state: state({ power: 'on', levelPercent: 40 }) })], rooms: [] })
    proxy.hubStorage.mockResolvedValue({ ok: true, hub: hub() })
    renderWithProviders(<DevicesView />)
    await screen.findByTestId('device-row')
    await screen.findByTestId('hub-tile')
    expect(screen.getByTestId('view-only')).toHaveTextContent(/To control devices or add new ones, use Home on your Android phone or your computer/)
    expect(screen.queryAllByRole('button')).toEqual([])
    expect(screen.queryAllByRole('switch')).toEqual([])
    expect(screen.queryAllByRole('checkbox')).toEqual([])
    expect(screen.queryAllByRole('slider')).toEqual([])
    expect(screen.queryAllByRole('menu')).toEqual([])
  })

  it('an empty realm says where devices come from — only after a successful read', async () => {
    renderWithProviders(<DevicesView />)
    expect(await screen.findByText('No devices yet')).toBeInTheDocument()
    expect(screen.getByText(/once a Tenshu Hub in your realm reports them/)).toBeInTheDocument()
  })

  it('a failed devices read is a failure by kind, never an empty home', async () => {
    proxy.devices.mockResolvedValue(failure('error_home_service_unavailable'))
    renderWithProviders(<DevicesView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_home_service_unavailable')
    expect(alert).toHaveTextContent('Home’s data can’t be reached in this browser right now.')
    expect(screen.queryByText('No devices yet')).toBeNull()
  })

  it('a rejected bridge call says the bridge failed, with a retry that reads again', async () => {
    proxy.devices.mockRejectedValueOnce(new Error('not permitted: home.devices'))
    renderWithProviders(<DevicesView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Home is not allowed to do that.')
    proxy.devices.mockResolvedValue({ ok: true, devices: [device('lamp')], rooms: [] })
    act(() => within(alert).getByRole('button', { name: 'Try again' }).click())
    expect(await screen.findByTestId('device-row')).toBeInTheDocument()
  })

  it('no hub reporting is no tile — an answer; a failed hub read is a failure', async () => {
    renderWithProviders(<DevicesView />)
    await screen.findByText('No devices yet')
    expect(screen.queryByTestId('hub-tile')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('a failed hub read shows by kind beside the devices', async () => {
    proxy.hubStorage.mockResolvedValue(failure('error_home_service_unavailable'))
    proxy.devices.mockResolvedValue({ ok: true, devices: [device('lamp')], rooms: [] })
    renderWithProviders(<DevicesView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_home_service_unavailable')
    expect(await screen.findByTestId('device-row')).toBeInTheDocument()
  })

  it('follows the devices stream, and a stream that ends says which failure, by kind', async () => {
    proxy.devices.mockResolvedValue({ ok: true, devices: [device('lamp')], rooms: [] })
    renderWithProviders(<DevicesView />)
    await screen.findByTestId('device-row')
    await waitFor(() => expect(streams.devices.open()).toBe(1))
    act(() => streams.devices.push({ ok: true, devices: [device('lamp'), device('lamp2')], rooms: [] }))
    await waitFor(() => expect(screen.getAllByTestId('device-row')).toHaveLength(2))

    act(() => streams.devices.fail(new Error('error_home_stream_ended: collector completed')))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_home_stream_ended')
    expect(alert).toHaveTextContent('Updates stopped')
    expect(alert).toHaveTextContent('The connection to Home’s data ended.')
    // The last rows stay, marked possibly stale.
    expect(screen.getAllByTestId('device-row')).toHaveLength(2)
    act(() => within(alert).getByRole('button', { name: 'Reconnect' }).click())
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
    expect(streams.devices.open()).toBe(1)
  })

  it('a stream refused by the bridge is told apart from a typed end', async () => {
    renderWithProviders(<DevicesView />)
    await screen.findByText('No devices yet')
    await waitFor(() => expect(streams.devices.open()).toBe(1))
    act(() => streams.devices.fail(new Error('apex proxy frame is not available')))
    const alert = await screen.findByRole('alert')
    expect(alert).not.toHaveAttribute('data-kind')
    expect(alert).toHaveTextContent('Couldn’t reach Tenshu.')
  })
})
