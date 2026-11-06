// What a write reports back to the dialog that ran it: `null` on success, else what to show.
import type { Failure } from './errors'

export type Outcome = { failure: Failure } | { error: unknown } | null

export async function attempt(run: () => Promise<{ ok: true } | { ok: false; error: Failure }>): Promise<Outcome> {
  try {
    const result = await run()
    return result.ok ? null : { failure: result.error }
  } catch (error) {
    return { error }
  }
}
