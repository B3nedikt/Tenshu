import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PlacesView } from './PlacesView'
import { failure, placeRow, proxy, resetProxy, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

describe('PlacesView', () => {
  it('lists places, an unnamed one by its coordinate, a confirmed role over the name', async () => {
    const onOpen = vi.fn()
    proxy.places.mockResolvedValue({
      ok: true,
      places: [
        placeRow({ placeId: 'a', name: 'Ring 12', role: 'home', visitCount: 40 }),
        placeRow({ placeId: 'b', name: '', latE7: 520_000_000, lngE7: 134_000_000, visitCount: 1 }),
        placeRow({ placeId: 'c', name: 'Forum', role: 'work', roleIsSuggestion: true }),
      ],
    })
    renderWithProviders(<PlacesView onOpen={onOpen} />)
    expect(await screen.findByText('Home')).toBeInTheDocument()
    expect(screen.getByText('Ring 12')).toBeInTheDocument()
    expect(screen.getByText('40 visits')).toBeInTheDocument()
    expect(screen.getByText('Unnamed place')).toBeInTheDocument()
    expect(screen.getByText('52.00000, 13.40000')).toBeInTheDocument()
    expect(screen.getByText('Forum')).toBeInTheDocument()
    expect(screen.queryByText('Work')).toBeNull()
    await userEvent.click(screen.getByText('Unnamed place'))
    expect(onOpen).toHaveBeenCalledWith({ kind: 'place', id: 'b' })
  })

  it('takes pushed updates (a rename elsewhere)', async () => {
    proxy.places.mockResolvedValue({ ok: true, places: [placeRow({ placeId: 'b', name: '' })] })
    renderWithProviders(<PlacesView onOpen={() => {}} />)
    await screen.findByText('Unnamed place')
    streams.places.push({ ok: true, places: [placeRow({ placeId: 'b', name: 'Bakery' })] })
    expect(await screen.findByText('Bakery')).toBeInTheDocument()
  })

  it('renders a failed read, never an empty list', async () => {
    proxy.places.mockResolvedValue(failure('error_timeline_source_silent'))
    renderWithProviders(<PlacesView onOpen={() => {}} />)
    expect(await screen.findByText('Still loading — or couldn’t load')).toBeInTheDocument()
    expect(screen.queryByText(/Places appear/)).toBeNull()
  })
})
