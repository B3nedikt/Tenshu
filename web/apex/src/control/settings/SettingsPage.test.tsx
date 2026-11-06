// Control → Settings index (web-app-suite.md §2 Control row). Every row must lead to a page the
// apex really has, native-only entries must be absent (not disabled), and the in-app privacy policy
// stays reachable (App Store 5.1.1(i), Play User Data policy).
import { afterEach, describe, expect, it } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import runtimeAppSource from '../../RuntimeApp.tsx?raw'
import i18n from '../../i18n'
import { SettingsPage } from './SettingsPage'
import { SETTINGS_ENTRIES } from './settingsEntries'

afterEach(async () => {
  await i18n.changeLanguage('en')
})

function renderPage() {
  render(
    <MemoryRouter>
      <SettingsPage />
    </MemoryRouter>,
  )
}

describe('SettingsPage', () => {
  it('lists the Control settings the web has, in native order, each linking to its page', () => {
    renderPage()
    const [list] = screen.getAllByRole('list')
    if (!list) throw new Error('no settings list')
    const links = within(list).getAllByRole('link')
    expect(links.map(l => l.getAttribute('href'))).toEqual([
      '/control/data/devices',
      '/control/data/background-ingest',
      '/control/settings/recovery',
    ])
    // "Devices", not native's "Sync & devices": the page is the device list, not a sync dashboard.
    expect(screen.getByRole('link', { name: /^Devices/ })).toBeInTheDocument()
    expect(screen.queryByText(/Sync/)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Background ingest/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Account recovery/ })).toBeInTheDocument()
  })

  // ⚠ The guard against a dead row: an entry whose route RuntimeApp does not declare would render a
  // link that bounces the user to "/" through the catch-all.
  it('only links routes RuntimeApp actually declares', () => {
    for (const entry of SETTINGS_ENTRIES) {
      expect(runtimeAppSource).toContain(`path="${entry.to.replace(/^\//, '')}"`)
    }
  })

  it('omits native-only and not-yet-built entries rather than showing them dead', () => {
    renderPage()
    for (const name of [/Offline maps/i, /Models/i, /Background sync/i, /Unlock/i, /Errors/i, /Advanced/i]) {
      expect(screen.queryByText(name)).not.toBeInTheDocument()
    }
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText(/Coming in/i)).not.toBeInTheDocument()
  })

  it('links the English privacy policy, opening outside the app', () => {
    renderPage()
    const link = screen.getByRole('link', { name: 'Privacy policy' })
    expect(link).toHaveAttribute('href', 'https://tenshu.app/legal/privacy/en')
    expect(link).toHaveAttribute('target', '_blank')
  })

  it('links the terms of use beside the policy', () => {
    renderPage()
    const link = screen.getByRole('link', { name: 'Terms of use' })
    expect(link).toHaveAttribute('href', 'https://tenshu.app/legal/terms/en')
    expect(link).toHaveAttribute('target', '_blank')
  })

  it('renders in German, policy included', async () => {
    await act(() => i18n.changeLanguage('de'))
    renderPage()
    expect(screen.getByRole('heading', { name: 'Einstellungen' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Datenschutzerklärung' })).toHaveAttribute(
      'href',
      'https://tenshu.app/legal/privacy/de',
    )
  })

  it('renders in French, policy included', async () => {
    await act(() => i18n.changeLanguage('fr'))
    renderPage()
    expect(screen.getByRole('heading', { name: 'Réglages' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Politique de confidentialité' })).toHaveAttribute(
      'href',
      'https://tenshu.app/legal/privacy/fr',
    )
    expect(screen.getByRole('link', { name: 'Conditions d’utilisation' })).toHaveAttribute(
      'href',
      'https://tenshu.app/legal/terms/fr',
    )
  })
})
