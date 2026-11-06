import { render, screen } from '@testing-library/react'
import { Dialog } from '@tenshu/web-tokens/ui'
import { afterAll, expect, it } from 'vitest'

// Guards the wait in `setup.ts`'s afterEach. Unmounting a Radix dialog leaves a FocusScope
// `setTimeout(0)` behind, and if that timer outlives the file, it fires after Vitest has torn jsdom
// down and throws an unhandled "parameter 1 is not of type 'Event'". That error is rare (Drive's
// ScanInviteFlow, 2026-10-28) because Vitest only reports it inside a window of a few microseconds.
// This test checks for the precondition instead, which is deterministic: by the time `afterAll` runs,
// the timer must already have fired.
//
// What the timer does is dispatch FocusScope's unmount event on the dialog element. ⚠ That event
// name is Radix-internal: if a Radix upgrade renames it, this goes red although the timer still
// fires. Read the new name out of `@radix-ui/react-focus-scope` rather than deleting the guard.
const UNMOUNT_EVENT = 'focusScope.autoFocusOnUnmount'
let unmountTimerFired = false

it('opens a dialog and leaves it for cleanup() to unmount', () => {
  render(
    <Dialog title="Probe" onClose={() => {}}>
      <button type="button">inside</button>
    </Dialog>,
  )
  // The listener stays attached after `cleanup()` detaches the element, which is where the timer
  // dispatches its event.
  screen.getByRole('dialog').addEventListener(UNMOUNT_EVENT, () => {
    unmountTimerFired = true
  })
  expect(unmountTimerFired).toBe(false)
})

afterAll(() => {
  // Runs after the shared afterEach. Without its wait, the timer is still pending here.
  expect(unmountTimerFired).toBe(true)
})
