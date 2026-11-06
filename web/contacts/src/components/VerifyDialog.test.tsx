// The verify flow (threat model A24): the ONLY path to `setVerified(…, true)` is an explicit
// "They match" over digits that are on screen.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ContactDetail } from './ContactDetail'
import { contact, identity, proxy, resetProxy } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'
import { queryKeys } from '../lib/queryKeys'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)

beforeEach(() => resetProxy())

const FP = 'abcdef0123456789'

function withKey(verification: string) {
  proxy.listContacts.mockResolvedValue({
    ok: true,
    contacts: [contact({ personId: 'ada', displayName: 'Ada', trust: 'unverified', identities: [identity(FP, verification)] })],
  })
}

describe('verify flow', () => {
  it('shows the six digits, and only "They match" marks the key verified', async () => {
    withKey('UNVERIFIED')
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Verify' }))
    const dialog = screen.getByRole('dialog')
    expect(await within(dialog).findByTestId('sas-code')).toHaveTextContent('123 456')
    expect(proxy.contactSas).toHaveBeenCalledWith(FP)
    expect(proxy.setVerified).not.toHaveBeenCalled()

    await userEvent.click(within(dialog).getByRole('button', { name: 'They match' }))
    await waitFor(() => expect(proxy.setVerified).toHaveBeenCalledWith(FP, true))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('"They don’t match" closes without writing anything', async () => {
    withKey('UNVERIFIED')
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Verify' }))
    await within(screen.getByRole('dialog')).findByTestId('sas-code')
    await userEvent.click(screen.getByRole('button', { name: 'They don’t match' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(proxy.setVerified).not.toHaveBeenCalled()
  })

  // ⚠⚠ No digits → no confirm button. Confirming nothing is how a key gets verified unseen.
  it('offers no confirm at all when there is no code', async () => {
    withKey('UNVERIFIED')
    proxy.contactSas.mockResolvedValueOnce({ ok: true, sas: '' })
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Verify' }))
    expect(await screen.findByText(/The code is not available right now/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'They match' })).not.toBeInTheDocument()
    expect(proxy.setVerified).not.toHaveBeenCalled()
  })

  // ⚠⚠ A refetch that THROWS keeps the old digits in react-query's `data`. The confirm must go with
  // them: "They match" beside an error is a confirm for a code that is no longer what is shown.
  it('withdraws the confirm when a later read of the code fails', async () => {
    withKey('UNVERIFIED')
    const { client } = renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Verify' }))
    const dialog = screen.getByRole('dialog')
    await within(dialog).findByTestId('sas-code')
    expect(within(dialog).getByRole('button', { name: 'They match' })).toBeInTheDocument()

    proxy.contactSas.mockRejectedValueOnce(new Error('bridge timeout'))
    await client.refetchQueries({ queryKey: queryKeys.sas(FP) })

    await waitFor(() => expect(within(dialog).queryByTestId('sas-code')).not.toBeInTheDocument())
    expect(within(dialog).queryByRole('button', { name: 'They match' })).not.toBeInTheDocument()
    expect(proxy.setVerified).not.toHaveBeenCalled()
  })

  it('does not re-read the code when the window regains focus', async () => {
    withKey('UNVERIFIED')
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Verify' }))
    await within(screen.getByRole('dialog')).findByTestId('sas-code')
    const calls = proxy.contactSas.mock.calls.length
    window.dispatchEvent(new Event('visibilitychange'))
    window.dispatchEvent(new Event('focus'))
    await new Promise((r) => setTimeout(r, 20))
    expect(proxy.contactSas.mock.calls.length).toBe(calls)
  })

  it('renders a failed code read by its kind, with no confirm', async () => {
    withKey('UNVERIFIED')
    proxy.contactSas.mockResolvedValueOnce({ ok: false, error: { kind: 'error_contacts_unknown_key' } } as never)
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Verify' }))
    expect(await screen.findByText('That key is no longer known on this device.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'They match' })).not.toBeInTheDocument()
  })

  it('says so when the write changed nothing, instead of claiming success', async () => {
    withKey('UNVERIFIED')
    proxy.setVerified.mockResolvedValueOnce({ ok: true, changed: false })
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Verify' }))
    await userEvent.click(await screen.findByRole('button', { name: 'They match' }))
    expect(await screen.findByText(/Nothing changed/)).toBeInTheDocument()
  })

  it('un-verifies a VERIFIED key directly — the safe direction needs no confirm', async () => {
    withKey('VERIFIED')
    renderWithProviders(<ContactDetail personId="ada" onBack={() => {}} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Un-verify' }))
    await waitFor(() => expect(proxy.setVerified).toHaveBeenCalledWith(FP, false))
    expect(screen.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument()
  })
})
