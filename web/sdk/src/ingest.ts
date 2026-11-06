// Ingest entry point: Control's generic background-ingest registry
// (`docs/proposals/drive-synced-folders-phase2.md` §8). The authenticated,
// in-realm management surface — the web counterpart of `:control-shared`'s
// `BackgroundIngestManager`. Backed by `IngestSources` on the Kotlin side
// (`:tenshu-sdk-js`).

import * as bundle from './_bundle'

export interface IngestSourceItem {
  readonly key: string
  readonly sourceId: string
  readonly appId: string
  readonly deviceId: string
  readonly displayName: string
  readonly sourceKind: string
  readonly enabled: boolean
  // An IngestSource status code: IDLE | RUNNING | UP_TO_DATE | PAUSED_POLICY | OUT_OF_SPACE | ERROR.
  readonly status: string
  readonly pendingCount: number
}

export interface IngestSourcesApi {
  list(): Promise<IngestSourceItem[]>
  // Flip a source's central kill switch; resolves true on success. Re-`list` after.
  setEnabled(sourceId: string, deviceId: string, enabled: boolean): Promise<boolean>
}

export const IngestSources: IngestSourcesApi = bundle.IngestSources.getInstance()
