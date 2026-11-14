import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NAV_MESSAGE, READY_MESSAGE, ROUTE_MESSAGE, hashOf, parseRoute, useAppRoute } from './route'

const APEX = 'https://apex.example'
const SECTIONS = ['home', 'files', 'settings'] as const
type Section = (typeof SECTIONS)[number]

// A framed app: `window.parent` is a different window whose postMessage we can watch.
const parent = { postMessage: vi.fn() }

beforeEach(() => {
  window.history.replaceState(null, '', '/')
  parent.postMessage.mockReset()
  vi.spyOn(window, 'parent', 'get').mockReturnValue(parent as unknown as Window)
})
afterEach(() => vi.restoreAllMocks())

const hook = () => renderHook(() => useAppRoute<Section>(SECTIONS, 'home', { apexOrigin: APEX }))

function fromApex(data: unknown, origin = APEX, source: unknown = parent) {
  act(() => {
    window.dispatchEvent(new MessageEvent('message', { data, origin, source: source as Window }))
  })
}

describe('parseRoute', () => {
  it('reads a section from the first path segment and decodes the fragment', () => {
    expect(parseRoute('/files', '#/My%20Docs/Taxes', SECTIONS, 'home')).toEqual({
      section: 'files',
      fragment: '/My Docs/Taxes',
    })
  })
  it('lands an unknown or empty section on home', () => {
    expect(parseRoute('/', '', SECTIONS, 'home').section).toBe('home')
    expect(parseRoute('/nope', '', SECTIONS, 'home').section).toBe('home')
  })
  it('keeps a malformed escape as raw text instead of throwing', () => {
    expect(parseRoute('/files', '#%E0%A4%A', SECTIONS, 'home').fragment).toBe('%E0%A4%A')
  })
})

describe('useAppRoute', () => {
  it('starts from the frame URL', () => {
    window.history.replaceState(null, '', '/files#/Documents')
    const { result } = hook()
    expect(result.current.section).toBe('files')
    expect(result.current.fragment).toBe('/Documents')
  })

  it('navigate replaces its own entry and asks the apex to push', () => {
    const { result } = hook()
    const length = window.history.length
    act(() => result.current.navigate('files', '/My Docs'))
    expect(window.location.pathname).toBe('/files')
    expect(window.location.hash).toBe('#/My%20Docs')
    // ⚠ Never a push inside the frame: the apex owns the tab's history.
    expect(window.history.length).toBe(length)
    expect(parent.postMessage).toHaveBeenCalledWith(
      { type: NAV_MESSAGE, path: '/files', hash: '#/My%20Docs', push: true },
      APEX,
    )
  })

  it('the home section is the bare path', () => {
    const { result } = hook()
    act(() => result.current.navigate('files'))
    act(() => result.current.navigate('home'))
    expect(window.location.pathname).toBe('/')
    expect(parent.postMessage).toHaveBeenLastCalledWith({ type: NAV_MESSAGE, path: '/', hash: '', push: true }, APEX)
  })

  it('setFragment keeps the section and asks the apex to replace', () => {
    const { result } = hook()
    act(() => result.current.navigate('files'))
    act(() => result.current.setFragment('/Taxes'))
    expect(result.current.section).toBe('files')
    expect(parent.postMessage).toHaveBeenLastCalledWith(
      { type: NAV_MESSAGE, path: '/files', hash: '#/Taxes', push: false },
      APEX,
    )
  })

  it('follows a route message from the apex without echoing it back', () => {
    const { result } = hook()
    parent.postMessage.mockClear() // the mount's ready message
    fromApex({ type: ROUTE_MESSAGE, path: '/settings', hash: '' })
    expect(result.current.section).toBe('settings')
    expect(window.location.pathname).toBe('/settings')
    expect(parent.postMessage).not.toHaveBeenCalled()
  })

  it('ignores route messages from any other origin or window', () => {
    const { result } = hook()
    fromApex({ type: ROUTE_MESSAGE, path: '/settings', hash: '' }, 'https://evil.example')
    fromApex({ type: ROUTE_MESSAGE, path: '/settings', hash: '' }, APEX, window)
    fromApex({ type: 'other', path: '/settings', hash: '' })
    expect(result.current.section).toBe('home')
  })

  it('tells the apex once it is listening, so a route sent while booting is re-sent', () => {
    hook()
    expect(parent.postMessage).toHaveBeenCalledWith({ type: READY_MESSAGE }, APEX)
  })

  it('a fragment that cannot be encoded is dropped, not thrown out of a click', () => {
    expect(hashOf('/bad\uD800name')).toBe('')
    const { result } = hook()
    expect(() => act(() => result.current.navigate('files', '/bad\uD800name'))).not.toThrow()
    expect(window.location.pathname).toBe('/files')
  })

  it('open pushes a detail in the current section; back steps history when it pushed', () => {
    const historyBack = vi.spyOn(window.history, 'back').mockImplementation(() => {})
    const { result } = hook()
    act(() => result.current.navigate('files'))
    act(() => result.current.open('w=1'))
    expect(parent.postMessage).toHaveBeenLastCalledWith({ type: NAV_MESSAGE, path: '/files', hash: '#w=1', push: true }, APEX)
    act(() => result.current.back())
    expect(historyBack).toHaveBeenCalledTimes(1)
  })

  it('back from a detail it did not push (reload, deep link) replaces instead of leaving the app', () => {
    const historyBack = vi.spyOn(window.history, 'back').mockImplementation(() => {})
    window.history.replaceState(null, '', '/files#w=1')
    const { result } = hook()
    act(() => result.current.back())
    expect(historyBack).not.toHaveBeenCalled()
    expect(result.current.fragment).toBe('')
    expect(parent.postMessage).toHaveBeenLastCalledWith({ type: NAV_MESSAGE, path: '/files', hash: '', push: false }, APEX)
  })

  it('an apex move (browser Back) uses up one of its pushes', () => {
    const historyBack = vi.spyOn(window.history, 'back').mockImplementation(() => {})
    const { result } = hook()
    act(() => result.current.open('w=1'))
    fromApex({ type: ROUTE_MESSAGE, path: '/', hash: '' }) // browser Back closed it
    act(() => result.current.back())
    expect(historyBack).not.toHaveBeenCalled()
  })

  it('standalone (not framed) it posts nothing', () => {
    vi.spyOn(window, 'parent', 'get').mockReturnValue(window)
    const post = vi.spyOn(window, 'postMessage')
    const { result } = hook()
    act(() => result.current.navigate('files'))
    expect(window.location.pathname).toBe('/files')
    expect(post).not.toHaveBeenCalled()
  })
})
