// Customize mode over a fake store (docs/proposals/web-apex-home.md §3.5.1, H3).
import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { OverviewPage } from './OverviewPage'
import { ShownAppsProvider } from './ShownAppsProvider'
import { fakeShownApps, type FakeShownApps } from '../test/fakeShownApps'

vi.mock('../control/recoveryStatus', () => ({
  getRecoveryStatus: () => Promise.resolve({ kind: 'verified', verifiedAtMs: 0 }),
}))

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderAt(path: string, source: FakeShownApps) {
  return render(
    <ShownAppsProvider source={source}>
      <MemoryRouter initialEntries={[path]}>
        <Where />
        <Routes>
          <Route path="/overview" element={<OverviewPage />} />
          <Route path="/overview/customize" element={<OverviewPage customizing />} />
        </Routes>
      </MemoryRouter>
    </ShownAppsProvider>,
  )
}

async function dock() {
  return screen.findByRole('region', { name: 'Your apps' })
}

describe('outside Customize mode', () => {
  // ⚠ The everyday page must not be rearrangeable by a stray drag or click.
  it('tiles are plain links: nothing draggable, no hide or move controls', async () => {
    renderAt('/overview', fakeShownApps(['drive', 'photos']))
    const region = await dock()
    await within(region).findByRole('link', { name: 'Photos' })
    expect(region.querySelector('[draggable="true"]')).toBeNull()
    expect(screen.queryByRole('button', { name: /^Hide / })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Move / })).toBeNull()
    expect(screen.getByRole('link', { name: 'Customize' })).toHaveAttribute('href', '/overview/customize')
  })
})

describe('Customize mode', () => {
  async function customize(stored: string[]) {
    const source = fakeShownApps(stored)
    renderAt('/overview/customize', source)
    await screen.findByRole('button', { name: /^Move Drive later/ })
    return source
  }

  it('tiles are not links, and Drive cannot be hidden', async () => {
    await customize(['drive', 'photos'])
    const region = await dock()
    expect(within(region).queryByRole('link', { name: 'Photos' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Hide Drive' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Hide Photos' })).toBeInTheDocument()
  })

  it('hiding writes at once and Undo puts the app back where it was', async () => {
    const source = await customize(['drive', 'photos', 'fit'])
    await userEvent.click(screen.getByRole('button', { name: 'Hide Photos' }))
    expect(source.writes.at(-1)).toEqual(['drive', 'fit'])
    // Focus moves to the neighbour instead of falling to <body>.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Move Fit earlier' })).toHaveFocus())
    const toast = await screen.findByText('Photos hidden. Its data stays in your realm.')
    await userEvent.click(within(toast.parentElement!).getByRole('button', { name: 'Undo' }))
    expect(source.writes.at(-1)).toEqual(['drive', 'photos', 'fit'])
  })

  it('moves by button, announces the new position, and disables the ends', async () => {
    const source = await customize(['drive', 'photos', 'fit'])
    expect(screen.getByRole('button', { name: 'Move Drive earlier' })).toHaveAttribute('aria-disabled', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Move Drive earlier' }))
    expect(source.writes).toEqual([])
    expect(screen.getByRole('button', { name: 'Move Fit later' })).toHaveAttribute('aria-disabled', 'true')
    await userEvent.click(screen.getByRole('button', { name: 'Move Fit earlier' }))
    expect(source.writes.at(-1)).toEqual(['drive', 'fit', 'photos'])
    expect(await screen.findByText('Fit moved to position 2 of 3')).toBeInTheDocument()
    // Keyboard users keep their place: focus stays on the button they pressed.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Move Fit earlier' })).toHaveFocus())
  })

  it('reorders by drag and drop', async () => {
    const source = await customize(['drive', 'photos', 'fit'])
    const tile = (name: string) => screen.getByRole('button', { name: `Hide ${name}` }).closest('li')!
    const drive = screen.getByRole('button', { name: 'Move Drive later' }).closest('li')!
    const dataTransfer = { setData: () => {}, effectAllowed: '' }
    fireEvent.dragStart(tile('Fit'), { dataTransfer })
    fireEvent.dragOver(drive, { dataTransfer })
    fireEvent.drop(drive, { dataTransfer })
    await waitFor(() => expect(source.writes.at(-1)).toEqual(['fit', 'drive', 'photos']))
  })

  // ⚠⚠ The second change lands before the store re-emitted the first; built from the stale list it
  // would undo the first (the shared `useShownAppsEditor` guard).
  it('two quick moves keep both', async () => {
    const source = await customize(['drive', 'photos', 'fit', 'maps'])
    source.holdEmits()
    await userEvent.click(screen.getByRole('button', { name: 'Move Maps earlier' }))
    await userEvent.click(screen.getByRole('button', { name: 'Move Maps earlier' }))
    expect(source.writes.at(-1)).toEqual(['drive', 'maps', 'photos', 'fit'])
    act(() => source.releaseEmits())
  })

  // ⚠⚠ One overlay for every editor: a change made in the dialog opened from Customize, before the
  // store re-emitted the hide, must not bring the hidden app back (nor the reverse).
  it('a hide in Customize survives an add in the dialog opened from it', async () => {
    const source = await customize(['drive', 'photos', 'fit'])
    source.holdEmits()
    await userEvent.click(screen.getByRole('button', { name: 'Hide Photos' }))
    await userEvent.click(screen.getByRole('button', { name: 'Add app' }))
    const dialog = await screen.findByRole('dialog', { name: 'Your apps' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add Maps to your overview' }))
    expect(source.writes.at(-1)).toEqual(['drive', 'fit', 'maps'])
    act(() => source.releaseEmits())
  })

  it('a failed Undo keeps the toast so it can be retried', async () => {
    const source = await customize(['drive', 'photos'])
    await userEvent.click(screen.getByRole('button', { name: 'Hide Photos' }))
    const toast = (await screen.findByText('Photos hidden. Its data stays in your realm.')).parentElement!
    source.failWrites(true)
    await userEvent.click(within(toast).getByRole('button', { name: 'Undo' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't save that change")
    expect(within(toast).getByRole('button', { name: 'Undo' })).toBeInTheDocument()
  })

  it('Escape inside the dialog closes the dialog and stays in Customize', async () => {
    await customize(['drive', 'photos'])
    await userEvent.click(screen.getByRole('button', { name: 'Add app' }))
    await screen.findByRole('dialog', { name: 'Your apps' })
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Your apps' })).toBeNull())
    expect(screen.getByTestId('where')).toHaveTextContent('/overview/customize')
  })

  it('a failed write is reported and nothing moves', async () => {
    const source = await customize(['drive', 'photos'])
    source.failWrites(true)
    await userEvent.click(screen.getByRole('button', { name: 'Hide Photos' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't save that change")
    expect(screen.getByRole('button', { name: 'Hide Photos' })).toBeInTheDocument()
    expect(screen.queryByText(/hidden\. Its data stays/)).toBeNull()
  })

  it('Escape leaves the mode', async () => {
    await customize(['drive', 'photos'])
    await userEvent.keyboard('{Escape}')
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/overview$/)
  })
})
