// Consumer-owned presentation for a realm-data type on the web Control surface.
//
// `RealmData` is a fieldless marker (docs/proposals/core-realm-data.md): the SDK exposes only
// *which* types are realm data (`TenshuSdk.realmDataTypeIds()`); how to label / icon / order
// them is localisable, consumer-specific presentation and lives here — the web apex's own
// registry, independent of the native control-shared one. English literals today; this is the
// single seam to localise via react-i18next per ADR 0016 when web i18n lands.

export interface RealmDataPresentation {
  readonly pluralDisplayName: string
  // A short glyph used as the card's leading mark (the apex renders simple unicode marks
  // rather than pulling an icon library into the control plane — see FilesPage).
  readonly glyph: string
  readonly sortOrder: number
  // URL segment under /control/data/<segment> the card links to.
  readonly routeSegment: string
}

const KNOWN: Readonly<Record<string, RealmDataPresentation>> = {
  'tenshu/file': {
    pluralDisplayName: 'Files',
    glyph: '▢',
    sortOrder: 100,
    routeSegment: 'files',
  },
}

// Presentation for a type id. Unknown types — future first-party or third-party data types
// not yet given a bespoke entry — still render a card (generic-walker invariant, control-ia
// §5): a derived label, a generic glyph, last-place ordering, and a route segment derived
// from the id's last path segment.
export function realmDataPresentation(typeId: string): RealmDataPresentation {
  const known = KNOWN[typeId]
  if (known) return known
  const tail = typeId.split('/').pop() ?? typeId
  return {
    pluralDisplayName: tail.charAt(0).toUpperCase() + tail.slice(1),
    glyph: '◆',
    sortOrder: Number.MAX_SAFE_INTEGER,
    routeSegment: tail,
  }
}
