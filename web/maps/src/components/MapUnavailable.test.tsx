// The map when WebGL works but the tile transport answers `error_map_transport_unconfigured`. Only
// map-kit's WebGL probe is faked — `<TenshuMap>` itself is real, as is its lazy MapView chunk.
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { placeRow, proxy, regions, resetProxy } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
vi.mock('../../../map-kit/src/webgl', () => ({ supportsWebGL: () => true }))

const { PlacesView } = await import('./PlacesView')

beforeEach(() => {
  resetProxy()
})

describe('the map with no tile transport', () => {
  it('says map tiles are not set up for this server, and the list stays fully usable', async () => {
    proxy.places.mockResolvedValue({ ok: true, places: [placeRow({ placeId: 'p1', name: 'Café', latitude: 43.77, longitude: 11.25 })] })
    const onOpen = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<PlacesView onOpen={onOpen} />)

    // ⚠ The generous timeout is the lazy MapView chunk (MapLibre itself) loading under jsdom.
    await waitFor(() => expect(screen.getByTestId('map-unavailable')).toHaveTextContent('Map tiles aren’t set up for this Tenshu server, so no map is shown.'), {
      timeout: 10_000,
    })
    expect(screen.getByTestId('map-unavailable')).toHaveTextContent('Map unavailable')
    expect(regions.regionAt).toHaveBeenCalledWith(43.77, 11.25)

    await user.type(screen.getByRole('searchbox'), 'cafe')
    await user.click(screen.getByRole('button', { name: /Café/ }))
    expect(onOpen).toHaveBeenCalledWith({ kind: 'place', id: 'p1' })
  }, 20_000)
})
