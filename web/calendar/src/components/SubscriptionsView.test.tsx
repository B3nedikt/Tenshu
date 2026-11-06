import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SubscriptionsView } from './SubscriptionsView'
import { HOME, proxy, resetProxy, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

const feed = { subscriptionId: 'sub-1', calendarId: HOME.calendarId, url: 'https://example.org/holidays.ics', enabled: true, lastFetchedEpochMs: 0 }

describe('SubscriptionsView', () => {
  it('lists rows, says who fetches, and that a never-fetched feed is not 1970', async () => {
    proxy.listSubscriptions.mockResolvedValueOnce({ ok: true, subscriptions: [feed] })
    renderWithProviders(<SubscriptionsView />)
    expect(await screen.findByText('Home')).toBeInTheDocument()
    expect(screen.getByText(feed.url)).toBeInTheDocument()
    expect(screen.getByText('Not fetched yet by any of your devices')).toBeInTheDocument()
    expect(screen.getByText(/fetched by your phone and desktop, not by this browser/)).toBeInTheDocument()

    await waitFor(() => expect(streams.subscriptions.open()).toBe(1))
    act(() => streams.subscriptions.push({ ok: true, subscriptions: [{ ...feed, enabled: false }] }))
    expect(await screen.findByText('Paused')).toBeInTheDocument()
  })

  it('adding states the consent to the OTHER devices fetching, before the write', async () => {
    renderWithProviders(<SubscriptionsView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Add subscription' }))
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent('Your phone and desktop will fetch this calendar')
    expect(dialog).toHaveTextContent('This browser never fetches it')
    await userEvent.type(within(dialog).getByLabelText(/Calendar address/), 'webcal://example.org/f.ics')
    await userEvent.type(within(dialog).getByLabelText('Name (optional)'), 'Holidays')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Subscribe' }))
    await waitFor(() => expect(proxy.addSubscription).toHaveBeenCalledWith('webcal://example.org/f.ics', 'Holidays'))
  })

  it('a refused address renders by kind', async () => {
    proxy.addSubscription.mockResolvedValueOnce({ ok: false, error: { kind: 'error_calendar_subscription_url' } } as never)
    renderWithProviders(<SubscriptionsView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Add subscription' }))
    await userEvent.type(screen.getByLabelText(/Calendar address/), 'http://example.org/f.ics')
    await userEvent.click(screen.getByRole('button', { name: 'Subscribe' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Use an https:// or webcal:// link.')
  })

  it('pauses and removes', async () => {
    proxy.listSubscriptions.mockResolvedValueOnce({ ok: true, subscriptions: [feed] })
    renderWithProviders(<SubscriptionsView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Pause' }))
    expect(proxy.setSubscriptionEnabled).toHaveBeenCalledWith('sub-1', false)
    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))
    const dialog = screen.getByRole('dialog', { name: 'Remove subscription?' })
    expect(dialog).toHaveTextContent('“Home” and its events are removed from all your devices.')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(proxy.removeSubscription).toHaveBeenCalledWith('sub-1'))
  })

  it('a failed read is a failure, not "No subscriptions."', async () => {
    proxy.listSubscriptions.mockResolvedValueOnce({ ok: false, error: { kind: 'error_internal' } })
    renderWithProviders(<SubscriptionsView />)
    expect(await screen.findByText('Something went wrong. Please try again.')).toBeInTheDocument()
    expect(screen.queryByText('No subscriptions.')).not.toBeInTheDocument()
  })
})
