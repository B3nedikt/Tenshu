// The core runtime surfaces: the runtime itself (`TenshuSdk`), onboarding (`Bip39`, `Realm`,
// `Pairing`, `Recovery`, `Session`), devices, notifications, the legacy `Files` / `Folders`, and
// `NativeFailure` — the failure half every fallible surface carries.
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

interface NativeDevice {
  readonly deviceId: string
  readonly name: string
  readonly publicKey: Int8Array
  readonly isCurrent: boolean
  readonly isAuthorized: boolean
}

interface NativeNotification {
  readonly uuid: string
  readonly timestamp: number
}

interface NativeFile {
  readonly path: string
  readonly size: number
  readonly lastModified: number
  readonly createdAt: number
  // W11: an explicit flag. This used to be `files?: NativeFile[]`, "present iff
  // the underlying Composite was a Folder" — which was true only because the
  // schema walker emitted the FILES field for Folders and omitted it for Files.
  // The walker no longer builds these rows, so the discriminator says what it means.
  readonly isFolder: boolean
  // Child count for a folder, 0 for a file (the `Folder.FileCount` relation, not
  // the FILES field — reading the field ships every child entity just for `.size`).
  readonly itemCount: number
}

// `FileSystem.details(path)` shape — identical to NativeFile now that every row
// carries `createdAt`. Raw epoch-millis; `0` when not recorded. Formatting
// (human size / localised dates) is the web UI's job.
type NativeFileDetails = NativeFile

// `FileSystem.activity(path)` row — one modification, newest-first. `kind`
// is 'Created' | 'Modified' | 'Renamed' | 'Moved'; `detail` is the
// "old → new" / "to /newParent" string (null for Created/Modified);
// `deviceName` is the resolved authoring device (null when unknown locally).
interface NativeFileActivity {
  readonly kind: string
  readonly detail: string | null
  readonly timestampMillis: number
  readonly deviceId: string
  readonly deviceName: string | null
}

interface NativeFolder {
  readonly path: string
  readonly size: number
  readonly lastModified: number
  readonly files: ReadonlyArray<NativeFile>
}

type RuntimeLeaseState = 'idle' | 'acquiring' | 'held' | 'elsewhere' | 'unguarded' | 'displaced'

interface TenshuSdkInstance {
  readonly version: string
  // Reports current runtime state. `ready` flips to true once IDB
  // hydration has completed; use `awaitReady()` to block on it. Cheap
  // to call — no forced construction of the runtime chain.
  // See docs/web/architecture.md §12.12.
  runtimeStatus(): {
    readonly ready: boolean
    readonly nowMs: number
  }
  // Resolves once the runtime is ready (IDB opened + cache hydrated).
  // Apex calls this once before mounting; SDK ops also await internally
  // so it's defense-in-depth, not strictly required.
  awaitReady(): Promise<void>
  // One runtime per origin (docs/web/architecture.md "Cross-tab"). Resolves to this page's lease,
  // acquiring it if nothing has yet. 'elsewhere' ⇒ another tab runs Tenshu and every SDK call here
  // rejects with RUNTIME_ELSEWHERE (`./core`); 'displaced' ⇒ this tab handed it over.
  awaitRuntimeLease(): Promise<RuntimeLeaseState>
  // Every lease change (not acquiring anything). Returns the unsubscribe function.
  onRuntimeLeaseChange(callback: (state: RuntimeLeaseState) => void): () => void
  // "Use here": ask the holding tab to hand over (it flushes and closes its stores first) and
  // resolve once the lock is free. ⚠ The caller must then reload the page.
  takeOverRuntime(): Promise<void>
  // Override the sync-relay base URL before any sync-touching SDK call
  // (`Realm.create`, `Pairing.submit`, `Recovery.claim`, …). Default is
  // `https://relay.tenshu.app`. Apex dev typically points this at a
  // locally-running `:sync-relay-server` (`http://localhost:8080`).
  // Throws if called after the relay has already been constructed.
  configure(relayBaseUrl: string): void
  // Type ids of every data type that declares the `RealmData` marker trait
  // (docs/proposals/core-realm-data.md). The Control "Data" section walks
  // these generically — one card per id, no hard-coded list — and owns the
  // localised display name / icon / order per id (a presentation registry).
  // `File` ('tenshu/file') is the only v1 entry; `Folder` folds into it.
  realmDataTypeIds(): Promise<string[]>
}

