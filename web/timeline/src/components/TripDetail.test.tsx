import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TripDetail } from './TripDetail'
import { day, failure, movement, proxy, resetProxy, routePoints, visit } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'
import { act, waitFor } from '@testing-library/react'
import { drawnMaps, lastMap, panTo } from '../test/mapKitMock'
import { MODE_COLORS } from '../lib/route'
import { queryKeys } from '../lib/queryKeys'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

const AT = Date.UTC(2026, 5, 3, 9)

describe('TripDetail', () => {
  it('shows the trip, its legs and its days, and what has not synced yet — and no map without route points', async () => {
    const onOpen = vi.fn()
    proxy.trip.mockResolvedValue({
      ok: true,
      trip: {
        tripId: 't1',
        title: 'Lisbon',
        dateRange: 'Jun 3 – Jun 4',
        year: '2026',
        missingMomentCount: 2,
        days: [
          day('2026-06-04', movement('leg2', AT + 86_400_000, 'flight', 1_850_000, 3 * 3600)),
          day('2026-06-03', visit('v', AT, { id: 'p', name: 'Alfama' }), movement('leg1', AT + 3_600_000, 'walk', 900, 900)),
        ],
      },
    })
    renderWithProviders(<TripDetail tripId="t1" onBack={() => {}} onOpen={onOpen} />)
    expect(await screen.findByRole('heading', { name: 'Lisbon' })).toBeInTheDocument()
    expect(screen.getByText('Jun 3 – Jun 4 · 2026')).toBeInTheDocument()
    expect(screen.getByText('2 moments of this trip haven’t reached this device yet.')).toBeInTheDocument()

    const legs = screen.getByRole('list', { name: 'Legs' })
    expect(within(legs).getByText('Flight · 1850.0 km · 3 h')).toBeInTheDocument()
    expect(within(legs).getByText('Walk · 900 m · 15 min')).toBeInTheDocument()
    expect(screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))).toEqual([
      'Thursday, June 4, 2026',
      'Wednesday, June 3, 2026',
    ])
    await userEvent.click(within(legs).getByText('Walk · 900 m · 15 min'))
    expect(onOpen).toHaveBeenCalledWith({ kind: 'moment', id: 'leg1' })
    expect(proxy.trip).toHaveBeenCalledWith('t1')
    // The legs have traces but no route points: no map, and no empty one.
    expect(proxy.movementRoutes.mock.calls).toEqual([[[['tr-leg1'], ['tr-leg2']]]])
    expect(screen.queryByTestId('tenshu-map')).toBeNull()
    expect(screen.queryByTestId('map-failure')).toBeNull()
    expect(drawnMaps).toEqual([])
  })

  it('says "not found" for a null trip', async () => {
    renderWithProviders(<TripDetail tripId="gone" onBack={() => {}} onOpen={() => {}} />)
    expect(await screen.findByText('Trip not found')).toBeInTheDocument()
  })

  it('renders a failed read by kind', async () => {
    proxy.trip.mockResolvedValue(failure('error_internal'))
    renderWithProviders(<TripDetail tripId="t1" onBack={() => {}} onOpen={() => {}} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_internal')
    expect(within(alert).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})

describe('TripDetail — the route map', () => {
  // A walk to the station, then the train: listed newest-first by day, drawn oldest-first.
  const walk = routePoints([38.711, -9.13], [38.712, -9.131], [38.713, -9.132])
  const train = routePoints([38.713, -9.132], [38.75, -9.2], [38.8, -9.38])
  const lisbon = {
    tripId: 't1',
    title: 'Lisbon',
    dateRange: 'Jun 3',
    year: '2026',
    missingMomentCount: 0,
    days: [
      day(
        '2026-06-03',
        visit('v', AT, { id: 'p', name: 'Alfama' }),
        movement('leg1', AT + 3_600_000, 'walk', 900, 900),
        movement('leg2', AT + 7_200_000, 'transit', 25_000, 1800),
      ),
    ],
  }

  function routes(byTrace: Record<string, ReturnType<typeof routePoints>>) {
    proxy.movementRoutes.mockImplementation(async (legs) => ({
      ok: true,
      legs: legs.map((traceIds) => traceIds.flatMap((id) => byTrace[id] ?? [])),
    }))
  }

  async function renderLisbon(trip = lisbon) {
    proxy.trip.mockResolvedValue({ ok: true, trip })
    const rendered = renderWithProviders(<TripDetail tripId="t1" onBack={() => {}} onOpen={() => {}} />)
    expect(await screen.findByRole('heading', { name: 'Lisbon' })).toBeInTheDocument()
    return rendered
  }

  it('draws one segment per leg, coloured by mode, every leg read in ONE call', async () => {
    routes({ 'tr-leg1': walk, 'tr-leg2': train })
    await renderLisbon()
    expect(await screen.findByRole('region', { name: 'Route' })).toBeInTheDocument()
    expect(proxy.movementRoutes.mock.calls).toEqual([[[['tr-leg1'], ['tr-leg2']]]])
    const segments = lastMap().polylines![0]!.segments
    expect(segments.map((s) => s.color)).toEqual([MODE_COLORS.walk, MODE_COLORS.transit])
    expect(segments.map((s) => s.points.length)).toEqual([3, 3])
  })

  it('a leg reclassified since its points were cached is drawn in its NEW mode’s colour', async () => {
    routes({ 'tr-leg1': walk, 'tr-leg2': train })
    const { client } = await renderLisbon()
    await screen.findByRole('region', { name: 'Route' })
    expect(lastMap().polylines![0]!.segments.map((s) => s.color)).toEqual([MODE_COLORS.walk, MODE_COLORS.transit])

    // Same traces, so the same route key — but the walk is a ride now.
    const [stay, leg1, leg2] = lisbon.days[0]!.moments
    const reclassified = { ...lisbon, days: [day('2026-06-03', stay!, { ...leg1!, movementMode: 'cycle' }, leg2!)] }
    proxy.trip.mockResolvedValue({ ok: true, trip: reclassified })
    await client.invalidateQueries({ queryKey: queryKeys.trip('t1'), exact: true })
    await waitFor(() =>
      expect(lastMap().polylines![0]!.segments.map((s) => s.color)).toEqual([MODE_COLORS.cycle, MODE_COLORS.transit]),
    )
    expect(proxy.movementRoutes).toHaveBeenCalledTimes(1) // the cached points, re-coloured — not re-read
  })

  it('a region lookup failing after a PAN leaves the map up — map-kit’s own alert says so, not a panel', async () => {
    routes({ 'tr-leg1': walk, 'tr-leg2': train })
    await renderLisbon()
    await screen.findByRole('region', { name: 'Route' })
    await waitFor(() => expect(proxy.mapRegionAt).toHaveBeenCalledTimes(1))

    // ⚠ Inside `act`, so a `setFailed` from the wrapper is flushed before the assertions below —
    // outside it this test passed with the guard removed (mutation-checked).
    proxy.mapRegionAt.mockResolvedValueOnce(failure('error_map_transport_failed'))
    const answer = await act(() => panTo(48.1, 11.6))
    expect(answer.ok).toBe(false) // handed to map-kit, which shows its tiles-failed alert over the map
    proxy.mapRegionAt.mockRejectedValueOnce(new Error('bridge gone'))
    await act(() => expect(panTo(48.2, 11.7)).rejects.toThrow('bridge gone'))

    expect(screen.getByRole('region', { name: 'Route' })).toBeInTheDocument()
    expect(screen.queryByTestId('map-failure')).toBeNull()
  })

  it('a transpacific route: the lookup gets a WGS84 longitude, however far the line is unwrapped', async () => {
    const flight = routePoints([35.55, 139.78], [45.0, 179.5], [45.5, -178.0], [37.62, -122.38])
    routes({ 'tr-leg1': flight })
    await renderLisbon({ ...lisbon, days: [day('2026-06-03', movement('leg1', AT, 'flight', 8_200_000, 9 * 3600))] })
    await screen.findByRole('region', { name: 'Route' })
    const lngs = lastMap().polylines![0]!.segments[0]!.points.map((p) => p.lng)
    expect(lngs).toEqual([139.78, 179.5, 182, 237.62])
    // A pan east of the line (MapLibre reports the unwrapped centre) is looked up at -122.38.
    await act(() => panTo(37.62, 237.62))
    const [lat, lng] = proxy.mapRegionAt.mock.calls.at(-1)!
    expect(lat).toBe(37.62)
    expect(lng).toBeCloseTo(-122.38, 9)
    expect(screen.queryByTestId('map-failure')).toBeNull()
  })

  it('fits the camera to the route and pins its origin and destination', async () => {
    routes({ 'tr-leg1': walk, 'tr-leg2': train })
    await renderLisbon()
    await screen.findByRole('region', { name: 'Route' })
    const map = lastMap()
    expect(map.camera).toEqual({ kind: 'bounds', points: [...walk, ...train].map((p) => ({ lat: p.latitude, lng: p.longitude })) })
    expect(map.pins).toEqual([
      { id: 'origin', position: { lat: 38.711, lng: -9.13 }, label: 'Start' },
      { id: 'destination', position: { lat: 38.8, lng: -9.38 }, label: 'End' },
    ])
    // The map's copy is the app's, not map-kit's English defaults.
    expect(map.labels?.notConfigured).toBe('Maps aren’t available on the web yet.')
  })

  it('a runtime without a tile transport: says so by kind, and the legs and days stay usable', async () => {
    const onOpen = vi.fn()
    routes({ 'tr-leg1': walk, 'tr-leg2': train })
    proxy.mapRegionAt.mockResolvedValue(failure('error_map_transport_unconfigured'))
    proxy.trip.mockResolvedValue({ ok: true, trip: lisbon })
    renderWithProviders(<TripDetail tripId="t1" onBack={() => {}} onOpen={onOpen} />)
    const unavailable = await screen.findByTestId('map-failure')
    expect(unavailable).toHaveAttribute('data-kind', 'error_map_transport_unconfigured')
    expect(unavailable).toHaveTextContent('Map unavailable')
    expect(unavailable).toHaveTextContent('Maps aren’t available on the web yet.')
    // No retry: retrying cannot change that answer.
    expect(within(unavailable).queryByRole('button')).toBeNull()
    expect(screen.queryByTestId('tenshu-map')).toBeNull()

    const legs = screen.getByRole('list', { name: 'Legs' })
    expect(within(legs).getAllByRole('button')).toHaveLength(2)
    await userEvent.click(within(legs).getByText('Walk · 900 m · 15 min'))
    expect(onOpen).toHaveBeenCalledWith({ kind: 'moment', id: 'leg1' })
    expect(screen.getByRole('region', { name: 'Wednesday, June 3, 2026' })).toBeInTheDocument()
  })

  it('another map failure renders by its own kind, with a retry that looks again', async () => {
    routes({ 'tr-leg1': walk })
    proxy.mapRegionAt.mockResolvedValueOnce(failure('error_map_catalog_unverified'))
    proxy.mapRegionAt.mockResolvedValue({ ok: true, region: null })
    await renderLisbon()
    const unavailable = await screen.findByTestId('map-failure')
    expect(unavailable).toHaveAttribute('data-kind', 'error_map_catalog_unverified')
    expect(unavailable).toHaveTextContent('The map catalogue couldn’t be verified, so no map is shown.')
    await userEvent.click(within(unavailable).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('region', { name: 'Route' })).toBeInTheDocument()
    expect(proxy.mapRegionAt).toHaveBeenCalledTimes(2)
  })

  it('a failed route read renders by kind in the map’s place, and the legs stay', async () => {
    proxy.movementRoutes.mockResolvedValue(failure('error_timeline_corrupt_route'))
    await renderLisbon()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_timeline_corrupt_route')
    expect(alert).toHaveTextContent('This route can’t be read.')
    expect(screen.getByRole('list', { name: 'Legs' })).toBeInTheDocument()
    expect(screen.queryByTestId('tenshu-map')).toBeNull()
  })

  it('a trip without movements reads no route and shows no map', async () => {
    proxy.trip.mockResolvedValue({ ok: true, trip: { ...lisbon, days: [day('2026-06-03', visit('v', AT, { id: 'p', name: 'Alfama' }))] } })
    renderWithProviders(<TripDetail tripId="t1" onBack={() => {}} onOpen={() => {}} />)
    expect(await screen.findByRole('heading', { name: 'Lisbon' })).toBeInTheDocument()
    expect(proxy.movementRoutes).not.toHaveBeenCalled()
    expect(screen.queryByTestId('tenshu-map')).toBeNull()
    expect(screen.queryByRole('status')).toBeNull()
  })
})
