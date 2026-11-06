import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ContactList } from './ContactList'
import { contact, proxy, resetProxy, streams } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

describe('ContactList', () => {
  it('renders the pushed book, live, with the SDK trust badge', async () => {
    renderWithProviders(<ContactList onOpen={() => {}} />)
    await waitFor(() => expect(streams.contacts.open()).toBe(1))
    act(() =>
      streams.contacts.push({
        ok: true,
        contacts: [contact({ displayName: 'Ada', trust: 'verified' }), contact({ displayName: 'Grace', trust: 'unverified' })],
      }),
    )
    expect(await screen.findByText('Ada')).toBeInTheDocument()
    expect(screen.getByText('Verified').closest('[data-trust]')).toHaveAttribute('data-trust', 'verified')
    expect(screen.getByText('Not verified')).toBeInTheDocument()

    act(() => streams.contacts.push({ ok: true, contacts: [contact({ displayName: 'Linus' })] }))
    expect(await screen.findByText('Linus')).toBeInTheDocument()
    expect(screen.queryByText('Ada')).not.toBeInTheDocument()
  })

  it('unsubscribes when the app unmounts', async () => {
    const view = renderWithProviders(<ContactList onOpen={() => {}} />)
    await waitFor(() => expect(streams.contacts.open()).toBe(1))
    view.unmount()
    expect(streams.contacts.open()).toBe(0)
  })

  it('filters by name, organisation and phone digits', async () => {
    proxy.listContacts.mockResolvedValueOnce({
      ok: true,
      contacts: [
        contact({ displayName: 'Ada', details: { phones: [{ label: 'm', value: '+44 7700 900123' }], emails: [], addresses: [], organisation: '', note: '' } }),
        contact({ displayName: 'Grace', details: { phones: [], emails: [], addresses: [], organisation: 'Navy', note: '' } }),
      ],
    })
    renderWithProviders(<ContactList onOpen={() => {}} />)
    await screen.findByText('Ada')
    const search = screen.getByRole('searchbox')
    await userEvent.type(search, 'navy')
    expect(screen.queryByText('Ada')).not.toBeInTheDocument()
    expect(screen.getByText('Grace')).toBeInTheDocument()
    await userEvent.clear(search)
    await userEvent.type(search, '7700900')
    expect(screen.getByText('Ada')).toBeInTheDocument()
    await userEvent.clear(search)
    await userEvent.type(search, 'zzz')
    expect(screen.getByText('No matches.')).toBeInTheDocument()
  })

  // ⚠⚠ A failed read is a failure, never an empty book (run A, web-app-suite.md §4.1).
  it('renders a typed read failure by its kind — never as "No contacts yet"', async () => {
    proxy.listContacts.mockResolvedValueOnce({ ok: false, error: { kind: 'error_unannounced_session', message: 'x' } })
    renderWithProviders(<ContactList onOpen={() => {}} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Tenshu needs to reconnect')
    expect(alert).toHaveAttribute('data-kind', 'error_unannounced_session')
    expect(screen.queryByText('No contacts yet')).not.toBeInTheDocument()
  })

  it('renders an unknown kind as the generic copy, not the raw key', async () => {
    proxy.listContacts.mockResolvedValueOnce({ ok: false, error: { kind: 'error_something_new' } })
    renderWithProviders(<ContactList onOpen={() => {}} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong. Please try again.')
  })

  it('renders a rejected bridge call as a connection error', async () => {
    proxy.listContacts.mockRejectedValueOnce(new Error("proxy call 'contact.list' timed out"))
    renderWithProviders(<ContactList onOpen={() => {}} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Tenshu took too long to answer')
  })

  it('shows the empty state only for a real, empty book', async () => {
    renderWithProviders(<ContactList onOpen={() => {}} />)
    expect(await screen.findByText('No contacts yet')).toBeInTheDocument()
  })

  it('a terminal stream error says live updates stopped, and reconnect resubscribes', async () => {
    renderWithProviders(<ContactList onOpen={() => {}} />)
    await waitFor(() => expect(streams.contacts.open()).toBe(1))
    act(() => streams.contacts.fail(new Error('contacts stream ended')))
    expect(await screen.findByText(/Live updates stopped/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Reconnect' }))
    await waitFor(() => expect(proxy.subscribeContacts).toHaveBeenCalledTimes(2))
    expect(screen.queryByText(/Live updates stopped/)).not.toBeInTheDocument()
  })

  it('adds a contact by name and opens it', async () => {
    const onOpen = vi.fn()
    renderWithProviders(<ContactList onOpen={onOpen} />)
    await userEvent.click(screen.getByRole('button', { name: 'Add contact' }))
    await userEvent.type(screen.getByLabelText('Name'), '  Barbara  ')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    await waitFor(() => expect(proxy.createContact).toHaveBeenCalledWith('Barbara'))
    expect(onOpen).toHaveBeenCalledWith('p-new')
  })

  it('imports a picked .vcf file through the SDK and reports the count', async () => {
    proxy.importVcards.mockResolvedValueOnce({ ok: true, imported: 2, cards: 2 })
    renderWithProviders(<ContactList onOpen={() => {}} />)
    const text = 'BEGIN:VCARD\r\nFN:A\r\nEND:VCARD\r\nBEGIN:VCARD\r\nFN:B\r\nEND:VCARD\r\n'
    await userEvent.upload(screen.getByTestId('vcf-input'), new File([text], 'people.vcf', { type: 'text/vcard' }))
    await waitFor(() => expect(proxy.importVcards).toHaveBeenCalledWith(text))
    expect(await screen.findByText('Imported 2 contacts.')).toBeInTheDocument()
  })

  it('reports a partial import as a count, and a file with no card by its kind', async () => {
    proxy.importVcards
      .mockResolvedValueOnce({ ok: true, imported: 1, cards: 3 })
      .mockResolvedValueOnce({ ok: false, error: { kind: 'error_contacts_none_in_file' } } as never)
    renderWithProviders(<ContactList onOpen={() => {}} />)
    const input = screen.getByTestId('vcf-input')
    await userEvent.upload(input, new File(['x'], 'a.vcf'))
    expect(await screen.findByText(/Imported 1 of 3 contacts/)).toBeInTheDocument()
    await userEvent.upload(input, new File(['not a card'], 'b.vcf'))
    expect(await screen.findByText('No contacts found in that file.')).toBeInTheDocument()
  })
})
