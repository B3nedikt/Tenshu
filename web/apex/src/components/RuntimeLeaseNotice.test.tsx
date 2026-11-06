import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import '../i18n'

// The real core module loads the Kotlin bundle; the notice only needs the predicate.
vi.mock('@tenshu/sdk/core', () => ({
  TenshuSdk: {},
  isRuntimeElsewhere: (s: string | null | undefined) => s === 'elsewhere' || s === 'displaced',
}))

const { RuntimeLeaseNotice } = await import('./RuntimeLeaseNotice')

function fakeSdk() {
  let push: ((state: string) => void) | null = null
  return {
    sdk: {
      onRuntimeLeaseChange: (cb: (state: never) => void) => {
        push = cb as (state: string) => void
        return () => undefined
      },
      takeOverRuntime: vi.fn(async () => undefined),
    },
    push: (state: string) => act(() => push?.(state)),
  }
}

const reload = vi.fn()
beforeEach(() => {
  reload.mockReset()
  vi.stubGlobal('location', { ...window.location, reload })
})
afterEach(() => vi.unstubAllGlobals())

describe('RuntimeLeaseNotice', () => {
  it('stays silent while this tab holds the runtime', () => {
    const { sdk, push } = fakeSdk()
    render(<RuntimeLeaseNotice sdk={sdk} />)
    push('held')
    expect(screen.queryByText('Tenshu is open in another tab')).toBeNull()
  })

  it('offers "Use here" when another tab holds it, and reloads after the hand-over', async () => {
    const { sdk, push } = fakeSdk()
    render(<RuntimeLeaseNotice sdk={sdk} />)
    push('elsewhere')

    await userEvent.click(await screen.findByRole('button', { name: 'Use here' }))

    expect(sdk.takeOverRuntime).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(reload).toHaveBeenCalled())
  })

  it('appears in the tab that was displaced', async () => {
    const { sdk, push } = fakeSdk()
    render(<RuntimeLeaseNotice sdk={sdk} />)
    push('held')
    push('displaced')
    expect(await screen.findByText('Tenshu is open in another tab')).toBeTruthy()
  })
})
