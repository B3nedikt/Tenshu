import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from './i18n'
import { day, placeDetail, placeRow, proxy, resetProxy, visit } from './test/proxyMock'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { default: App } = await import('./App')

beforeEach(async () => {
  resetProxy()
  await i18n.changeLanguage('en')
})

describe('App', () => {
  it('renders its shell, with no lease banner while this tab holds the runtime', async () => {
    proxy.runtimeLease.mockResolvedValue('held')
    renderWithQueryClient(<App />)
    expect(screen.getByRole('heading', { name: 'Timeline' })).toBeInTheDocument()
    expect(await screen.findByText('Nothing on your timeline yet')).toBeInTheDocument()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('navigates feed → moment → place → back, and to the places tab', async () => {
    proxy.runtimeLease.mockResolvedValue('held')
    const at = Date.UTC(2026, 5, 8, 9)
    proxy.feed.mockResolvedValue({ ok: true, days: [day('2026-06-08', visit('v1', at, { id: 'p1', name: 'Café' }))], trips: [], suggestion: null })
    proxy.momentDetail.mockResolvedValue({ ok: true, moment: visit('v1', at, { id: 'p1', name: 'Café' }) })
    proxy.place.mockResolvedValue({ ok: true, place: placeDetail(placeRow({ placeId: 'p1', name: 'Café' })) })
    proxy.places.mockResolvedValue({ ok: true, places: [placeRow({ placeId: 'p1', name: 'Café' })] })
    renderWithQueryClient(<App />)

    await userEvent.click(await screen.findByRole('button', { name: /Café/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Open this place' }))
    expect(await screen.findByRole('button', { name: 'Merge with another place' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Back/ }))
    expect(await screen.findByRole('button', { name: 'Open this place' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Places' }))
    expect(await screen.findByText('1 visit')).toBeInTheDocument()
  })
})
