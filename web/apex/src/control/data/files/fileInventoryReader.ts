// The Data → Files read, reduced to the four states the page renders. Kept apart from the page so
// the state machine is testable without React, and so "what counts as an error" is decided once.
//
// ⚠⚠ Two failure shapes, both errors, neither ever zeros: the SDK answering `ok: false` (the tree
// read broke — kind `read`, or a kind added later) and the promise REJECTING (the runtime itself
// threw, e.g. it is not up in this tab). An empty drive is the only route to the empty state.
import type { FileInventory, FileInventoryResult } from '@tenshu/sdk/files'

export type InventoryErrorKind = 'read' | 'runtime' | 'other'

export type InventoryState =
  | { readonly status: 'loading' }
  | { readonly status: 'empty' }
  | { readonly status: 'ready'; readonly inventory: FileInventory }
  | { readonly status: 'error'; readonly kind: InventoryErrorKind; readonly detail?: string }

export async function readInventoryState(
  read: () => Promise<FileInventoryResult>,
): Promise<InventoryState> {
  let result: FileInventoryResult
  try {
    result = await read()
  } catch (e) {
    return { status: 'error', kind: 'runtime', detail: e instanceof Error ? e.message : String(e) }
  }
  if (!result.ok) {
    const kind: InventoryErrorKind = result.error.kind === 'read' ? 'read' : 'other'
    return { status: 'error', kind, detail: result.error.message ?? result.error.kind }
  }
  const { inventory } = result
  if (inventory.fileCount === 0 && inventory.folderCount === 0) return { status: 'empty' }
  return { status: 'ready', inventory }
}

// ⚠⚠ One read at a time, per tab. `FileSystem.inventory()` is a full-tree `traverse()` on the tab's
// single JS thread (shared with the hosted apps), and it is a `GlobalScope.promise` — unmounting
// cannot cancel it. So a second open while the first read is still running (navigate away and
// back, StrictMode's double effect) joins that read instead of starting another full walk. The
// slot clears when the read settles, so the NEXT open — or "Try again" after an error — reads
// fresh: this dedupes concurrent reads, it never serves a stale answer.
let inFlight: Promise<InventoryState> | null = null

export function readInventoryOnce(read: () => Promise<FileInventoryResult>): Promise<InventoryState> {
  if (inFlight) return inFlight
  const pending = readInventoryState(read).finally(() => {
    if (inFlight === pending) inFlight = null
  })
  inFlight = pending
  return pending
}

/** Tests only: forget a read a test left pending, so it cannot leak into the next test. */
export function resetInventoryReadForTests(): void {
  inFlight = null
}
