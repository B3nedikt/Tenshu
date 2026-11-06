import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CalendarView } from './CalendarView'
import { HOME, occurrence, proxy, resetProxy, setBook, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

const cell = (date: string) => document.querySelector(`[data-date="${date}"]`) as HTMLElement

describe('CalendarView — month', () => {
  it('lays the window out on its days, live, and opens the day from its date', async () => {
    setBook({ occurrences: [occurrence({ title: 'Standup', date: '2026-10-26' })] })
    renderWithProviders(<CalendarView initialDate="2026-10-14" />)
    expect(await screen.findByRole('heading', { name: 'October 2026' })).toBeInTheDocument()
    await screen.findByRole('grid', { name: 'Month' })
    expect(within(cell('2026-10-26')).getByText('Standup')).toBeInTheDocument()
    // The window read is padded a day each side of the six-row grid.
    const [from, to] = proxy.occurrences.mock.calls[0]!
    expect(from < '2026-10-01' && to > '2026-10-31').toBe(true)

    // The stream replaces the seed: a write elsewhere shows up without a refetch.
    await waitFor(() => expect(streams.occurrences.open()).toBe(1))
    act(() =>
      streams.occurrences.push({
        ok: true,
        occurrences: [occurrence({ title: 'Retro', date: '2026-10-30' })],
        readerZone: 'Europe/Berlin',
        readerZoneFallback: false,
      }),
    )
    expect(await within(cell('2026-10-30')).findByText('Retro')).toBeInTheDocument()
    expect(screen.queryByText('Standup')).not.toBeInTheDocument()

    await userEvent.click(within(cell('2026-10-30')).getByRole('button', { name: /October 30, 2026/ }))
    expect(await screen.findByRole('grid', { name: 'Day' })).toBeInTheDocument()
  })

  it('hides a hidden calendar’s events (the hidden-set stream, not the list)', async () => {
    setBook({
      occurrences: [
        occurrence({ title: 'Work thing', date: '2026-10-26' }),
        occurrence({ title: 'Home thing', date: '2026-10-26', calendarId: HOME.calendarId }),
      ],
    })
    renderWithProviders(<CalendarView initialDate="2026-10-14" />)
    expect(await screen.findByText('Home thing')).toBeInTheDocument()
    act(() => streams.hidden.push({ ok: true, hidden: [HOME.calendarId] }))
    await waitFor(() => expect(screen.queryByText('Home thing')).not.toBeInTheDocument())
    expect(screen.getByText('Work thing')).toBeInTheDocument()
  })

  it('pages by month', async () => {
    renderWithProviders(<CalendarView initialDate="2026-10-14" />)
    await screen.findByRole('heading', { name: 'October 2026' })
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByRole('heading', { name: 'November 2026' })).toBeInTheDocument()
    await waitFor(() => expect(proxy.occurrences.mock.calls.at(-1)![1] > '2026-11-30').toBe(true))
  })
})