interface Bip39DecodeResult {
  readonly kind: 'ok' | 'invalidLength' | 'unknownWord' | 'checksumMismatch'
  // Set when kind === 'ok'; null otherwise.
  readonly entropy: Int8Array | null
  // Set when kind === 'unknownWord'; null otherwise.
  readonly word: string | null
  // Set when kind === 'invalidLength'; 0 otherwise.
  readonly wordCount: number
}

interface Bip39Instance {
  // entropySize defaults to 32 (= 24-word mnemonic) per the threat
  // model. ByteArray on the Kotlin side surfaces as Int8Array on the
  // JS side.
  generateMnemonic(entropySize?: number): string
  encode(entropy: Int8Array): string
  // Parses a user-typed mnemonic back to entropy. The discriminated
  // result lets the recovery UI surface distinct messages per failure
  // mode without try/catch across the Kotlin/JS boundary.
  decode(mnemonic: string): Bip39DecodeResult
  // The BIP-39 English wordlist (2048 entries today). Treat as
  // read-only — driven by the Kotlin BIP39_ENGLISH_WORDLIST constant.
  readonly wordlist: ReadonlyArray<string>
}

interface DevicesInstance {
  demo(): NativeDevice
  // Subscribes to the realm's authorised-device list (filters out
  // revoked devices). First emission lands after the initial poll;
  // subsequent emissions only fire when the device-id set changes.
  observe(callback: (devices: NativeDevice[]) => void): () => void
  // Revokes a paired device by id. Returns the SDK's standard
  // `TResult<void>` shape; subsequent `observe` emissions drop the
  // revoked device.
  revoke(deviceId: string): Promise<
    | { readonly ok: true; readonly value: undefined }
    | {
        readonly ok: false
        readonly error:
          | { readonly kind: 'network' }
          | { readonly kind: 'other'; readonly message: string }
      }
  >
  // Revokes THIS device (web self-revoke / "sign out everywhere"). The
  // Kotlin side resolves the current device id internally and resolves
  // `ok` only once the `RevokeDevice` tombstone has been pushed to the
  // relay. Same `TResult<void>` shape as `revoke`.
  revokeSelf(): Promise<
    | { readonly ok: true; readonly value: undefined }
    | {
        readonly ok: false
        readonly error:
          | { readonly kind: 'network' }
          | { readonly kind: 'other'; readonly message: string }
      }
  >
}

interface NotificationsInstance {
  demo(): NativeNotification
  // Callback-based subscribe; returns an unsubscribe function. Each
  // emission delivers the full snapshot (not deltas), matching the
  // Tenshu Flow<TResult<TList<T>>> protocol after merging.
  observe(callback: (items: NativeNotification[]) => void): () => void
  // The newest [limit] (1..50) as display events, re-emitted on change — the web twin of the desktop
  // tray's `recentNotifications`. ⚠ `ok: false` on a failed read, never an empty list; `onError`
  // fires once if the stream ends for any reason but the returned unsubscribe.
  observeRecent(
    limit: number,
    callback: (r: { readonly ok: true; readonly events: NativeRecentEvent[] } | NativeFailure) => void,
    onError: (message: string) => void,
  ): () => void
}

// One display event. `kind`: 'file_added' | 'folder_added' | 'files_added' | 'file_deleted' |
// 'folder_deleted' | 'change_lost' | 'app_registered' | 'app_removed' — a newer build may add more.
// `name` is a path for file kinds, an app name for app kinds; `otherCount` > 0 only for 'files_added'.
interface NativeRecentEvent {
  readonly id: string
  readonly timestamp: number
  readonly kind: string
  readonly name: string
  readonly otherCount: number
}

