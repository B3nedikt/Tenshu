// Edit mode over fake stores (docs/proposals/web-apex-home.md §4.12): widgets, not app tiles.
import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { OverviewPage } from './OverviewPage'
import { ShownAppsProvider, WidgetsProvider } from './ShownAppsProvider'
import { GlanceGatewayContext, type GatewayCall } from './glance/glanceGateway'
import { fakeShownApps, type FakeShownApps } from '../test/fakeShownApps'

vi.mock('../control/recoveryStatus', () => ({
  getRecoveryStatus: () => Promise.resolve({ kind: 'verified', verifiedAtMs: 0 }),
}))

// Every card's load answers "nothing": the cards draw, which is all these tests need.
const gateway: GatewayCall = async () => null

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderAt(path: string, apps: FakeShownApps, widgets: FakeShownApps) {
  return render(
    <GlanceGatewayContext.Provider value={gateway}>
      <ShownAppsProvider source={apps}>
        <WidgetsProvider source={widgets}>
          <MemoryRouter initialEntries={[path]}>
            <Where />
            <Routes>
              <Route path="/overview" element={<OverviewPage />} />
              <Route path="/overview/customize" element={<OverviewPage customizing />} />
            </Routes>
          </MemoryRouter>
        </WidgetsProvider>
      </ShownAppsProvider>
    </GlanceGatewayContext.Provider>,
  )
}

// The widgets in edit mode (the mode fieldset is a group too, so not every group).
const groups = () => [...document.querySelectorAll('[role="group"][draggable]')].map(g => g.getAttribute('aria-label'))

describe('outside edit mode', () => {
  // ⚠ The everyday page must not be rearrangeable by a stray drag or click.
  it('nothing is draggable and no widget has edit controls', async () => {
    renderAt('/overview', fakeShownApps(['drive', 'photos']), fakeShownApps())
    await screen.findByRole('article', { name: 'Photos' })
    expect(document.querySelector('[draggable="true"]')).toBeNull()
    expect(screen.queryByRole('button', { name: /^Remove / })).toBeNull()
    expect(screen.getByRole('link', { name: 'Customize' })).toHaveAttribute('href', '/overview/customize')
  })

  it('D-W1: never edited, the page is Your apps, then the installed apps’ cards', async () => {
    renderAt('/overview', fakeShownApps(['drive', 'fit', 'photos']), fakeShownApps())
    await screen.findByRole('article', { name: 'Fit' })
    const cards = screen.getAllByRole('article').map(a => a.getAttribute('aria-labelledby'))
    expect(cards).toEqual(['glance-drive', 'glance-fit', 'glance-photos'])
    expect(screen.getByRole('region', { name: 'Your apps' })).toBeInTheDocument()
  })
})

