// The apex half of an app's URL (web-apps-layout.md §3.4, L2): the frame opens where the apex URL
// points, the app's reports move the apex URL, and moves the app did not report are sent to it.
import { act, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { HostedAppEmbed } from './HostedAppEmbed'
import { NAV_MESSAGE, READY_MESSAGE, ROUTE_MESSAGE, appPathFor, readNavMessage } from './appNav'
import { findHostedApp } from './registry'

const drive = findHostedApp('drive')!
const DRIVE = drive.origin()

// Shows the apex URL, and moves it the way the launcher or Back would (not through the app).
function Probe() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <output data-testid="url">{location.pathname + location.hash}</output>
      <button type="button" onClick={() => navigate('/drive/settings')}>
        elsewhere
      </button>
    </>
  )
}

function SwitchTo({ to }: { to: string }) {
  const navigate = useNavigate()
  return (
    <button type="button" onClick={() => navigate(to)}>
      switch
    </button>
  )
}

function renderDrive(at: string) {
  render(
    <MemoryRouter initialEntries={[at]}>
      <Probe />
      <Routes>
        <Route path={'/' + drive.routePath} element={<HostedAppEmbed app={drive} />} />
      </Routes>
    </MemoryRouter>,
  )
  const frame = screen.getByTitle('Drive') as HTMLIFrameElement
  return { frame, url: () => screen.getByTestId('url').textContent }
}

function report(frame: HTMLIFrameElement, data: unknown, origin = DRIVE) {
  act(() => {
    window.dispatchEvent(new MessageEvent('message', { data, origin, source: frame.contentWindow }))
  })
}

describe('HostedAppEmbed — URL sync', () => {
  it('opens the frame at the section and fragment the apex URL names', () => {
    const { frame } = renderDrive('/drive/files#/Documents')
    expect(frame).toHaveAttribute('src', `${DRIVE}/files#/Documents`)
  })

  it('opens at the app root for a path that is not a section', () => {
    const { frame } = renderDrive('/drive/Taxes%202026')
    expect(frame).toHaveAttribute('src', DRIVE)
  })

  it('moves the apex URL when the app reports a move', () => {
    const { frame, url } = renderDrive('/drive')
    report(frame, { type: NAV_MESSAGE, path: '/files', hash: '#/Documents', push: true })
    expect(url()).toBe('/drive/files#/Documents')
    report(frame, { type: NAV_MESSAGE, path: '/', hash: '', push: true })
    expect(url()).toBe('/drive')
  })

  it('does not echo a reported move back to the app', () => {
    const { frame } = renderDrive('/drive')
    const post = vi.spyOn(frame.contentWindow!, 'postMessage')
    report(frame, { type: NAV_MESSAGE, path: '/files', hash: '', push: true })
    expect(post).not.toHaveBeenCalled()
  })

  it('sends the app a move it did not report (back, forward, the launcher)', () => {
    const { frame } = renderDrive('/drive/files')
    const post = vi.spyOn(frame.contentWindow!, 'postMessage')
    act(() => screen.getByRole('button', { name: 'elsewhere' }).click())
    expect(post).toHaveBeenCalledWith({ type: ROUTE_MESSAGE, path: '/settings', hash: '' }, DRIVE)
  })

  it('ignores reports from another origin or window', () => {
    const { frame, url } = renderDrive('/drive')
    report(frame, { type: NAV_MESSAGE, path: '/files', hash: '', push: true }, 'https://evil.example')
    act(() => {
      window.dispatchEvent(
        new MessageEvent('message', { data: { type: NAV_MESSAGE, path: '/files', hash: '', push: true }, origin: DRIVE, source: window }),
      )
    })
    expect(url()).toBe('/drive')
  })

  it('re-sends its place when the app says it is listening (an earlier send was lost)', () => {
    const { frame } = renderDrive('/drive/files#/A')
    const post = vi.spyOn(frame.contentWindow!, 'postMessage')
    act(() => screen.getByRole('button', { name: 'elsewhere' }).click())
    post.mockClear() // the app was still booting: that send is gone
    report(frame, { type: READY_MESSAGE })
    expect(post).toHaveBeenCalledWith({ type: ROUTE_MESSAGE, path: '/settings', hash: '' }, DRIVE)
  })

  it('a switch to another app gets a fresh frame at that app, carrying nothing of the old one', () => {
    const photos = findHostedApp('photos')!
    render(
      <MemoryRouter initialEntries={['/drive/files#/Taxes']}>
        <Probe />
        <Routes>
          <Route path={'/' + drive.routePath} element={<HostedAppEmbed app={drive} />} />
          <Route path={'/' + photos.routePath} element={<HostedAppEmbed app={photos} />} />
        </Routes>
        <SwitchTo to="/photos" />
      </MemoryRouter>,
    )
    expect(screen.getByTitle('Drive')).toHaveAttribute('src', `${DRIVE}/files#/Taxes`)
    act(() => screen.getByRole('button', { name: 'switch' }).click())
    expect(screen.getByTitle('Photos')).toHaveAttribute('src', photos.origin())
  })

  // ⚠⚠ The privacy guard (§3.4): a reload sends the apex PATH to the apex host, which logs it.
  it('never lets a folder or file name into the apex path, however the app reports it', () => {
    const { frame, url } = renderDrive('/drive/files')
    for (const path of ['/files/Taxes 2026', '/Taxes', '/files/Documents/passport.pdf', '//evil.example', '/files/']) {
      report(frame, { type: NAV_MESSAGE, path, hash: '', push: true })
      expect(url()).toBe('/drive/files')
    }
    // The fragment is where a folder goes — and it never reaches the server.
    report(frame, { type: NAV_MESSAGE, path: '/files', hash: '#/Taxes%202026/Receipts', push: false })
    expect(url()).toBe('/drive/files#/Taxes%202026/Receipts')
    expect(url()!.split('#')[0]).not.toMatch(/Taxes|Receipts/)
  })
})

describe('appNav', () => {
  it('maps an apex pathname to an in-app section path', () => {
    expect(appPathFor(drive, '/drive')).toBe('/')
    expect(appPathFor(drive, '/drive/files')).toBe('/files')
    expect(appPathFor(drive, '/drive/files/')).toBe('/files')
    expect(appPathFor(drive, '/drive/files/a/b')).toBe('/')
  })

  it('rejects malformed reports', () => {
    expect(readNavMessage(drive, null)).toBeNull()
    expect(readNavMessage(drive, { type: 'other', path: '/files', hash: '' })).toBeNull()
    expect(readNavMessage(drive, { type: NAV_MESSAGE, path: '/nope', hash: '' })).toBeNull()
    // A fragment we will not carry costs only the fragment: the section move and its push stay.
    expect(readNavMessage(drive, { type: NAV_MESSAGE, path: '/files', hash: '#' + 'x'.repeat(9000), push: true })).toEqual({
      path: '/files',
      hash: '',
      push: true,
    })
    expect(readNavMessage(drive, { type: NAV_MESSAGE, path: '/files', hash: '' })).toEqual({ path: '/files', hash: '', push: false })
  })
})

describe('registry sections', () => {
  it('every hosted app lists its home section and only path-safe section ids', async () => {
    const { HOSTED_APPS } = await import('./registry')
    for (const app of HOSTED_APPS) {
      expect(app.sections, app.id).toContain('')
      for (const section of app.sections) expect(section, app.id).toMatch(/^[a-z]*$/)
    }
  })
})
