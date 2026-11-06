// What kind of row a citation is, from its address's type id — native's `glyphFor` (ChatScreen.kt)
// and iOS `CitationsCell.symbol(for:)`, the same six outcomes.
//
// ⚠ Literals, as natively: presentation lives in each consumer's registry, never on the type
// (`core-realm-data.md`). A stale spelling costs a generic file glyph on an otherwise correct row,
// which is what the default is for — a newer runtime citing a source this build does not know.
export type HitKind = 'place' | 'moment' | 'workout' | 'device' | 'event' | 'file'

const KIND_BY_TYPE: Readonly<Record<string, HitKind>> = {
  'tenshu/place/place': 'place',
  'tenshu/timeline/moment': 'moment',
  'tenshu/health/workout': 'workout',
  'tenshu/home/device/homeDevice': 'device',
  'tenshu/calendar/eventOccurrence': 'event',
}

export function hitKind(type: string): HitKind {
  return KIND_BY_TYPE[type] ?? 'file'
}
