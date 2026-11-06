import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DuplicatesView } from './DuplicatesView'
import { contact, identity, proxy, resetProxy } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

const phone = (value: string) => ({ phones: [{ label: 'm', value }], emails: [], addresses: [], organisation: '', note: '' })

describe('DuplicatesView', () => {
  it('suggests a pair, writes nothing until confirmed, then merges into the kept side', async () => {
    proxy.listContacts.mockResolvedValue({
      ok: true,
      contacts: [
        contact({ personId: 'a', displayName: 'Ada', identities: [identity('k', 'UNVERIFIED')], details: phone('+44 7700 900123') }),
        contact({ personId: 'b', displayName: 'A. Lovelace', details: phone('07700 900123') }),
      ],
    })
    renderWithProviders(<DuplicatesView />)
    expect(await screen.findByText('“Ada” and “A. Lovelace”')).toBeInTheDocument()
    expect(screen.getByText('Same phone number')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Review' }))
    const dialog = screen.getByRole('dialog')
    // Defaults to keeping the connected contact.
    expect(within(dialog).getByLabelText('Ada')).toBeChecked()
    expect(proxy.mergeContacts).not.toHaveBeenCalled()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Combine' }))
    await waitFor(() => expect(proxy.mergeContacts).toHaveBeenCalledWith('b', 'a'))
  })

  it('never suggests two connected contacts on name alone', async () => {
    proxy.listContacts.mockResolvedValue({
      ok: true,
      contacts: [
        contact({ displayName: 'Sam', identities: [identity('k1', 'UNVERIFIED')] }),
        contact({ displayName: 'sam', identities: [identity('k2', 'UNVERIFIED')] }),
      ],
    })
    renderWithProviders(<DuplicatesView />)
    expect(await screen.findByText('No duplicates found')).toBeInTheDocument()
  })

  it('marks a name-only suggestion as a question', async () => {
    proxy.listContacts.mockResolvedValue({ ok: true, contacts: [contact({ displayName: 'Sam' }), contact({ displayName: ' sam ' })] })
    renderWithProviders(<DuplicatesView />)
    expect(await screen.findByText(/Same name.*which two people can share/)).toBeInTheDocument()
  })

  it('renders a merge failure by its kind', async () => {
    proxy.listContacts.mockResolvedValue({ ok: true, contacts: [contact({ displayName: 'Sam' }), contact({ displayName: 'Sam' })] })
    proxy.mergeContacts.mockResolvedValueOnce({ ok: false, error: { kind: 'error_not_found' } } as never)
    renderWithProviders(<DuplicatesView />)
    await userEvent.click(await screen.findByRole('button', { name: 'Review' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Combine' }))
    expect(await screen.findByText('That contact is no longer available.')).toBeInTheDocument()
  })
})
