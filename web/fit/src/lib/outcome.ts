// What a write reports back to the dialog or card that ran it — a plain function, kept out of
// `components/ui.tsx` so that file exports components only (fast refresh).
import type { Failure } from './errors'

// `null` on success, else what to show.
export type Outcome = { failure: Failure } | { error: unknown } | null

export async function attempt<T extends { readonly ok: true } | { readonly ok: false; readonly error: Failure }>(
  run: () => Promise<T>,
  onOk?: (result: Extract<T, { readonly ok: true }>) => void,
): Promise<Outcome> {
  try {
    const result = await run()
    if (!result.ok) return { failure: (result as { error: Failure }).error }
    onOk?.(result as Extract<T, { readonly ok: true }>)
    return null
  } catch (error) {
    return { error }
  }
}
