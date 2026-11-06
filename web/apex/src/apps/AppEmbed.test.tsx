// Apex hosting of the per-origin apps (web-app-suite.md Wave 1, run B1): the generic AppEmbed,
// Drive's embed kept identical, Contacts' new one, and the launcher entry that reaches it.
// Since F4 both embeds are `HostedAppEmbed` over the app's registry entry (the per-app
// `DriveEmbed` / `ContactsEmbed` files are gone); the assertions are the ones they had.
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { AppEmbed, FIRST_PARTY_ALLOW } from './AppEmbed'
import { HostedAppEmbed } from './HostedAppEmbed'
import { findHostedApp } from './registry'
import { AppLauncher } from '../control/AppLauncher'
import { listApps } from '../control/apps/manifest'

describe('AppEmbed', () => {
  it('frames the given origin with the first-party permissions', () => {
    render(<AppEmbed origin="https://x.example" title="X" />)
    const frame = screen.getByTitle('X')
    expect(frame).toHaveAttribute('src', 'https://x.example')
    expect(frame).toHaveAttribute('allow', FIRST_PARTY_ALLOW)
  })

  it('Drive keeps its title, dev origin and allow list', () => {
    render(<HostedAppEmbed app={findHostedApp('drive')!} />)
    const frame = screen.getByTitle('Drive')
    expect(frame).toHaveAttribute('src', 'http://localhost:5174')
    expect(frame).toHaveAttribute('allow', 'clipboard-read; clipboard-write; publickey-credentials-get *')
  })

  it('Contacts frames its own dev origin', () => {
    render(<HostedAppEmbed app={findHostedApp('contacts')!} />)
    expect(screen.getByTitle('Contacts')).toHaveAttribute('src', 'http://localhost:5175')
  })
})

describe('launcher + Apps', () => {
  it('the launcher opens Contacts at /contacts', async () => {
    render(
      <MemoryRouter initialEntries={['/control/home']}>
        <Routes>
          <Route path="/control/home" element={<AppLauncher />} />
          <Route path="/contacts" element={<p>contacts route</p>} />
        </Routes>
      </MemoryRouter>,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Apps' }))
    await userEvent.click(screen.getByRole('button', { name: /Contacts/ }))
    expect(screen.getByText('contacts route')).toBeInTheDocument()
  })

  it('the Apps list carries Contacts with its launch path', () => {
    // ⚠ The first two, not the whole list: since F4 every registered app joins it, and a pin on the
    // exact list would fail the day the next app lands (the registry test pins the full set).
    expect(listApps().slice(0, 2).map((a) => [a.id, a.launchPath])).toEqual([
      ['drive', '/drive/files'],
      ['contacts', '/contacts'],
    ])
  })
})
