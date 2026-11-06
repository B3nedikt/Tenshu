// The REAL `<TenshuMap>` behind a workout's route, for the ways a map can fail to draw. Tiles are
// live, so the normal path reaches MapLibre, which jsdom cannot construct; what is
// pinned here is that every failure says why — in the app's language — and is never a blank box.
// ⚠ jsdom has no WebGL, so map-kit's probe is stubbed (by its resolved path) to say yes; MapLibre
// itself is never constructed on these paths.
import { screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
const { webgl } = vi.hoisted(() => ({ webgl: { ok: true } }))
vi.mock('../../../map-kit/src/webgl', () => ({ supportsWebGL: () => webgl.ok }))

const { proxy, resetProxy, workoutRow, detailOf } = await import('../test/proxyMock')
const { renderWithProviders } = await import('../test/render')
const i18n = (await import('../i18n')).default
const { WorkoutDetail } = await import('./WorkoutDetail')

const ROUTE = [
  { tSec: 0, lat: 52.52, lng: 13.405, altM: null },
  { tSec: 600, lat: 52.523, lng: 13.409, altM: null },
]

beforeEach(() => {
  resetProxy()
  webgl.ok = true
  proxy.mapRegionCall.mockResolvedValue({ ok: false, error: { kind: 'error_map_transport_failed', message: 'x' } } as never)
  proxy.workout.mockResolvedValue({ ok: true, workout: detailOf(workoutRow('w1', 0, { title: 'Run' }), { route: ROUTE }) })
})

describe('the route map when it cannot draw', () => {
  it('a failed tile fetch (offline, refused) says the tiles could not be loaded, after asking which region covers the route', async () => {
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={() => {}} />)
    await waitFor(() => expect(screen.getByTestId('map-unavailable')).toHaveTextContent('Map tiles couldn’t be loaded.'), {
      timeout: 5000,
    })
    expect(proxy.mapRegionCall).toHaveBeenCalledWith('mapRegion.regionAt', { latitude: 52.5215, longitude: 13.407 })
    // The rest of the detail is unaffected.
    expect(screen.getByRole('heading', { name: 'Run' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delete workout' })).toBeInTheDocument()
  })

  it('in the app’s language', async () => {
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={() => {}} />)
    await i18n.changeLanguage('de')
    await waitFor(() => expect(screen.getByTestId('map-unavailable')).toHaveTextContent('Kartenkacheln konnten nicht geladen werden.'), {
      timeout: 5000,
    })
    await i18n.changeLanguage('en')
  })

  it('a runtime without a tile transport says so, not as a tile failure', async () => {
    proxy.mapRegionCall.mockResolvedValue({ ok: false, error: { kind: 'error_map_transport_unconfigured', message: 'x' } } as never)
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={() => {}} />)
    await waitFor(() => expect(screen.getByTestId('map-unavailable')).toHaveTextContent('Maps are not set up for this site.'), {
      timeout: 5000,
    })
  })

  it('without WebGL: map unavailable with the reason, and no region is asked for', async () => {
    webgl.ok = false
    renderWithProviders(<WorkoutDetail workoutId="w1" onBack={() => {}} />)
    await waitFor(() => expect(screen.getByTestId('map-unavailable')).toHaveTextContent(/WebGL/))
    expect(proxy.mapRegionCall).not.toHaveBeenCalled()
  })
})
