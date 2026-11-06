import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CalendarsView } from './CalendarsView'
import { HOME, WORK, proxy, resetProxy, setBook, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'
import { derivedCalendarId } from '../lib/calendarId'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

const row = (name: string) => screen.getByText(name).closest('li') as HTMLElement

describe('CalendarsView', () => {
  it('lists the calendars with the default marked, live', async () => {
    renderWithProviders(<CalendarsView />)
    expect(await screen.findByText('Work')).toBeInTheDocument()
    expect(within(row('Work')).getByText('Default')).toBeInTheDocument()
    await waitFor(() => expect(streams.calendars.open()).toBe(1))
    act(() => streams.calendars.push({ ok: true, calendars: [WORK, HOME, { ...HOME, calendarId: 'cal-x', name: 'Gym' }] }))
    expect(await screen.findByText('Gym')).toBeInTheDocument()
  })

  it('show/hide is this browser’s, and follows the hidden-set stream', async () => {
    renderWithProviders(<CalendarsView />)
    const box = await screen.findByRole('checkbox', { name: 'Show Home' })
    await waitFor(() => expect(box).toBeChecked())
    await userEvent.click(box)
    expect(proxy.setCalendarVisible).toHaveBeenCalledWith(HOME.calendarId, false)
    act(() => streams.hidden.push({ ok: true, hidden: [HOME.calendarId] }))
    await waitFor(() => expect(box).not.toBeChecked())
    expect(screen.getByText('Showing and hiding a calendar applies to this browser only.')).toBeInTheDocument()
  })

  it('a refused visibility write renders by kind', async () => {
    proxy.setCalendarVisible.mockResolvedValueOnce({ ok: false, error: { kind: 'error_calendar_no_device' } } as never)
    renderWithProviders(<CalendarsView />)
    const box = await screen.findByRole('checkbox', { name: 'Show Home' })
    await waitFor(() => expect(box).toBeEnabled())
    await userEvent.click(box)
    expect(await screen.findByRole('alert')).toHaveAttribute('data-kind', 'error_calendar_no_device')
  })

  it('renames and recolours, keeping the lock-screen flag', async () => {
    renderWithProviders(<CalendarsView />)
    await userEvent.click(within(await screen.findByText('Home').then((e) => e.closest('li') as HTMLElement)).getByRole('button', { name: 'Edit' }))
    const name = screen.getByLabelText('Name')
    await userEvent.clear(name)
    await userEvent.type(name, 'Family')
    await userEvent.click(screen.getByRole('button', { name: 'Colour 5' }))
    expect(screen.getByLabelText(/Keep details off the lock screen/)).toBeChecked()
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(proxy.saveCalendar).toHaveBeenCalledWith(HOME.calendarId, 'Family', 0xff9c27b0 | 0, true))
    // A rename never re-points the default.
    expect(proxy.setDefaultCalendar).not.toHaveBeenCalled()
  })

  it('refuses a new calendar whose derived id is taken (it would overwrite that one)', async () => {
    setBook({ calendars: [{ ...WORK, calendarId: derivedCalendarId('Work'), name: 'Job' }] })
    renderWithProviders(<CalendarsView />)
    await userEvent.click(await screen.findByRole('button', { name: 'New calendar' }))
    await userEvent.type(screen.getByLabelText('Name'), ' work ')
    expect(screen.getByText('You already have a calendar with this name.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled()
  })

  it('creates a calendar in the next free colour; the default stays put', async () => {
    renderWithProviders(<CalendarsView />)
    await userEvent.click(await screen.findByRole('button', { name: 'New calendar' }))
    await userEvent.type(screen.getByLabelText('Name'), 'Gym')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))
    // Blue and green are taken: red.
    await waitFor(() => expect(proxy.saveCalendar).toHaveBeenCalledWith('', 'Gym', 0xffdb4437 | 0, false))
    expect(proxy.setDefaultCalendar).not.toHaveBeenCalled()
  })

  it('deleting warns that its events go too', async () => {
    renderWithProviders(<CalendarsView />)
    await userEvent.click(within(await screen.findByText('Home').then((e) => e.closest('li') as HTMLElement)).getByRole('button', { name: 'Delete' }))
    const dialog = screen.getByRole('dialog', { name: 'Delete “Home”?' })
    expect(dialog).toHaveTextContent('Every event in this calendar is deleted too')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(proxy.deleteCalendar).toHaveBeenCalledWith(HOME.calendarId))
  })

  it('makes another calendar the default', async () => {
    renderWithProviders(<CalendarsView />)
    await screen.findByText('Home')
    await userEvent.click(within(row('Home')).getByRole('button', { name: 'Make default' }))
    expect(proxy.setDefaultCalendar).toHaveBeenCalledWith(HOME.calendarId)
  })

  it('a failed list read is a failure, not an empty book', async () => {
    proxy.listCalendars.mockRejectedValueOnce(new Error('no answer'))
    renderWithProviders(<CalendarsView />)
    expect(await screen.findByText(/Couldn’t reach Tenshu/)).toBeInTheDocument()
    expect(screen.queryByText(/Create a calendar to start/)).not.toBeInTheDocument()
  })
})
