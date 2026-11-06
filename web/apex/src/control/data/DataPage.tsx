// Control → Data landing (control-ia.md §5 / docs/proposals/core-realm-data.md Phase 4).
//
// A GENERIC walker: one card per realm-data type, driven entirely by
// `TenshuSdk.realmDataTypeIds()` with no hard-coded type list. A future data type appears
// here automatically once it declares the `RealmData` marker — the only per-consumer concern
// is presentation (label / glyph / order), owned by `realmDataPresentation`. Below the type
// inventory sit the two cross-cutting Data subsections from the IA: Devices (§5.2) and
// Shares (§5.3). Type cards are a meta-view, not a browser; the File card links to its
// inventory meta-view, which routes into Drive for actual browsing.
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { TenshuSdk } from '@tenshu/sdk/core'
import { realmDataPresentation } from './realmDataPresentation'

export function DataPage() {
  const [typeIds, setTypeIds] = useState<readonly string[] | null>(null)

  useEffect(() => {
    let active = true
    TenshuSdk.realmDataTypeIds()
      .then(ids => {
        if (active) setTypeIds(ids)
      })
      .catch(() => {
        if (active) setTypeIds([])
      })
    return () => {
      active = false
    }
  }, [])

  const types = (typeIds ?? [])
    .map(typeId => ({ typeId, ...realmDataPresentation(typeId) }))
    .sort((a, b) => a.sortOrder - b.sortOrder)

  return (
    <div>
      <h1 className="text-2xl font-medium">Data</h1>
      <p className="mt-1 text-sm text-text-2">
        The data in your realm. Apps are temporary lenses; your data persists across them.
      </p>

      <ul className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {types.map(type => (
          <li key={type.typeId}>
            <Link
              to={`/control/data/${type.routeSegment}`}
              className="flex items-center gap-3 rounded-md border border-line p-4 hover:border-line"
            >
              <span aria-hidden className="text-xl text-text-3">
                {type.glyph}
              </span>
              <span className="font-medium">{type.pluralDisplayName}</span>
            </Link>
          </li>
        ))}
      </ul>

      <h2 className="mt-8 text-sm font-medium uppercase tracking-wide text-text-2">More</h2>
      <ul className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <li>
          <Link
            to="/control/data/devices"
            className="flex items-center gap-3 rounded-md border border-line p-4 hover:border-line"
          >
            <span aria-hidden className="text-xl text-text-3">
              ▥
            </span>
            <span className="font-medium">Devices</span>
          </Link>
        </li>
        <li>
          <Link
            to="/control/data/shares"
            className="flex items-center gap-3 rounded-md border border-line p-4 hover:border-line"
          >
            <span aria-hidden className="text-xl text-text-3">
              ↗
            </span>
            <span className="font-medium">Shares</span>
          </Link>
        </li>
        <li>
          <Link
            to="/control/data/background-ingest"
            className="flex items-center gap-3 rounded-md border border-line p-4 hover:border-line"
          >
            <span aria-hidden className="text-xl text-text-3">
              ☁
            </span>
            <span className="font-medium">Background ingest</span>
          </Link>
        </li>
      </ul>
    </div>
  )
}
