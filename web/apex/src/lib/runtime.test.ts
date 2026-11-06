import { describe, expect, it, vi } from 'vitest'

const configure = vi.fn()
vi.mock('@tenshu/sdk/core', () => ({ TenshuSdk: { configure: (url: string) => configure(url) } }))

const { ensureRuntime } = await import('./runtime')
const { RELAY_URL } = await import('./relayUrl')

describe('ensureRuntime', () => {
  it('configures the relay once, on the first call and not at import', () => {
    expect(configure).not.toHaveBeenCalled()
    ensureRuntime()
    ensureRuntime()
    expect(configure).toHaveBeenCalledTimes(1)
    expect(configure).toHaveBeenCalledWith(RELAY_URL)
  })
})