interface FilesInstance {
  demo(): NativeFile
}

interface FoldersInstance {
  demo(): NativeFolder
}

// `Realm.create` (and its onboarding siblings `Pairing`, `Recovery`) take
// positional Kotlin arguments rather than the TS `{ args }` object the
// public `web/sdk/src/onboarding.ts` contract exposes — the TS adapter
// destructures the contract args before forwarding here. Returns the
// `TResult<void>` shape from `web/sdk/src/onboarding.ts` (`{ ok: true,
// value: undefined }` or `{ ok: false, error: string }`).
interface RealmInstance {
  create(
    mnemonic: string,
    deviceName: string,
  ): Promise<
    | { readonly ok: true; readonly value: undefined }
    | { readonly ok: false; readonly error: string }
  >
  // This realm's ownerId, or null before one exists. Every commerce endpoint is
  // keyed by it. Not a TResult — absence is an ordinary state, not a failure.
  ownerId(): Promise<string | null>
}

// `Pairing.submit` returns a `TResult<NativePairingSession,
// PairingSubmitError>` shaped exactly like the contract in
// `web/sdk/src/onboarding.ts`. The Kotlin side builds the
// `NativePairingSession` as a plain JS object whose `observe` and
// `cancel` are plain functions — `getInstance()` doesn't apply here.
interface NativePairingSession {
  observe(
    callback: (status: NativePairingStatus) => void,
  ): () => void
  cancel(): Promise<void>
}

type NativePairingStatus =
  | { readonly kind: 'awaiting'; readonly sas: string }
  | { readonly kind: 'paired' }
  | { readonly kind: 'denied' }
  | { readonly kind: 'expired' }
  | { readonly kind: 'error'; readonly message: string }

type NativePairingSubmitError =
  | { readonly kind: 'invalidUri' }
  | { readonly kind: 'network' }
  | { readonly kind: 'other'; readonly message: string }

interface NativeHostPairingSession {
  observe(callback: (status: NativeHostPairingStatus) => void): () => void
  approve(deviceId: string): Promise<
    | { readonly ok: true; readonly value: undefined }
    | {
        readonly ok: false
        readonly error:
          | { readonly kind: 'network' }
          | { readonly kind: 'other'; readonly message: string }
      }
  >
  cancel(): Promise<void>
}

type NativeHostPairingStatus =
  | { readonly kind: 'showing'; readonly pairingUri: string }
  | {
      readonly kind: 'request'
      readonly deviceId: string
      readonly deviceName: string
      readonly sas: string | null
    }
  | { readonly kind: 'paired' }
  | { readonly kind: 'cancelled' }
  | { readonly kind: 'error'; readonly message: string }

type NativePairingShowError =
  | { readonly kind: 'network' }
  | { readonly kind: 'other'; readonly message: string }

// `Pairing.offer` — the offer-flow (this browser shows the QR). Session carries the
// `tenshu://offer?…` URI + observe/cancel (sync-pairing-inverted-direction.md §5.1).
interface NativeOfferPairingSession {
  readonly pairingUri: string
  observe(callback: (status: NativeOfferPairingStatus) => void): () => void
  cancel(): Promise<void>
}

type NativeOfferPairingStatus =
  | { readonly kind: 'showing'; readonly pairingUri: string }
  | { readonly kind: 'paired'; readonly sas: string }
  | { readonly kind: 'denied' }
  | { readonly kind: 'expired' }
  | { readonly kind: 'error'; readonly message: string }

interface PairingInstance {
  submit(
    pairingUri: string,
    deviceName: string,
  ): Promise<
    | { readonly ok: true; readonly value: NativePairingSession }
    | { readonly ok: false; readonly error: NativePairingSubmitError }
  >
  show(): Promise<
    | { readonly ok: true; readonly value: NativeHostPairingSession }
    | { readonly ok: false; readonly error: NativePairingShowError }
  >
  offer(deviceName: string): Promise<
    | { readonly ok: true; readonly value: NativeOfferPairingSession }
    | { readonly ok: false; readonly error: NativePairingSubmitError }
  >
}