describe('edit mode', () => {
  async function edit(apps: string[], widgets: string[] = []) {
    const appsSource = fakeShownApps(apps)
    const widgetsSource = fakeShownApps(widgets)
    renderAt('/overview/customize', appsSource, widgetsSource)
    await screen.findByRole('button', { name: 'Add widget' })
    await screen.findAllByRole('button', { name: /^Remove / })
    return { appsSource, widgetsSource }
  }

  it('every widget gets a − and moves; content is inert, so nothing navigates', async () => {
    await edit(['drive', 'photos'])
    expect(groups()).toEqual(['Your apps', 'Drive', 'Photos'])
    for (const name of ['Your apps', 'Drive', 'Photos']) {
      expect(screen.getByRole('button', { name: `Remove ${name}` })).toBeInTheDocument()
    }
    // Every link sits under `inert` (jsdom does not prune inert from the accessibility tree, so the
    // attribute is what is checked): nothing to click or tab to mid-edit.
    const links = document.querySelectorAll('a')
    expect(links.length).toBeGreaterThan(0)
    links.forEach(link => expect(link.closest('[inert]')).not.toBeNull())
  })

  it('removing writes the widget list at once, never the apps, and Undo puts it back', async () => {
    const { appsSource, widgetsSource } = await edit(['drive', 'photos'])
    await userEvent.click(screen.getByRole('button', { name: 'Remove Drive' }))
    expect(widgetsSource.writes.at(-1)).toEqual(['apps', 'app:photos', 'hidden:app:drive'])
    // ⚠⚠ Removing a widget never uninstalls.
    expect(appsSource.writes).toEqual([])
    await waitFor(() => expect(screen.getByRole('button', { name: 'Remove Photos' })).toHaveFocus())
    const toast = (await screen.findByText('Drive removed. Add it back any time.')).parentElement!
    await userEvent.click(within(toast).getByRole('button', { name: 'Undo' }))
    expect(widgetsSource.writes.at(-1)).toEqual(['apps', 'app:drive', 'app:photos'])
  })

  it('moves by button, announces the position, and disables the ends', async () => {
    const { widgetsSource } = await edit(['drive', 'photos'])
    expect(screen.getByRole('button', { name: 'Move Your apps earlier' })).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('button', { name: 'Move Photos later' })).toHaveAttribute('aria-disabled', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Move Photos earlier' }))
    expect(widgetsSource.writes.at(-1)).toEqual(['apps', 'app:photos', 'app:drive'])
    expect(await screen.findByText('Photos moved to position 2 of 3')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Move Photos earlier' })).toHaveFocus())
  })

  it('reorders by drag and drop', async () => {
    const { widgetsSource } = await edit(['drive', 'photos'])
    const group = (name: string) => screen.getByRole('group', { name })
    const dataTransfer = { setData: () => {}, effectAllowed: '' }
    fireEvent.dragStart(group('Photos'), { dataTransfer })
    fireEvent.dragOver(group('Your apps'), { dataTransfer })
    fireEvent.drop(group('Your apps'), { dataTransfer })
    await waitFor(() => expect(widgetsSource.writes.at(-1)).toEqual(['app:photos', 'apps', 'app:drive']))
  })

  // ⚠⚠ The second change lands before the store re-emitted the first (the provider's overlay).
  it('two quick moves keep both', async () => {
    const { widgetsSource } = await edit(['drive', 'photos'])
    widgetsSource.holdEmits()
    await userEvent.click(screen.getByRole('button', { name: 'Move Photos earlier' }))
    await userEvent.click(screen.getByRole('button', { name: 'Move Photos earlier' }))
    expect(widgetsSource.writes.at(-1)).toEqual(['app:photos', 'apps', 'app:drive'])
    act(() => widgetsSource.releaseEmits())
  })

  it('Add widget lists removed widgets and appends the one picked', async () => {
    const { widgetsSource } = await edit(['drive', 'photos'], ['app:drive', 'hidden:apps', 'hidden:app:photos'])
    expect(groups()).toEqual(['Drive'])
    await userEvent.click(screen.getByRole('button', { name: 'Add widget' }))
    const dialog = await screen.findByRole('dialog', { name: 'Add widget' })
    expect(within(dialog).getAllByRole('button', { name: /^Add / }).map(b => b.getAttribute('aria-label'))).toEqual([
      'Add Your apps',
      'Add Photos',
    ])
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add Photos' }))
    expect(widgetsSource.writes.at(-1)).toEqual(['app:drive', 'hidden:apps', 'app:photos'])
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  // D-W1, edited: installing still adds the new app's card at the end.
  it('an app installed from the Your apps dialog brings its card, after an edit too', async () => {
    const { appsSource } = await edit(['drive', 'photos'], ['app:photos', 'apps', 'app:drive'])
    await userEvent.click(screen.getByRole('button', { name: 'Your apps' }))
    const dialog = await screen.findByRole('dialog', { name: 'Your apps' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add Fit to your overview' }))
    expect(appsSource.writes.at(-1)).toEqual(['drive', 'photos', 'fit'])
    await userEvent.click(within(dialog).getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(groups()).toEqual(['Photos', 'Your apps', 'Drive', 'Fit']))
  })

  it('a failed write is reported and nothing moves', async () => {
    const { widgetsSource } = await edit(['drive', 'photos'])
    widgetsSource.failWrites(true)
    await userEvent.click(screen.getByRole('button', { name: 'Remove Photos' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't save that change")
    expect(groups()).toEqual(['Your apps', 'Drive', 'Photos'])
    expect(screen.queryByText(/removed\. Add it back/)).toBeNull()
  })

  it('a failed Undo keeps the toast so it can be retried', async () => {
    const { widgetsSource } = await edit(['drive', 'photos'])
    await userEvent.click(screen.getByRole('button', { name: 'Remove Photos' }))
    const toast = (await screen.findByText('Photos removed. Add it back any time.')).parentElement!
    widgetsSource.failWrites(true)
    await userEvent.click(within(toast).getByRole('button', { name: 'Undo' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't save that change")
    expect(within(toast).getByRole('button', { name: 'Undo' })).toBeInTheDocument()
  })

  // ⚠ An unreadable widget list draws the default and refuses edits — it is not an empty page.
  it('an unreadable widget list shows the default and offers no edits', async () => {
    renderAt('/overview/customize', fakeShownApps(['drive', 'photos']), fakeShownApps([], { readError: 'boom' }))
    await screen.findByRole('article', { name: 'Photos' })
    expect(screen.queryByRole('button', { name: /^Remove / })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Add widget' })).toBeNull()
    expect(screen.getByText(/can't be loaded right now/)).toBeInTheDocument()
  })

  it('Escape inside a dialog closes the dialog and stays in edit mode', async () => {
    await edit(['drive', 'photos'])
    await userEvent.click(screen.getByRole('button', { name: 'Add widget' }))
    await screen.findByRole('dialog', { name: 'Add widget' })
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByTestId('where')).toHaveTextContent('/overview/customize')
  })

  it('Escape leaves the mode', async () => {
    await edit(['drive', 'photos'])
    await userEvent.keyboard('{Escape}')
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/overview$/)
  })
})
