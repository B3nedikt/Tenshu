// Param readers shared by the gateway modules. Each handler gets the structured-cloned `params` an
// app sent and must validate it before it reaches the runtime: a missing required value throws, so
// the gateway rejects loudly instead of calling an op with a default it never asked for.

export function asRecord(params: unknown): Record<string, unknown> {
  return typeof params === 'object' && params !== null
    ? (params as Record<string, unknown>)
    : {}
}

// Contacts address-book validators. An id is required and non-empty: a blank one would resolve
// to "no such contact" (or, for a merge, hit the unlinked-candidate marker) instead of surfacing
// the malformed call. A name or text may be '' — that is a value, not an omission.
export function readId(params: unknown, key: string, op: string): string {
  const v = asRecord(params)[key]
  if (typeof v !== 'string' || v.length === 0) throw new Error(`${op} requires { ${key} }`)
  return v
}

export function readString(params: unknown, key: string, op: string): string {
  const v = asRecord(params)[key]
  if (typeof v !== 'string') throw new Error(`${op} requires { ${key}: string }`)
  return v
}
