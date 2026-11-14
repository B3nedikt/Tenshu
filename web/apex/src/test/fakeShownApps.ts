// An in-memory `ShownAppsSource` for tests. It behaves like the real store where that matters:
// it emits the stored list on subscribe; a successful write RESOLVES FIRST and the re-emit arrives
// later (the real one comes back through the realm relation's flow) — ⚠ emitting inside `write`
// hid a lost-update race in the dialog; and a failing write leaves the list unchanged.
import type { ShownAppsSource } from '../overview/shownAppsContext'

export interface FakeShownApps extends ShownAppsSource {
  /** Every list written, in order. */
  readonly writes: ReadonlyArray<readonly string[]>
  /** Make the next writes fail (true) or succeed (false). */
  failWrites(fail: boolean): void
  /**
   * Hold re-emits after writes until [releaseEmits] — the window between a write resolving and the
   * store re-emitting, held open so a test can act inside it (a timer alone closes too soon).
   */
  holdEmits(): void
  releaseEmits(): void
}

export function fakeShownApps(
  initial: readonly string[] = [],
  options: { readonly readError?: string } = {},
): FakeShownApps {
  let stored = [...initial]
  let failing = false
  let holding = false
  const held: Array<() => void> = []
  const writes: string[][] = []
  const listeners = new Set<(list: readonly string[]) => void>()
  return {
    writes,
    failWrites(fail) {
      failing = fail
    },
    holdEmits() {
      holding = true
    },
    releaseEmits() {
      holding = false
      held.splice(0).forEach(emit => emit())
    },
    observe(onList, onError) {
      if (options.readError !== undefined) {
        onError(options.readError)
        return () => {}
      }
      listeners.add(onList)
      queueMicrotask(() => onList(stored))
      return () => {
        listeners.delete(onList)
      }
    },
    async write(appIds) {
      if (failing) return { ok: false, message: 'write failed (fake)' }
      stored = [...appIds]
      writes.push(stored)
      const emitted = stored
      const emit = () => listeners.forEach(l => l(emitted))
      if (holding) held.push(emit)
      else setTimeout(emit, 0)
      return { ok: true }
    },
  }
}
