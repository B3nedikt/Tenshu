// `markRuntimeRequested` is called by `RequiresRuntime` DURING render. It used to flip the store and
// notify on the spot, a render-phase update to the root's notice slot: React warned "Cannot update a
// component while rendering a different component" and threw the in-progress render away, so every
// render-time read under the gate ran twice — the second time after the invite route had already
// scrubbed its `#v1.…`, which is why every invite link read as "incomplete".
import { useState } from 'react'
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

// Module state (the flag) must start clean in every case.
async function freshModule() {
  vi.resetModules()
  return import('./runtimeRequested')
}

describe('markRuntimeRequested', () => {
  let errors: ReturnType<typeof vi.spyOn>
  beforeEach(() => {
    errors = vi.spyOn(console, 'error').mockImplementation(() => {})
  })
  afterEach(() => {
    errors.mockRestore()
  })

  it('called during render, updates no other component mid-render and still lands', async () => {
    const { markRuntimeRequested, useRuntimeRequested } = await freshModule()
    let gateRenders = 0

    function Slot() {
      return <p>{useRuntimeRequested() ? 'requested' : 'idle'}</p>
    }
    // RequiresRuntime's shape: the request is made from a state initializer, i.e. in render.
    function Gate() {
      gateRenders++
      useState(() => {
        markRuntimeRequested()
        return true
      })
      return null
    }

    // The slot is already mounted (it lives in the root) when a route's gate first renders.
    const { rerender } = render(<Slot />)
    rerender(
      <>
        <Slot />
        <Gate />
      </>,
    )

    expect(await screen.findByText('requested')).toBeInTheDocument()
    const crossUpdates = errors.mock.calls.filter((c: unknown[]) =>
      String(c[0]).includes('Cannot update a component'),
    )
    expect(crossUpdates).toEqual([])
    expect(gateRenders).toBe(1)
  })

  it('notifies once, however often it is called', async () => {
    const { markRuntimeRequested, useRuntimeRequested } = await freshModule()
    let slotRenders = 0
    function Slot() {
      slotRenders++
      return <p>{useRuntimeRequested() ? 'requested' : 'idle'}</p>
    }
    render(<Slot />)
    markRuntimeRequested()
    markRuntimeRequested()
    await waitFor(() => expect(screen.getByText('requested')).toBeInTheDocument())
    expect(slotRenders).toBe(2)
  })
})
