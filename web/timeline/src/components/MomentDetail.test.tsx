import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MomentDetail } from './MomentDetail'
import { activity, failure, movement, proxy, resetProxy, routePoints, visit } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'
import { lastMap } from '../test/mapKitMock'
import { MODE_COLORS } from '../lib/route'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

const AT = Date.UTC(2026, 5, 8, 9)

describe('MomentDetail', () => {
  it('shows a visit by its place, and opens the place', async () => {
    const onOpen = vi.fn()
    proxy.momentDetail.mockResolvedValue({ ok: true, moment: visit('v1', AT, { id: 'cafe', name: 'Café Luitpold', city: 'Munich' }, 82 * 60) })
    renderWithProviders(<MomentDetail momentId="v1" onBack={() => {}} onOpen={onOpen} />)
    expect(await screen.findByRole('heading', { name: 'Café Luitpold' })).toBeInTheDocument()
    expect(screen.getByText('Munich')).toBeInTheDocument()
    expect(screen.getByText('1 h 22 min')).toBeInTheDocument()
    expect(proxy.momentDetail).toHaveBeenCalledWith('v1')
    await userEvent.click(screen.getByRole('button', { name: 'Open this place' }))
    expect(onOpen).toHaveBeenCalledWith({ kind: 'place', id: 'cafe' })
  })

  it('shows a movement’s mode and distance, in the given units', async () => {
    proxy.momentDetail.mockResolvedValue({ ok: true, moment: movement('m1', AT, 'transit', 12_345) })
    renderWithProviders(<MomentDetail momentId="m1" onBack={() => {}} onOpen={() => {}} />, { units: 'imperial' })
    expect(await screen.findByRole('heading', { name: 'Transit' })).toBeInTheDocument()
    expect(screen.getByText('7.7 mi')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Open this place' })).toBeNull()
  })

  it('shows an activity’s kind, and says when its workout has not synced yet', async () => {
    proxy.momentDetail.mockResolvedValue({ ok: true, moment: activity('a1', AT, null, 'Morning run') })
    renderWithProviders(<MomentDetail momentId="a1" onBack={() => {}} onOpen={() => {}} />)
    expect(await screen.findByRole('heading', { name: 'Morning run' })).toBeInTheDocument()
    expect(screen.getByText(/haven’t reached this device yet/)).toBeInTheDocument()
  })

  it('says "not found" for a null moment — an answer, not a failure', async () => {
    proxy.momentDetail.mockResolvedValue({ ok: true, moment: null })
    renderWithProviders(<MomentDetail momentId="gone" onBack={() => {}} onOpen={() => {}} />)
    expect(await screen.findByText('Moment not found')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('renders a failed read by kind, with a retry', async () => {
    proxy.momentDetail.mockResolvedValueOnce(failure('error_timeline_source_silent'))
    proxy.momentDetail.mockResolvedValueOnce({ ok: true, moment: visit('v1', AT, { id: 'p', name: 'Late' }) })
    renderWithProviders(<MomentDetail momentId="v1" onBack={() => {}} onOpen={() => {}} />)
    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText('Still loading — or couldn’t load')).toBeInTheDocument()
    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'Late' })).toBeInTheDocument()
  })

  it('goes back', async () => {
    const onBack = vi.fn()
    renderWithProviders(<MomentDetail momentId="x" onBack={onBack} onOpen={() => {}} />)
    await userEvent.click(screen.getByRole('button', { name: /Back/ }))
    expect(onBack).toHaveBeenCalled()
  })
})

describe('MomentDetail — a movement’s route', () => {
  it('draws every trace behind a collapsed ride as ONE leg, in its mode’s colour', async () => {
    const ride = { ...movement('m1', AT, 'transit', 12_345), traceIds: ['tr-a', 'tr-b'] }
    proxy.momentDetail.mockResolvedValue({ ok: true, moment: ride })
    proxy.movementRoutes.mockResolvedValue({ ok: true, legs: [routePoints([48.1, 11.5], [48.2, 11.6], [48.3, 11.7])] })
    renderWithProviders(<MomentDetail momentId="m1" onBack={() => {}} onOpen={() => {}} />)
    expect(await screen.findByRole('region', { name: 'Route' })).toBeInTheDocument()
    expect(proxy.movementRoutes.mock.calls).toEqual([[[['tr-a', 'tr-b']]]])
    const map = lastMap()
    expect(map.polylines![0]!.segments.map((s) => s.color)).toEqual([MODE_COLORS.transit])
    expect(map.pins!.map((p) => p.id)).toEqual(['origin', 'destination'])
  })

  it('a visit reads no route and has no route map', async () => {
    proxy.momentDetail.mockResolvedValue({ ok: true, moment: visit('v1', AT, { id: 'cafe', name: 'Café' }) })
    renderWithProviders(<MomentDetail momentId="v1" onBack={() => {}} onOpen={() => {}} />)
    expect(await screen.findByRole('heading', { name: 'Café' })).toBeInTheDocument()
    expect(proxy.movementRoutes).not.toHaveBeenCalled()
    expect(screen.queryByTestId('tenshu-map')).toBeNull()
  })
})

