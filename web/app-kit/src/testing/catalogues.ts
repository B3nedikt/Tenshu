// i18n key parity: en / de / fr must carry the same keys, or a missing one renders English (or the
// raw key) silently.

/** Every leaf key of a catalogue, dot-joined (`errors.error_generic`). */
export function catalogueKeys(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) return [prefix]
  return Object.entries(value).flatMap(([k, v]) => catalogueKeys(v, prefix ? `${prefix}.${k}` : k))
}
