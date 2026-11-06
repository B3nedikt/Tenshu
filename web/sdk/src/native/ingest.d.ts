// `IngestSources` — the background-ingest registry.
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// IngestSources: the background-ingest registry (Synced Folders Phase 2).
// `pendingCount` is a JS number; `status` is an IngestSource status code.
interface IngestSourceItem {
  readonly key: string
  readonly sourceId: string
  readonly appId: string
  readonly deviceId: string
  readonly displayName: string
  readonly sourceKind: string
  readonly enabled: boolean
  readonly status: string
  readonly pendingCount: number
}

interface IngestSourcesInstance {
  list(): Promise<IngestSourceItem[]>
  setEnabled(sourceId: string, deviceId: string, enabled: boolean): Promise<boolean>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const IngestSources: { getInstance(): IngestSourcesInstance }
}