type NativeBip39DecodeError =
  | { readonly kind: 'invalidLength'; readonly wordCount: number }
  | { readonly kind: 'unknownWord'; readonly word: string }
  | { readonly kind: 'checksumMismatch' }

type NativeRecoveryError =
  | { readonly kind: 'invalidMnemonic'; readonly reason: NativeBip39DecodeError }
  | { readonly kind: 'noBackupOnRelay' }
  | { readonly kind: 'kdfParametersOutOfDate' }
  // This device already belongs to a DIFFERENT realm (T-RC-09) — refused before any
  // relay call. The user disconnects this browser first; retrying cannot help.
  | { readonly kind: 'deviceAlreadySetUp' }
  | { readonly kind: 'network' }
  | { readonly kind: 'other'; readonly message: string }

interface RecoveryInstance {
  claim(
    mnemonic: string,
    deviceName: string,
  ): Promise<
    | { readonly ok: true; readonly value: undefined }
    | { readonly ok: false; readonly error: NativeRecoveryError }
  >
  // §7.4 reveal — reconstructs the 24-word phrase from the in-memory entropy
  // (carried in the v2 wrap blob). Resolves `unavailable` on a v1 (pre-
  // entropy) blob, `joinerDevice` on a paired device, `locked` with no realm.
  revealPhrase(): Promise<
    | { readonly ok: true; readonly value: string }
    | {
        readonly ok: false
        readonly error:
          | { readonly kind: 'locked' }
          | { readonly kind: 'unavailable' }
          | { readonly kind: 'joinerDevice' }
          | { readonly kind: 'other'; readonly message: string }
      }
  >
}

type NativeSessionState =
  | { readonly kind: 'active'; readonly deviceName: string }
  | { readonly kind: 'locked'; readonly deviceName: string }
  | { readonly kind: 'firstTime' }

interface NativeSessionToken {
  readonly bytes: Int8Array
  readonly expiresAtMs: number
}

interface SessionInstance {
  detect(): Promise<NativeSessionState>
  persist(token: NativeSessionToken): Promise<void>
  // The Kotlin side accepts `null`/`undefined` here too — apex may pass
  // either when no options are needed.
  clear(options: { readonly purgeRealm?: boolean } | null): Promise<void>
  registerPasskey(registration: unknown): Promise<
    | { readonly ok: true; readonly value: void }
    | { readonly ok: false; readonly error: string }
  >
  unlock(assertion: unknown): Promise<
    | { readonly ok: true; readonly value: NativeSessionToken }
    | { readonly ok: false; readonly error: unknown }
  >
  unlockWithPhrase(mnemonic: string): Promise<
    | { readonly ok: true; readonly value: NativeSessionToken }
    | { readonly ok: false; readonly error: unknown }
  >
}

// ⚠⚠ The failure half every fallible SDK surface carries. Added because `T | null` could not say
// why: a failure and a legitimate absence arrived identically and callers assumed absence, which
// is the bug class behind W2a, W7's silent fallback and the content-stream refusal
// (`tech-debt.md`, the `T?` rule).
type NativeFailure = { readonly ok: false; readonly error: { readonly kind: string; readonly message?: string } }

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const TenshuSdk: { getInstance(): TenshuSdkInstance }
  export const Bip39: { getInstance(): Bip39Instance }
  export const Devices: { getInstance(): DevicesInstance }
  export const Notifications: { getInstance(): NotificationsInstance }
  export const Files: { getInstance(): FilesInstance }
  export const Folders: { getInstance(): FoldersInstance }
  export const Realm: { getInstance(): RealmInstance }
  export const Pairing: { getInstance(): PairingInstance }
  export const Recovery: { getInstance(): RecoveryInstance }
  export const Session: { getInstance(): SessionInstance }
}
