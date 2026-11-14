// The overview's dock + "Your apps" dialog over a fake store (docs/proposals/web-apex-home.md H2).
import { describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { OverviewPage } from './OverviewPage'
import { ShownAppsProvider } from './ShownAppsProvider'
import { AppLauncher } from '../control/AppLauncher'
import { fakeShownApps, type FakeShownApps } from '../test/fakeShownApps'
import type { ShownAppsSource } from './shownAppsContext'
import { AppRegistryProvider } from '../control/apps/AppRegistryProvider'
import { MockAppRegistry, registrationsOf, type RegistrationsState } from '../control/apps/appRegistry'

vi.mock('../control/recoveryStatus', () => ({
  getRecoveryStatus: () => Promise.resolve({ kind: 'verified', verifiedAtMs: 0 }),
}))

function renderAt(path: string, source: ShownAppsSource, registry = new MockAppRegistry()) {
  return render(
    <AppRegistryProvider registry={registry}>
      <ShownAppsProvider source={source}>
        <MemoryRouter initialEntries={[path]}>
          <AppLauncher />
          <Routes>
            <Route path="/overview" element={<OverviewPage />} />
            <Route path="/overview/apps" element={<OverviewPage editingApps />} />
          </Routes>
        </MemoryRouter>
      </ShownAppsProvider>
    </AppRegistryProvider>,
  )
}

// `hidden`: behind the open modal the page is aria-hidden (Radix), so the dock is only reachable so.
async function dockNames(hidden = false) {
  const dock = await screen.findByRole('region', { name: 'Your apps', hidden })
  return within(dock).getAllByRole('link', { hidden }).map(l => l.textContent)
}

describe('overview dock', () => {
  it('lists the stored apps in order, Drive forced first, then the Add tile', async () => {
    renderAt('/overview', fakeShownApps(['fit', 'photos']))
    await waitFor(async () => expect(await dockNames()).toEqual(['Drive', 'Fit', 'Photos', 'Add app']))
  })

  // ⚠⚠ A failed read must never hide apps: show all, say so, and offer no editing.
  it('shows every app and says so when the list cannot be read', async () => {
    renderAt('/overview', fakeShownApps([], { readError: 'offline' }))
    expect(await screen.findByRole('status')).toHaveTextContent("Couldn't load your app choices")
    const names = await dockNames()
    expect(names).toContain('Mia')
    expect(names).not.toContain('Add app')
  })

  it('the launcher lists only the shown apps', async () => {
    renderAt('/overview', fakeShownApps(['drive', 'contacts']))
    await dockNames()
    await userEvent.click(screen.getByRole('button', { name: 'Apps' }))
    const launcher = screen.getByRole('dialog', { name: 'Tenshu apps' })
    expect(within(launcher).getByRole('button', { name: 'Contacts' })).toBeInTheDocument()
    expect(within(launcher).queryByRole('button', { name: 'Photos' })).toBeNull()
    expect(within(launcher).getByRole('button', { name: 'Your apps…' })).toBeInTheDocument()
  })
})

describe('Your apps dialog', () => {
  async function openDialog(source: FakeShownApps) {
    renderAt('/overview/apps', source)
    return screen.findByRole('dialog', { name: 'Your apps' })
  }

  it('Drive is always on and has no Remove', async () => {
    const dialog = await openDialog(fakeShownApps())
    expect(within(dialog).getByText('Always on')).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Remove Drive from your overview' })).toBeNull()
  })

  it('adding writes the whole list and the dock follows', async () => {
    const source = fakeShownApps()
    const dialog = await openDialog(source)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add Fit to your overview' }))
    expect(source.writes).toEqual([['drive', 'photos', 'fit']])
    await waitFor(async () => expect(await dockNames(true)).toEqual(['Drive', 'Photos', 'Fit', 'Add app']))
  })

  it('removing hides the app and says its data stays', async () => {
    const source = fakeShownApps(['drive', 'photos'])
    const dialog = await openDialog(source)
    expect(within(dialog).getByText(/Its data stays in your realm/)).toBeInTheDocument()
    expect(within(dialog).getByText('Uses Drive')).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove Photos from your overview' }))
    expect(source.writes).toEqual([['drive']])
  })

  // ⚠⚠ The second click lands before the store has re-emitted the first write (the fake, like the
  // real store, resolves the write first and emits later). Built from the stale list it would drop Fit.
  it('two quick adds keep both', async () => {
    const source = fakeShownApps()
    const dialog = await openDialog(source)
    source.holdEmits()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add Fit to your overview' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add Contacts to your overview' }))
    expect(source.writes.at(-1)).toEqual(['drive', 'photos', 'fit', 'contacts'])
    act(() => source.releaseEmits())
    await waitFor(async () =>
      expect(await dockNames(true)).toEqual(['Drive', 'Photos', 'Fit', 'Contacts', 'Add app']),
    )
  })

  it('a failed write is reported and nothing moves', async () => {
    const source = fakeShownApps(['drive', 'photos'])
    source.failWrites(true)
    const dialog = await openDialog(source)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove Photos from your overview' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent("Couldn't save that change")
    expect(within(dialog).getByRole('button', { name: 'Remove Photos from your overview' })).toBeInTheDocument()
  })
})

