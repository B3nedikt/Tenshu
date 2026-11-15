// `AppsListPage` renders the installed apps from the live `AppRegistry`
// (seeded from the manifest). We assert the heading + a card per app with a
// link to the detail route, and the empty state when no apps are installed.
import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { AppsListPage } from './AppsListPage'
import { AppRegistryProvider } from './AppRegistryProvider'
import { MockAppRegistry, registrationsOf } from './appRegistry'
import { listApps } from './manifest'

function renderWith(registry: MockAppRegistry) {
  return render(
    <AppRegistryProvider registry={registry}>
      <MemoryRouter>
        <AppsListPage />
      </MemoryRouter>
    </AppRegistryProvider>,
  )
}

describe('AppsListPage', () => {
  it('renders the heading + intro copy', () => {
    renderWith(new MockAppRegistry())
    expect(screen.getByRole('heading', { name: 'Apps' })).toBeInTheDocument()
    expect(screen.getByText(/Every app you can open here/i)).toBeInTheDocument()
  })

  it('renders one card per installed app, each linking to the detail route', () => {
    renderWith(new MockAppRegistry())
    for (const app of listApps()) {
      const link = screen.getByRole('link', { name: new RegExp(app.label, 'i') })
      expect(link).toHaveAttribute('href', `/control/apps/${app.id}`)
      expect(within(link).getByText(`v${app.version}`)).toBeInTheDocument()
      const permissionLabel =
        app.permissions.length === 1 ? '1 permission' : `${app.permissions.length} permissions`
      expect(within(link).getByText(permissionLabel)).toBeInTheDocument()
    }
  })

  it('says on how many devices each app is installed, from the registrations', () => {
    renderWith(new MockAppRegistry(undefined, registrationsOf({ photos: ['Pixel 9', 'MacBook'] })))
    expect(within(screen.getByRole('link', { name: /Photos/ })).getByText('On 2 devices')).toBeInTheDocument()
    expect(within(screen.getByRole('link', { name: /^Fit/ })).getByText('Not on your devices yet')).toBeInTheDocument()
  })

  // ⚠⚠ No answer is not "on no devices".
  it('says nothing about devices while the read is loading or failed', () => {
    renderWith(new MockAppRegistry(undefined, { kind: 'unavailable', message: 'offline' }))
    expect(screen.queryByText(/^On \d+ devices?$|^Not on your devices yet$/)).toBeNull()
  })

  it('renders the empty state when no apps are installed', () => {
    renderWith(new MockAppRegistry([]))
    expect(screen.getByText(/No apps yet\./i)).toBeInTheDocument()
  })
})
