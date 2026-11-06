import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TransferView } from './TransferView'
import { HOME, WORK, proxy, resetProxy, setBook } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())
afterEach(() => vi.restoreAllMocks())

const ics = (name = 'team.ics') => new File(['BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n'], name, { type: 'text/calendar' })

describe('TransferView — import', () => {
  it('imports a chosen file into the default calendar, with the file name as its origin', async () => {
    renderWithProviders(<TransferView />)
    const input = await screen.findByLabelText('Choose an .ics file to import')
    expect(input).toHaveAttribute('accept', '.ics,text/calendar')
    await waitFor(() => expect(screen.getByLabelText('Into calendar')).toHaveValue(WORK.calendarId))
    await userEvent.upload(input, ics())
    await waitFor(() => expect(proxy.importIcs).toHaveBeenCalledWith('BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n', WORK.calendarId, 'team.ics'))
    expect(await screen.findByRole('status')).toHaveTextContent('Imported 2 events from team.ics.')
  })

  it('imports a dropped file into the chosen calendar, and reports every partial failure', async () => {
    proxy.importIcs.mockResolvedValueOnce({
      ok: true,
      imported: 1,
      overrides: 1,
      skipped: 2,
      failed: 1,
      warnings: [{ eventId: 'e', part: 'reminders', kind: 'error_generic', message: 'x' }],
    } as never)
    renderWithProviders(<TransferView />)
    await userEvent.selectOptions(await screen.findByLabelText('Into calendar'), HOME.calendarId)
    fireEvent.drop(screen.getByTestId('drop-zone'), { dataTransfer: { files: [ics('home.ics')] } })
    const status = await screen.findByRole('status')
    expect(proxy.importIcs).toHaveBeenCalledWith(expect.any(String), HOME.calendarId, 'home.ics')
    expect(status).toHaveTextContent('Imported 1 event from home.ics.')
    expect(status).toHaveTextContent('Plus 1 changed occurrence.')
    expect(status).toHaveTextContent('2 entries in the file couldn’t be read and were skipped.')
    expect(status).toHaveTextContent('1 event couldn’t be saved.')
    expect(status).toHaveTextContent('1 event was imported without all of its details')
  })

  it('a file with no events renders by kind', async () => {
    proxy.importIcs.mockResolvedValueOnce({ ok: false, error: { kind: 'error_calendar_none_in_file' } } as never)
    renderWithProviders(<TransferView />)
    await userEvent.upload(await screen.findByLabelText('Choose an .ics file to import'), ics())
    expect(await screen.findByRole('alert')).toHaveTextContent('No events found in that file.')
  })

  it('with no calendar, asks for one first', async () => {
    setBook({ calendars: [] })
    renderWithProviders(<TransferView />)
    expect(await screen.findByText(/Create a calendar first/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Choose an .ics file to import')).not.toBeInTheDocument()
  })
})

describe('TransferView — export', () => {
  it('downloads one calendar as a Blob named after it', async () => {
    const created: Blob[] = []
    vi.spyOn(URL, 'createObjectURL').mockImplementation((b) => {
      created.push(b as Blob)
      return 'blob:x'
    })
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const clicked: HTMLAnchorElement[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this)
    })
    renderWithProviders(<TransferView />)
    await userEvent.selectOptions(await screen.findByLabelText('Calendar'), HOME.calendarId)
    await userEvent.click(screen.getByRole('button', { name: 'Download .ics' }))
    await waitFor(() => expect(clicked).toHaveLength(1))
    expect(proxy.exportIcs).toHaveBeenCalledWith(HOME.calendarId)
    expect(clicked[0]!.download).toBe('Home.ics')
    expect(created[0]!.type).toBe('text/calendar')
    expect(await created[0]!.text()).toBe('BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n')
  })

  it('"all calendars" exports with an empty id; a failure renders and downloads nothing', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    proxy.exportIcs.mockRejectedValueOnce(new Error('not permitted: calendar.exportIcs'))
    renderWithProviders(<TransferView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Download .ics' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Calendar is not allowed to do that.')
    expect(proxy.exportIcs).toHaveBeenCalledWith('')
    expect(click).not.toHaveBeenCalled()
  })
})