describe('CalendarView — week, day and agenda', () => {
  it('week: all-day row and timed events in their columns', async () => {
    setBook({
      occurrences: [
        occurrence({ title: 'Offsite', date: '2026-10-27', allDay: true, startLocal: '2026-10-27T00:00', endLocal: '2026-10-28T00:00' }),
        occurrence({ title: 'Review', date: '2026-10-28', from: '14:00', to: '15:00' }),
      ],
    })
    renderWithProviders(<CalendarView initialDate="2026-10-28" initialView="week" />)
    const grid = await screen.findByRole('grid', { name: 'Week' })
    expect(within(document.querySelector('[data-allday="2026-10-27"]') as HTMLElement).getByText('Offsite')).toBeInTheDocument()
    const review = within(document.querySelector('[data-day="2026-10-28"]') as HTMLElement).getByText('Review')
    expect(review.closest('button')).toHaveStyle({ top: `${14 * 44}px` })
    expect(within(grid).getAllByRole('columnheader')).toHaveLength(7)
  })

  it('day: clicking empty time creates there; clicking an event opens it', async () => {
    setBook({ occurrences: [occurrence({ title: 'Dentist', date: '2026-10-28', from: '08:00', to: '08:30' })] })
    renderWithProviders(<CalendarView initialDate="2026-10-28" initialView="day" />)
    await userEvent.click(await screen.findByText('Dentist'))
    expect(await screen.findByRole('dialog', { name: 'Edit event' })).toBeInTheDocument()
    expect(screen.getByLabelText('Title')).toHaveValue('Dentist')
  })

  it('agenda: the days that have something on them, and an honest empty stretch', async () => {
    setBook({ occurrences: [occurrence({ title: 'Flight', date: '2026-10-29', location: 'BER' })] })
    renderWithProviders(<CalendarView initialDate="2026-10-28" initialView="agenda" />)
    const list = await screen.findByRole('list', { name: 'Agenda' })
    expect(within(list).getByText('Flight')).toBeInTheDocument()
    expect(within(list).getByText('BER')).toBeInTheDocument()
    expect(within(list).getByRole('heading', { name: /Thursday, October 29, 2026/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('Nothing scheduled in these 30 days.')).toBeInTheDocument()
  })
})

describe('CalendarView — the empty book, zones and failures', () => {
  it('an empty book asks for a calendar (no seed), and the first one becomes the default', async () => {
    setBook({ calendars: [] })
    proxy.defaultCalendar.mockResolvedValue({ ok: true, calendarId: '' })
    renderWithProviders(<CalendarView />)
    expect(await screen.findByRole('heading', { name: 'No calendars yet' })).toBeInTheDocument()
    expect(proxy.saveCalendar).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Create a calendar' }))
    await userEvent.type(screen.getByLabelText('Name'), 'Personal')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(proxy.saveCalendar).toHaveBeenCalledWith('', 'Personal', expect.any(Number), false))
    expect(proxy.setDefaultCalendar).toHaveBeenCalledWith('cal-new')
  })

  it('says when times follow the browser’s clock (readerZoneFallback)', async () => {
    proxy.occurrences.mockResolvedValue({ ok: true, occurrences: [], readerZone: 'Etc/Unknown', readerZoneFallback: true })
    renderWithProviders(<CalendarView initialDate="2026-10-14" />)
    expect(await screen.findByText(/Times shown in your browser’s clock/)).toBeInTheDocument()
  })

  it('marks an event whose zone this browser cannot resolve', async () => {
    setBook({ occurrences: [occurrence({ title: 'Far away', date: '2026-10-26', zoneId: 'America/Nowhere', zoneUnresolved: true })] })
    renderWithProviders(<CalendarView initialDate="2026-10-14" />)
    const chip = (await screen.findByText('Far away')).closest('button')!
    expect(within(chip).getByRole('img', { name: 'Unknown time zone' })).toBeInTheDocument()
  })

  // ⚠⚠ A failure is a failure, never an empty calendar.
  it('renders a failed calendars read by its kind, with a retry', async () => {
    proxy.listCalendars.mockResolvedValueOnce({ ok: false, error: { kind: 'error_calendar_no_device' } })
    renderWithProviders(<CalendarView />)
    expect(await screen.findByRole('alert')).toHaveAttribute('data-kind', 'error_calendar_no_device')
    expect(screen.getByRole('alert')).toHaveTextContent('isn’t set up as one of your devices')
    expect(screen.queryByRole('heading', { name: 'No calendars yet' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: /\d{4}/ })).toBeInTheDocument()
  })

  it('renders a rejected window read and an unknown kind without an empty grid', async () => {
    proxy.occurrences.mockRejectedValueOnce(new Error('not permitted: calendar.occurrences'))
    renderWithProviders(<CalendarView initialDate="2026-10-14" />)
    expect(await screen.findByText('Calendar is not allowed to do that.')).toBeInTheDocument()
    expect(screen.queryByRole('grid')).not.toBeInTheDocument()
  })

  it('a typed window failure with a kind it has no copy for reads as the generic one', async () => {
    proxy.occurrences.mockResolvedValueOnce({ ok: false, error: { kind: 'error_from_the_future', message: 'x' } })
    renderWithProviders(<CalendarView initialDate="2026-10-14" />)
    expect(await screen.findByText('Something went wrong. Please try again.')).toBeInTheDocument()
    expect(screen.queryByText('error_from_the_future')).not.toBeInTheDocument()
  })

  it('a dead occurrence stream says so and reconnects', async () => {
    renderWithProviders(<CalendarView initialDate="2026-10-14" />)
    await waitFor(() => expect(streams.occurrences.open()).toBe(1))
    act(() => streams.occurrences.fail(new Error('collector died')))
    expect(await screen.findByText(/Live updates stopped/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Reconnect' }))
    await waitFor(() => expect(screen.queryByText(/Live updates stopped/)).not.toBeInTheDocument())
    expect(streams.occurrences.open()).toBe(1)
  })
})
