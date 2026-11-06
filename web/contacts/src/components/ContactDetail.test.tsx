import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Contact } from '@tenshu/sdk/contact'
import { ContactDetail } from './ContactDetail'
import { contact, identity, proxy, resetProxy } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

function book(...contacts: Contact[]) {
  proxy.listContacts.mockResolvedValue({ ok: true, contacts })
}

const ada = () =>
  contact({
    personId: 'ada',
    displayName: 'Ada',
    trust: 'unverified',
    identities: [identity('abcdef0123456789aa', 'UNVERIFIED', 'Countess')],
    details: {
      phones: [{ label: 'mobile', value: '+1 555 0101' }],
      emails: [{ label: 'work', value: 'ada@example.org' }],
      addresses: [],
      organisation: 'Analytical Engines',
      note: '',
    },
  })

describe('ContactDetail', () => {
  it('renders the petname, the SDK trust, the keyring with the claim quoted, and the info', async () => {
    book(ada())
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    expect(await screen.findByRole('heading', { name: 'Ada' })).toBeInTheDocument()
    expect(screen.getByText('ABCDE-F0123-45678')).toBeInTheDocument()
    expect(screen.getByText('Introduced themselves as “Countess”')).toBeInTheDocument()
    expect(screen.getByText('ada@example.org')).toBeInTheDocument()
    expect(screen.getByText('Analytical Engines')).toBeInTheDocument()
  })

  // ⚠⚠ Q2b: the checkmark follows `trust`, never a keyring scan. This keyring has a VERIFIED key,
  // but the SDK says the contact needs re-verification — so no checkmark anywhere.
  it('shows no checkmark for a mixed keyring even though one key is VERIFIED', async () => {
    book(
      contact({
        personId: 'm',
        displayName: 'Mixed',
        trust: 'needsReverification',
        identities: [identity('aaaa', 'VERIFIED'), identity('bbbb', 'UNVERIFIED')],
      }),
    )
    renderWithProviders(<ContactDetail personId="m" onBack={() => {}} />)
    await screen.findByRole('heading', { name: 'Mixed' })
    expect(screen.getByText('Key changed')).toBeInTheDocument()
    expect(screen.queryByText('✓')).not.toBeInTheDocument()
  })

  it('shows the checkmark for an ATTESTED-only contact because the SDK says verified', async () => {
    book(contact({ personId: 'r', displayName: 'Rei', trust: 'verified', identities: [identity('cccc', 'ATTESTED')] }))
    renderWithProviders(<ContactDetail personId="r" onBack={() => {}} />)
    await screen.findByRole('heading', { name: 'Rei' })
    expect(screen.getByText('✓')).toBeInTheDocument()
    expect(screen.getByText('Attested')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Un-verify' })).not.toBeInTheDocument()
  })

  it('says "not found" for a person who is gone', async () => {
    book(ada())
    renderWithProviders(<ContactDetail personId="nobody" onBack={() => {}} />)
    expect(await screen.findByText('Contact not found')).toBeInTheDocument()
  })

  it('renames', async () => {
    book(ada())
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Rename' }))
    const field = screen.getByLabelText('Name')
    await userEvent.clear(field)
    await userEvent.type(field, 'Augusta')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(proxy.renameContact).toHaveBeenCalledWith('ada', 'Augusta'))
  })

  it('keeps the rename dialog open with the typed error when the write fails', async () => {
    book(ada())
    proxy.renameContact.mockResolvedValueOnce({ ok: false, error: { kind: 'error_not_found' } } as never)
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Rename' }))
    await userEvent.type(screen.getByLabelText('Name'), 'x')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('That contact is no longer available.')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  // ⚠⚠ Q7b — the confirm must say that deleting revokes nothing.
  it('deletes only after a confirm that carries the access warning', async () => {
    book(ada())
    const onBack = vi.fn()
    renderWithProviders(<ContactDetail personId="ada" onBack={onBack} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(/does not take away anything you have already shared/)).toBeInTheDocument()
    expect(proxy.deleteContact).not.toHaveBeenCalled()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(proxy.deleteContact).toHaveBeenCalledWith('ada'))
    expect(onBack).toHaveBeenCalled()
  })

  it('unlinks a key after confirming', async () => {
    book(ada())
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Remove key' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove key' }))
    await waitFor(() => expect(proxy.unlinkIdentity).toHaveBeenCalledWith('abcdef0123456789aa'))
  })

  it('relinks a key to the contact the user picks', async () => {
    book(ada(), contact({ personId: 'aug', displayName: 'Augusta' }))
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Move to another contact' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(/Its verification stays with the key/)).toBeInTheDocument()
    await userEvent.selectOptions(within(dialog).getByLabelText('Move to'), 'aug')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move' }))
    await waitFor(() => expect(proxy.relinkIdentity).toHaveBeenCalledWith('abcdef0123456789aa', 'aug'))
  })

  it('edits details and saves every key, with blank rows dropped', async () => {
    book(ada())
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Edit info' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '+ Add a phone number' }))
    const phones = within(dialog).getAllByLabelText('Phone')
    await userEvent.type(phones[1]!, ' 222 ')
    await userEvent.click(within(dialog).getByRole('button', { name: '+ Add an email address' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(proxy.saveContactDetails).toHaveBeenCalledWith('ada', {
        phones: [
          { label: 'mobile', value: '+1 555 0101' },
          { label: 'mobile', value: '222' },
        ],
        emails: [{ label: 'work', value: 'ada@example.org' }],
        addresses: [],
        organisation: 'Analytical Engines',
        note: '',
      }),
    )
  })

  it('exports the vCard as a local download', async () => {
    book(ada())
    const createObjectURL = vi.fn(() => 'blob:x')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Export .vcf' }))
    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(proxy.exportVcard).toHaveBeenCalledWith('ada')
    expect(createObjectURL).toHaveBeenCalled()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:x')
  })

  it('renders an export with nothing to export by its kind', async () => {
    book(ada())
    proxy.exportVcard.mockResolvedValueOnce({ ok: false, error: { kind: 'error_contacts_nothing_to_export' } } as never)
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Export .vcf' }))
    expect(await screen.findByText('This contact has nothing to export.')).toBeInTheDocument()
  })

  it('names a withheld photo instead of showing no photo, and can remove it', async () => {
    book(contact({ personId: 'big', displayName: 'Big', avatar: 'tooLarge' }))
    renderWithProviders(<ContactDetail personId="big" onBack={() => {}} />)
    expect(await screen.findByText('Their photo is too large to show here.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Remove photo' }))
    await waitFor(() => expect(proxy.clearAvatar).toHaveBeenCalledWith('big'))
  })

  it('merges with a picked contact, folding into the one the user keeps', async () => {
    book(ada(), contact({ personId: 'dup', displayName: 'Ada L.' }))
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Merge with…' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.selectOptions(within(dialog).getByLabelText('Combine with'), 'dup')
    expect(within(dialog).getByText('This cannot be undone.', { exact: false })).toBeInTheDocument()
    // Default keeps the connected one (Ada); swap to keep the other.
    await userEvent.click(within(dialog).getByLabelText('Ada L.'))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Combine' }))
    await waitFor(() => expect(proxy.mergeContacts).toHaveBeenCalledWith('ada', 'dup'))
  })
})
