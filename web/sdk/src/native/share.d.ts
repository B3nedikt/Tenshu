// Mode-A link sharing: the anonymous viewer and the owner's share management.
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// Mode-A recipient surface (drive-file-sharing.md §3.4 / §6.5). `bytes` is the
// decrypted file content on success; on failure `errorKind` discriminates a
// revoked/expired/absent share (`gone`), a transport failure (`network`), a
// decryption/AAD failure incl. a wrong key (`decrypt`), or a descriptor without
// readable sealed metadata (`meta`). Name/MIME come from the sealed metadata.
// Anonymous — no session required.
interface ShareFetchResult {
  readonly ok: boolean
  readonly errorKind: 'gone' | 'network' | 'decrypt' | 'meta' | null
  readonly bytes: Int8Array | null
  readonly fileName: string | null
  readonly mimeType: string | null
}

interface ShareViewerInstance {
  fetch(
    shareId: string,
    kShareBase64Url: string,
    relayBaseUrl: string,
  ): Promise<ShareFetchResult>
}

// Authenticated Mode-A management surface (control-ia.md §5.3) — the realm
// owner's view over their own outbound link shares. Distinct from the anonymous
// ShareViewer: runs in-realm over the authenticated client. `expiresAtMillis` /
// `maxOpens` are null for no-expiry / no-cap; no `opensSoFar` (relay-only in v1).
interface ShareListItem {
  readonly shareId: string
  readonly fileName: string
  readonly filePath: string
  readonly createdAtMillis: number
  readonly expiresAtMillis: number | null
  readonly maxOpens: number | null
  readonly revoked: boolean
}

// `Shares.create(...)` result — the relay-assigned id + the per-share key
// fragment the caller turns into the copy-able URL. `null` on any error.
interface NativeShareLink {
  readonly shareId: string
  readonly urlFragment: string
}

interface SharesInstance {
  create(
    path: string,
    expiresAtMillis: number,
    maxOpens: number,
  ): Promise<NativeShareLink | null>
  list(): Promise<ShareListItem[]>
  revoke(shareId: string): Promise<boolean>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const ShareViewer: { getInstance(): ShareViewerInstance }
  export const Shares: { getInstance(): SharesInstance }
}
