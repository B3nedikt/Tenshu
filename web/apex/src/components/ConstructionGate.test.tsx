// Coverage for the pre-launch construction gate. Exercises:
//   - passthrough when no code is configured (gate disabled)
//   - passthrough when already unlocked (localStorage)
//   - the curtain hides children until the right code is entered
//   - a wrong code marks the inputs invalid and keeps children hidden
//   - the right code reveals children and persists the unlock
import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter } from 'react-router'
import { ConstructionGate, RoutedConstructionGate } from './ConstructionGate'
import { isAlwaysPublicPath } from '../lib/alwaysPublicPaths'

const SECRET = 'the-real-site'
const CODE = '424242'

// jsdom in this vitest setup exposes a non-functional `localStorage` (a plain {} without
// getItem/setItem — see publisherClient.ts's feature-detect). Install a real in-memory Storage
// so we exercise the component's actual persistence path rather than its no-storage fallback.
function installMemoryLocalStorage() {
  const map = new Map<string, string>()
  const storage: Storage = {
    get length() {
      return map.size
    },
    clear: () => map.clear(),
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => void map.set(k, String(v)),
    removeItem: (k) => void map.delete(k),
    key: (i) => Array.from(map.keys())[i] ?? null,
  }
  Object.defineProperty(window, 'localStorage', { value: storage, configurable: true })
}

function renderGate(code?: string) {
  return render(
    <ConstructionGate code={code}>
      <div>{SECRET}</div>
    </ConstructionGate>,
  )
}

describe('ConstructionGate', () => {
  beforeEach(() => {
    installMemoryLocalStorage()
  })

  it('renders children directly when no code is configured', () => {
    renderGate(undefined)
    expect(screen.getByText(SECRET)).toBeInTheDocument()
    expect(screen.queryByText(/finishing touches/i)).not.toBeInTheDocument()
  })

  it('renders children directly when an empty/whitespace code is configured', () => {
    renderGate('   ')
    expect(screen.getByText(SECRET)).toBeInTheDocument()
  })

  it('renders children directly when already unlocked', () => {
    window.localStorage.setItem('tenshu.preview.unlocked', '1')
    renderGate(CODE)
    expect(screen.getByText(SECRET)).toBeInTheDocument()
    expect(screen.queryByText(/finishing touches/i)).not.toBeInTheDocument()
  })

  it('shows the curtain and hides children until the code is entered', () => {
    renderGate(CODE)
    expect(screen.getByText(/finishing touches/i)).toBeInTheDocument()
    expect(screen.queryByText(SECRET)).not.toBeInTheDocument()
  })

  it('marks inputs invalid and keeps children hidden on a wrong code', async () => {
    const user = userEvent.setup()
    renderGate(CODE)

    await user.click(screen.getByLabelText('Digit 1'))
    await user.paste('000000')

    await waitFor(() => {
      expect(screen.getByLabelText('Digit 1')).toHaveAttribute('aria-invalid', 'true')
    })
    expect(screen.queryByText(SECRET)).not.toBeInTheDocument()
    expect(window.localStorage.getItem('tenshu.preview.unlocked')).toBeNull()
  })

  it('reveals children and persists the unlock on the right code', async () => {
    const user = userEvent.setup()
    renderGate(CODE)

    await user.click(screen.getByLabelText('Digit 1'))
    await user.paste(CODE)

    expect(await screen.findByText(SECRET)).toBeInTheDocument()
    expect(window.localStorage.getItem('tenshu.preview.unlocked')).toBe('1')
  })
})

// ⚠⚠ Production builds set the curtain code, and Google Play's reviewers must reach the
// account-deletion page without one (lib/alwaysPublicPaths.ts).
describe('RoutedConstructionGate', () => {
  beforeEach(() => {
    installMemoryLocalStorage()
  })

  function renderAt(path: string) {
    return render(
      <MemoryRouter initialEntries={[path]}>
        <RoutedConstructionGate code={CODE}>
          <div>{SECRET}</div>
          <Link to="/">home</Link>
        </RoutedConstructionGate>
      </MemoryRouter>,
    )
  }

  it('never covers the account-deletion page', () => {
    renderAt('/account/delete')
    expect(screen.getByText(SECRET)).toBeInTheDocument()
    expect(screen.queryByText(/finishing touches/i)).not.toBeInTheDocument()
  })

  it('still covers every other path', () => {
    renderAt('/vision')
    expect(screen.getByText(/finishing touches/i)).toBeInTheDocument()
    expect(screen.queryByText(SECRET)).not.toBeInTheDocument()
  })

  it('comes back when the visitor navigates from the public page into the site', async () => {
    const user = userEvent.setup()
    renderAt('/account/delete')
    await user.click(screen.getByRole('link', { name: 'home' }))
    expect(screen.getByText(/finishing touches/i)).toBeInTheDocument()
  })

  it('matches only the listed paths', () => {
    expect(isAlwaysPublicPath('/account/delete')).toBe(true)
    expect(isAlwaysPublicPath('/account/delete/')).toBe(true)
    expect(isAlwaysPublicPath('/Account/Delete')).toBe(true)
    expect(isAlwaysPublicPath('/account')).toBe(false)
    expect(isAlwaysPublicPath('/account/delete/x')).toBe(false)
    expect(isAlwaysPublicPath('/')).toBe(false)
  })
})
