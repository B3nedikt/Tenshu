import { afterEach, describe, expect, it, vi } from 'vitest'

const call = vi.fn(async (..._args: unknown[]) => 'answer' as unknown)
const unsubscribe = vi.fn()
const subscribe = vi.fn((..._args: unknown[]) => unsubscribe)
const mountApexProxy = vi.fn(async (_options: { apexOrigin: string }) => ({ call, subscribe, dispose: vi.fn() }))
vi.mock('@tenshu/sdk/bridge', () => ({
  mountApexProxy: (options: { apexOrigin: string }) => mountApexProxy(options),
}))

const { createApexBridge, configuredApexOrigin } = await import('./bridge')

const Methods = { Ping: 'bridge.ping', List: 'thing.list', Observe: 'thing.observe' } as const

afterEach(() => {
  call.mockClear()
  subscribe.mockClear()
  unsubscribe.mockClear()
  mountApexProxy.mockClear()
})

describe('createApexBridge', () => {
  it('dials the apex dev server unless VITE_APEX_ORIGIN says otherwise', () => {
    expect(configuredApexOrigin()).toBe('http://localhost:5173')
    expect(createApexBridge(Methods).apexOrigin).toBe('http://localhost:5173')
    expect(createApexBridge(Methods, { apexOrigin: 'https://tenshu.app' }).apexOrigin).toBe('https://tenshu.app')
  })

  it('mounts once however many calls it carries — two mounts would be two hidden iframes', async () => {
    const bridge = createApexBridge(Methods, { apexOrigin: 'https://tenshu.app' })
    await Promise.all([bridge.call(Methods.List), bridge.call(Methods.Ping), bridge.client()])
    expect(mountApexProxy).toHaveBeenCalledTimes(1)
    expect(mountApexProxy).toHaveBeenCalledWith({ apexOrigin: 'https://tenshu.app' })
  })

  it('forwards the method and params, and omits params a call does not take', async () => {
    const bridge = createApexBridge(Methods)
    expect(await bridge.call(Methods.List, { path: '/' })).toBe('answer')
    expect(call).toHaveBeenLastCalledWith('thing.list', { path: '/' })
    await bridge.call(Methods.Ping)
    expect(call.mock.lastCall).toEqual(['bridge.ping'])
  })

  it('subscribes once mounted and tears down through the returned function', async () => {
    const bridge = createApexBridge(Methods)
    const onValue = vi.fn()
    const onError = vi.fn()
    const stop = bridge.subscribe(Methods.Observe, { id: 1 }, onValue, onError)
    await vi.waitFor(() => expect(subscribe).toHaveBeenCalledWith('thing.observe', { id: 1 }, onValue, onError))
    stop()
    expect(unsubscribe).toHaveBeenCalledTimes(1)
  })

  it('a subscription cancelled before the bridge mounted never starts', async () => {
    const bridge = createApexBridge(Methods)
    bridge.subscribe(Methods.Observe, undefined, vi.fn())()
    await bridge.client()
    await Promise.resolve()
    expect(subscribe).not.toHaveBeenCalled()
  })

  it('reports a bridge that never mounted to the subscriber as a terminal error', async () => {
    mountApexProxy.mockRejectedValueOnce(new Error('no apex'))
    const bridge = createApexBridge(Methods)
    const onError = vi.fn()
    bridge.subscribe(Methods.Observe, undefined, vi.fn(), onError)
    await vi.waitFor(() => expect(onError).toHaveBeenCalledWith(new Error('no apex')))
  })
})
