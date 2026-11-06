import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TenshuMapProps } from '@tenshu/map-kit'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

// The map itself is map-kit's (tested there); what is under test is what Fit hands it.
const { mapProps } = vi.hoisted(() => ({ mapProps: [] as TenshuMapProps[] }))
vi.mock('@tenshu/map-kit', () => ({
  TenshuMap: (props: TenshuMapProps) => {
    mapProps.push(props)
    return <div data-testid="tenshu-map" />
  },
  bridgeRegionAccess: () => ({ regionAt: vi.fn(), readRange: vi.fn() }),
}))

const { proxy, resetProxy, failure, workoutRow, detailOf, PROFILE } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const { WorkoutDetail } = await import('./WorkoutDetail')

const T0 = Date.UTC(2026, 9, 28, 7, 0)
const ROUTE = [
  { tSec: 0, lat: 52.52, lng: 13.405, altM: 34 },
  { tSec: 300, lat: 52.521, lng: 13.407, altM: null },
  { tSec: 600, lat: 52.523, lng: 13.409, altM: 35 },
]
const HR = [
  { tSec: 0, bpm: 110 },
  { tSec: 300, bpm: 150 },
  { tSec: 600, bpm: 130 },
]

beforeEach(() => {
  resetProxy()
  mapProps.length = 0
})

describe('WorkoutDetail', () => {
  it('shows the stats, leaving an unknown one out rather than showing 0', async () => {
    proxy.workout.mockResolvedValue({
      ok: true,
      workout: detailOf(workoutRow('w1', T0, { title: 'Morning run', distanceM: 10_000, paceSecPerKm: 330, avgHr: 141, maxHr: 172, activeEnergyKj: null, steps: null })),
    })
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={() => {}} />)
    expect(await screen.findByRole('heading', { name: 'Morning run' })).toBeInTheDocument()
    const stats = within(screen.getByTestId('stats'))
    expect(stats.getByText('30:00')).toBeInTheDocument()
    expect(stats.getByText('10.0 km')).toBeInTheDocument()
    expect(stats.getByText('5:30 /km')).toBeInTheDocument()
    expect(stats.getByText('141 bpm')).toBeInTheDocument()
    expect(stats.getByText('172 bpm')).toBeInTheDocument()
    expect(stats.queryByText('Energy')).toBeNull()
    expect(stats.queryByText('Steps')).toBeNull()
    expect(proxy.workout).toHaveBeenCalledWith('w1')
  })

  it('formats in the profile units: miles, and pace per mile', async () => {
    proxy.profile.mockResolvedValue({ ok: true, profile: { ...PROFILE, units: 'imperial' } })
    proxy.workout.mockResolvedValue({ ok: true, workout: detailOf(workoutRow('w1', T0, { distanceM: 10_000, paceSecPerKm: 330 })) })
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={() => {}} />)
    const stats = within(await screen.findByTestId('stats'))
    await waitFor(() => expect(stats.getByText('6.2 mi')).toBeInTheDocument())
    expect(stats.getByText('8:51 /mi')).toBeInTheDocument()
  })

  it('draws the HR curve from the series, with the average line', async () => {
    proxy.workout.mockResolvedValue({ ok: true, workout: detailOf(workoutRow('w1', T0, { avgHr: 135 }), { heartRate: HR }) })
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={() => {}} />)
    const chart = await screen.findByTestId('hr-chart')
    expect(chart).toHaveAccessibleName('Heart rate, 110 to 150 bpm')
    // x is elapsed time; y spans 102..158 (min-8 .. max+8) with 0 at the top (ChartMath.hrPolyline).
    expect(screen.getByTestId('hr-line').getAttribute('points')).toBe('0.00,34.29 50.00,5.71 100.00,20.00')
    expect(screen.getByTestId('hr-average')).toBeInTheDocument()
  })

  it('draws the route: one polyline, the camera fitted to it, start and end pins', async () => {
    proxy.workout.mockResolvedValue({ ok: true, workout: detailOf(workoutRow('w1', T0), { route: ROUTE }) })
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={() => {}} />)
    await screen.findByTestId('tenshu-map')
    const props = mapProps.at(-1)!
    const points = ROUTE.map((p) => ({ lat: p.lat, lng: p.lng }))
    expect(props.camera).toEqual({ kind: 'bounds', points, padding: 32 })
    expect(props.polylines).toEqual([{ id: 'route', segments: [{ points, color: '#7C3AED' }] }])
    expect(props.pins).toEqual([
      { id: 'start', position: points[0], label: 'Start' },
      { id: 'end', position: points[2], label: 'Finish' },
    ])
    expect(props.labels?.notConfigured).toBe('Maps are not set up for this site.')
  })

  it('no route, no map — and no HR series, no chart', async () => {
    proxy.workout.mockResolvedValue({ ok: true, workout: detailOf(workoutRow('w1', T0, { title: 'Gym' })) })
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={() => {}} />)
    await screen.findByRole('heading', { name: 'Gym' })
    expect(screen.queryByText('Route')).toBeNull()
    expect(screen.queryByTestId('tenshu-map')).toBeNull()
    expect(screen.queryByTestId('hr-chart')).toBeNull()
    expect(mapProps).toEqual([])
  })

  it('a missing workout is an answer, not a failure', async () => {
    renderWithProviders(<WorkoutDetail workoutId="gone" onBack={() => {}} />)
    expect(await screen.findByText(/This workout no longer exists/)).toHaveAttribute('role', 'status')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('a failed read renders by kind with a retry', async () => {
    proxy.workout.mockResolvedValueOnce(failure('error_fit_streams_unreadable'))
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={() => {}} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_fit_streams_unreadable')
    expect(alert).toHaveTextContent('route and heart rate can’t be read')
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(proxy.workout).toHaveBeenCalledTimes(2)
  })
})

describe('WorkoutDetail delete', () => {
  beforeEach(() => {
    proxy.workout.mockResolvedValue({ ok: true, workout: detailOf(workoutRow('w1', T0, { title: 'Morning run' })) })
  })

  it('asks first; Cancel deletes nothing', async () => {
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Delete workout' }))
    const dialog = await screen.findByRole('dialog', { name: 'Delete this workout?' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(proxy.deleteWorkout).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('confirmed: deletes that workout and goes back', async () => {
    const onBack = vi.fn()
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={onBack} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Delete workout' }))
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(onBack).toHaveBeenCalled())
    expect(proxy.deleteWorkout).toHaveBeenCalledWith('w1')
  })

  it('a refused delete stays open and says why', async () => {
    proxy.deleteWorkout.mockResolvedValue(failure('error_fit_workout_not_found'))
    const onBack = vi.fn()
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={onBack} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Delete workout' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(await within(dialog).findByRole('alert')).toHaveAttribute('data-kind', 'error_fit_workout_not_found')
    expect(onBack).not.toHaveBeenCalled()
  })
})
