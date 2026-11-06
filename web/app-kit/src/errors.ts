// Typed errors → copy. ⚠⚠ Render by `kind` (a catalogue key, `error_…`), never by `message` (a
// diagnostic) and never as an empty list: a failed READ is a failure on the web, because there is
// no reachability banner to explain a blank screen (web-app-suite.md §4.1).

/** `kind` when the app has copy for it, else the fallback — never the raw, unknown key. */
export function knownErrorKind(
  kind: string,
  known: readonly string[],
  fallback = 'error_generic',
): string {
  return known.includes(kind) ? kind : fallback
}

// A rejected bridge call (no answer, the gateway refusing this origin, a thrown handler) is not a
// typed failure, so it gets its own sentence rather than pretending to be one.
export type BridgeErrorKind = 'bridge_not_permitted' | 'bridge_timeout' | 'bridge'

export function bridgeErrorKind(error: unknown): BridgeErrorKind {
  const message = error instanceof Error ? error.message : String(error)
  // The apex gateway's F5 refusal: this origin's allowlist lacks the method.
  if (message.startsWith('not permitted')) return 'bridge_not_permitted'
  if (message.includes('timed out')) return 'bridge_timeout'
  return 'bridge'
}