// H6 (web-apex-home.md §3.5): "You use it on <device>" under More apps, from the realm's registrations.
describe('Your apps dialog — where you already use an app', () => {
  async function openWith(registrations: RegistrationsState) {
    const registry = new MockAppRegistry(undefined, registrations)
    renderAt('/overview/apps', fakeShownApps(['drive']), registry)
    return { registry, dialog: await screen.findByRole('dialog', { name: 'Your apps' }) }
  }
  const row = (dialog: HTMLElement, app: string) =>
    within(dialog).getByRole('button', { name: `Add ${app} to your overview` }).closest('li')!

  it('names the device an app is registered on', async () => {
    const { dialog } = await openWith(registrationsOf({ fit: ['Pixel 9'] }))
    expect(within(row(dialog, 'Fit')).getByText('You use it on Pixel 9')).toBeInTheDocument()
  })

  it('names two devices, then counts the rest', async () => {
    const { dialog } = await openWith(
      registrationsOf({ fit: ['Pixel 9', 'MacBook'], timeline: ['Pixel 9', 'MacBook', 'iPad'] }),
    )
    expect(within(row(dialog, 'Fit')).getByText('You use it on MacBook and Pixel 9')).toBeInTheDocument()
    expect(within(row(dialog, 'Timeline')).getByText('You use it on iPad and 2 other devices')).toBeInTheDocument()
  })

  // ⚠⚠ Never a guessed line: an unnamed device, no registration, loading and a failed read all say nothing.
  it.each<[string, RegistrationsState]>([
    ['loading', { kind: 'loading' }],
    ['a failed read', { kind: 'unavailable', message: 'offline' }],
    ['no registration', registrationsOf({})],
    ['a device the roster cannot name', registrationsOf({ fit: [null] })],
  ])('shows no line for %s', async (_, registrations) => {
    const { dialog } = await openWith(registrations)
    expect(within(dialog).queryByText(/You use it on/)).toBeNull()
  })

  it('a shown app gets no suggestion — it is already on the overview', async () => {
    const { dialog } = await openWith(registrationsOf({ drive: ['Pixel 9'] }))
    expect(within(dialog).queryByText(/You use it on/)).toBeNull()
  })

  it('the line appears when the read lands', async () => {
    const { registry, dialog } = await openWith({ kind: 'loading' })
    expect(within(dialog).queryByText(/You use it on/)).toBeNull()
    act(() => registry.setRegistrations(registrationsOf({ fit: ['Pixel 9'] })))
    expect(await within(dialog).findByText('You use it on Pixel 9')).toBeInTheDocument()
  })
})
