import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { TimelinePlace, TimelinePlaceDetail } from '@tenshu/sdk/timeline'
import { PlaceDetail } from './PlaceDetail'
import { failure, placeDetail, placeRow, proxy, resetProxy, visit } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'
import { lastMap } from '../test/mapKitMock'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

const cafe = placeRow({ placeId: 'p-cafe', name: 'Café', city: 'Munich', visitCount: 3 })

function showing(detail: TimelinePlaceDetail, ...others: TimelinePlace[]) {
  proxy.place.mockResolvedValue({ ok: true, place: detail })
  proxy.places.mockResolvedValue({ ok: true, places: [detail.place, ...others] })
}

function renderPlace(placeId = 'p-cafe', handlers: { onReplace?: () => void; onOpen?: () => void } = {}) {
  return renderWithProviders(
    <PlaceDetail placeId={placeId} onBack={() => {}} onOpen={handlers.onOpen ?? (() => {})} onReplace={handlers.onReplace ?? (() => {})} />,
  )
}

describe('PlaceDetail — reading', () => {
  it('shows the place, its visits, and an unnamed place by its coordinate', async () => {
    showing(placeDetail(placeRow({ placeId: 'p-x', name: '', latE7: 481_374_300, lngE7: 115_754_900 }), {
      visits: [visit('v1', Date.UTC(2026, 5, 8, 9), { id: 'p-x', name: '' })],
    }))
    renderPlace('p-x')
    expect(await screen.findByRole('heading', { name: 'Unnamed place' })).toBeInTheDocument()
    expect(screen.getAllByText('48.13743, 11.57549').length).toBeGreaterThan(0)
    expect(screen.getByText('Visits')).toBeInTheDocument()
  })

  it('says when the id it opened was folded into this place', async () => {
    showing(placeDetail(cafe, { redirectedFrom: 'p-old' }))
    renderPlace('p-old')
    expect(await screen.findByText('The place you opened was merged into this one.')).toBeInTheDocument()
  })

  it('says "not found" for a null place, and renders a failure by kind', async () => {
    const { unmount } = renderPlace('gone')
    expect(await screen.findByText('Place not found')).toBeInTheDocument()
    unmount()
    proxy.place.mockResolvedValue(failure('error_timeline_source_silent'))
    renderPlace()
    expect(await screen.findByText('Still loading — or couldn’t load')).toBeInTheDocument()
  })
})

