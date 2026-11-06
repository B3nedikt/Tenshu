import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from './i18n'
import { proxy, resetProxy } from './test/proxyMock'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { default: App } = await import('./App')

beforeEach(async () => {
  resetProxy()
  await i18n.changeLanguage('en')
})

describe('App', () => {
  it('renders its shell and the four sections, with no lease banner while this tab holds the runtime', async () => {
    proxy.runtimeLease.mockResolvedValue('held')
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
    renderWithQueryClient(<App />)
    expect(screen.getByRole('heading', { name: 'Photos' })).toBeInTheDocument()
    expect(await screen.findByText(/No photos yet/)).toBeInTheDocument()
    for (const name of ['Photos', 'Albums', 'Favourites', 'Trash']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    expect(screen.queryByRole('status')).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Trash' }))
    expect(await screen.findByText('The trash is empty.')).toBeInTheDocument()
  })

  it('builds none of the surfaces decided out of web v1 (photos-app.md §42.6)', () => {
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
    renderWithQueryClient(<App />)
    for (const name of [/people/i, /faces/i, /search/i, /upload/i, /places/i, /duplicates/i, /hidden/i]) {
      expect(screen.queryByRole('button', { name })).toBeNull()
    }
  })

  it('speaks German', async () => {
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
    await i18n.changeLanguage('de')
    renderWithQueryClient(<App />)
    expect(screen.getByRole('heading', { name: 'Fotos' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Papierkorb' })).toBeInTheDocument()
  })
})
