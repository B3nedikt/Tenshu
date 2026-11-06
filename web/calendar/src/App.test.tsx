import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithQueryClient } from '@tenshu/app-kit/testing'
import i18n from './i18n'
import { resetProxy } from './test/proxyMock'

vi.mock('./lib/proxy', async () => (await import('./test/proxyMock')).proxyModule)

const { default: App } = await import('./App')

beforeEach(async () => {
  resetProxy()
  await i18n.changeLanguage('en')
})

describe('App', () => {
  it('renders its shell on the calendar, with no lease banner while this tab holds the runtime', async () => {
    renderWithQueryClient(<App />)
    expect(screen.getByRole('heading', { name: 'Calendar', level: 1 })).toBeInTheDocument()
    expect(await screen.findByRole('grid', { name: 'Month' })).toBeInTheDocument()
    expect(screen.queryByText(/Tenshu is open in another tab/)).toBeNull()
  })

  it('switches between its screens', async () => {
    renderWithQueryClient(<App />)
    await userEvent.click(screen.getByRole('button', { name: 'Subscriptions' }))
    expect(await screen.findByRole('heading', { name: 'Subscribed calendars' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Import & export' }))
    expect(await screen.findByRole('heading', { name: 'Import an .ics file' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Calendars' }))
    expect(await screen.findByRole('heading', { name: 'Your calendars' })).toBeInTheDocument()
  })

  it('speaks German', async () => {
    await i18n.changeLanguage('de')
    renderWithQueryClient(<App />)
    expect(screen.getByRole('heading', { name: 'Kalender', level: 1 })).toBeInTheDocument()
    expect(await screen.findByRole('tab', { name: 'Monat' })).toBeInTheDocument()
  })
})