describe('PlaceDetail — rename', () => {
  it('renames through the dialog and refetches', async () => {
    showing(placeDetail(cafe))
    renderPlace()
    await userEvent.click(await screen.findByRole('button', { name: 'Rename' }))
    const dialog = screen.getByRole('dialog')
    const field = within(dialog).getByLabelText('Place name')
    expect(field).toHaveValue('Café')
    await userEvent.clear(field)
    await userEvent.type(field, '  Café Luitpold ')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    expect(proxy.renamePlace).toHaveBeenCalledWith('p-cafe', 'Café Luitpold')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(proxy.place).toHaveBeenCalledTimes(2))
  })

  it('keeps the dialog open with the typed error when the rename is refused', async () => {
    showing(placeDetail(cafe))
    proxy.renamePlace.mockResolvedValueOnce(failure('error_timeline_place_not_found'))
    renderPlace()
    await userEvent.click(await screen.findByRole('button', { name: 'Rename' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    expect(await within(dialog).findByText('That place is no longer available.')).toBeInTheDocument()
  })

  it('writes nothing on cancel', async () => {
    showing(placeDetail(cafe))
    renderPlace()
    await userEvent.click(await screen.findByRole('button', { name: 'Rename' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }))
    expect(proxy.renamePlace).not.toHaveBeenCalled()
  })
})

describe('PlaceDetail — role (the user’s answer)', () => {
  it('sets a role from the dialog, only on confirm', async () => {
    showing(placeDetail(cafe))
    renderPlace()
    await userEvent.click(await screen.findByRole('button', { name: 'Label as…' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('radio', { name: 'No label' })).toBeChecked()
    await userEvent.click(within(dialog).getByRole('radio', { name: 'Work' }))
    expect(proxy.setPlaceRole).not.toHaveBeenCalled()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(proxy.setPlaceRole).toHaveBeenCalledWith('p-cafe', 'work')
  })

  it('shows a confirmed role as the headline and the place name under it', async () => {
    showing(placeDetail(placeRow({ placeId: 'p-h', name: 'Ring 12', role: 'home' })))
    renderPlace('p-h')
    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument()
    expect(screen.getByText('Ring 12')).toBeInTheDocument()
    expect(screen.queryByText('Is this your home?')).toBeNull()
  })

  // ⚠ An inferred role is a QUESTION — never the headline — and "Not my home" is a real answer.
  it('asks about an inferred role, and "Not my home" writes none', async () => {
    showing(placeDetail(placeRow({ placeId: 'p-h', name: 'Ring 12', role: 'home', roleIsSuggestion: true })))
    renderPlace('p-h')
    expect(await screen.findByRole('heading', { name: 'Ring 12' })).toBeInTheDocument()
    expect(screen.getByText('Is this your home?')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Not my home' }))
    expect(proxy.setPlaceRole).toHaveBeenCalledWith('p-h', 'none')
  })

  it('"Yes" confirms the inferred role as the user’s', async () => {
    showing(placeDetail(placeRow({ placeId: 'p-w', name: 'Forum', role: 'work', roleIsSuggestion: true })))
    renderPlace('p-w')
    await userEvent.click(await screen.findByRole('button', { name: 'Yes' }))
    expect(proxy.setPlaceRole).toHaveBeenCalledWith('p-w', 'work')
  })
})

describe('PlaceDetail — merge', () => {
  const other = placeRow({ placeId: 'p-b', name: 'Café (terrace)' })

  it('picks the other place, confirms, merges, and follows the SDK’s survivor', async () => {
    const onReplace = vi.fn()
    showing(placeDetail(cafe), other)
    // ⚠ The survivor is min(rootA, rootB) — here the OTHER place, not the one the user started on.
    proxy.mergePlaces.mockResolvedValueOnce({ ok: true, survivorId: 'p-b' })
    renderPlace('p-cafe', { onReplace })
    await userEvent.click(await screen.findByRole('button', { name: 'Merge with another place' }))
    const picker = screen.getByRole('dialog')
    expect(within(picker).getByRole('button', { name: 'Continue' })).toBeDisabled()
    await userEvent.selectOptions(within(picker).getByLabelText('Merge with'), 'p-b')
    await userEvent.click(within(picker).getByRole('button', { name: 'Continue' }))
    expect(proxy.mergePlaces).not.toHaveBeenCalled()

    const confirm = screen.getByRole('dialog', { name: 'Merge these places?' })
    expect(within(confirm).getByText(/“Café” and “Café \(terrace\)” become one place/)).toBeInTheDocument()
    await userEvent.click(within(confirm).getByRole('button', { name: 'Merge' }))
    expect(proxy.mergePlaces).toHaveBeenCalledWith('p-cafe', 'p-b')
    await waitFor(() => expect(onReplace).toHaveBeenCalledWith({ kind: 'place', id: 'p-b' }))
  })

  it('shows already_merged in the confirm dialog, and stays', async () => {
    const onReplace = vi.fn()
    showing(placeDetail(cafe), other)
    proxy.mergePlaces.mockResolvedValueOnce(failure('error_timeline_already_merged'))
    renderPlace('p-cafe', { onReplace })
    await userEvent.click(await screen.findByRole('button', { name: 'Merge with another place' }))
    await userEvent.selectOptions(within(screen.getByRole('dialog')).getByLabelText('Merge with'), 'p-b')
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Continue' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Merge' }))
    expect(await screen.findByText('These are already the same place.')).toBeInTheDocument()
    expect(onReplace).not.toHaveBeenCalled()
  })
})

describe('PlaceDetail — unmerge', () => {
  // ⚠⚠ Run A fix 1: unmerge takes the FOLDED place's id; the survivor has no merge row.
  it('separates one specific folded place, after a confirm, by ITS id', async () => {
    showing(
      placeDetail(cafe, {
        mergedPlaceCount: 2,
        foldedPlaces: [
          { placeId: 'p-f1', name: 'Café terrace' },
          { placeId: 'p-f2', name: '' },
        ],
      }),
    )
    renderPlace()
    expect(await screen.findByText('2 places merged into this one')).toBeInTheDocument()
    const rows = screen.getAllByRole('button', { name: 'Separate' })
    expect(rows).toHaveLength(2)
    expect(screen.getByText('Unnamed place')).toBeInTheDocument()

    await userEvent.click(rows[1]!)
    const dialog = screen.getByRole('dialog', { name: 'Separate this place?' })
    expect(within(dialog).getByText('“Unnamed place” becomes its own place again, with its own visits.')).toBeInTheDocument()
    expect(proxy.unmergePlace).not.toHaveBeenCalled()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Separate' }))
    expect(proxy.unmergePlace).toHaveBeenCalledWith('p-f2')
    expect(proxy.unmergePlace).not.toHaveBeenCalledWith('p-cafe')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('offers no unmerge for a place with nothing folded into it', async () => {
    showing(placeDetail(cafe))
    renderPlace()
    await screen.findByRole('heading', { name: 'Café' })
    expect(screen.queryByRole('button', { name: 'Separate' })).toBeNull()
  })

  it('shows not_merged by kind when the fold is already gone', async () => {
    showing(placeDetail(cafe, { mergedPlaceCount: 1, foldedPlaces: [{ placeId: 'p-f1', name: 'Terrace' }] }))
    proxy.unmergePlace.mockResolvedValueOnce(failure('error_timeline_not_merged'))
    renderPlace()
    await userEvent.click(await screen.findByRole('button', { name: 'Separate' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Separate' }))
    expect(await screen.findByText(/isn’t merged into another/)).toBeInTheDocument()
  })
})

describe('PlaceDetail — the map', () => {
  it('one pin on the place, the camera centred on it at the native hero zoom', async () => {
    showing(placeDetail(cafe))
    renderPlace()
    expect(await screen.findByRole('region', { name: 'Map of this place' })).toBeInTheDocument()
    const map = lastMap()
    expect(map.pins).toEqual([{ id: 'p-cafe', position: { lat: 48.13743, lng: 11.57549 } }])
    expect(map.camera).toEqual({ kind: 'center', center: { lat: 48.13743, lng: 11.57549 }, zoom: 14.5 })
    expect(map.polylines).toBeUndefined()
    expect(proxy.mapRegionAt).toHaveBeenCalledWith(48.13743, 11.57549)
  })

  it('a runtime without a tile transport: "not available on the web yet", and the place stays editable', async () => {
    proxy.mapRegionAt.mockResolvedValue(failure('error_map_transport_unconfigured'))
    showing(placeDetail(cafe))
    renderPlace()
    const unavailable = await screen.findByTestId('map-failure')
    expect(unavailable).toHaveAttribute('data-kind', 'error_map_transport_unconfigured')
    expect(unavailable).toHaveTextContent('Maps aren’t available on the web yet.')
    await userEvent.click(screen.getByRole('button', { name: 'Rename' }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })

  it('a bridge refusal renders its own sentence, not a typed kind', async () => {
    proxy.mapRegionAt.mockRejectedValue(new Error('not permitted: mapRegion.regionAt'))
    showing(placeDetail(cafe))
    renderPlace()
    const unavailable = await screen.findByTestId('map-failure')
    expect(unavailable).not.toHaveAttribute('data-kind')
    expect(unavailable).toHaveTextContent('Map unavailable')
  })

  it('a place without a coordinate has no map', async () => {
    showing(placeDetail(placeRow({ placeId: 'p-cafe', name: 'Café', latE7: 0, lngE7: 0 })))
    renderPlace()
    expect(await screen.findByRole('heading', { name: 'Café' })).toBeInTheDocument()
    expect(screen.queryByTestId('tenshu-map')).toBeNull()
    expect(screen.queryByTestId('map-failure')).toBeNull()
    expect(proxy.mapRegionAt).not.toHaveBeenCalled()
  })
})
