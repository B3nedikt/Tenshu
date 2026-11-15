// `DriveSettings` / `PhotosSettings` — the web apps' remembered view settings (web-apps-layout.md L6).
// A fragment of `../native.d.ts`. ⚠ DEVICE scope: this browser's own rows (Drive's sort and view are
// native Drive's rows for this device). `{ ok: false, error.kind: 'no_device' }` until the browser
// has a sync device id; `bad_value` for a value outside the vocabulary (nothing is stored).

type NativeDriveSortKey = 'name' | 'modified' | 'created' | 'size'

interface DriveSettingsInstance {
  get(): Promise<
    | {
        readonly ok: true
        readonly key: NativeDriveSortKey
        readonly descending: boolean
        readonly layout: 'list' | 'grid'
        readonly detailsPane: boolean
        // Whether each row exists on this device (a missing row reads as the default above).
        readonly sortStored: boolean
        readonly layoutStored: boolean
        readonly paneStored: boolean
      }
    | NativeFailure
  >
  setListing(key: NativeDriveSortKey, descending: boolean, layout: 'list' | 'grid'): Promise<{ readonly ok: true } | NativeFailure>
  setDetailsPane(open: boolean): Promise<{ readonly ok: true } | NativeFailure>
}

interface PhotosSettingsInstance {
  get(): Promise<
    | {
        readonly ok: true
        readonly density: 'days' | 'months' | 'years'
        readonly mosaic: boolean
        readonly fit: boolean
        // The size slider's photos per row, 3…8 (web-apps-layout.md §4.10).
        readonly columns: number
      }
    | NativeFailure
  >
  setDensity(density: 'days' | 'months' | 'years'): Promise<{ readonly ok: true } | NativeFailure>
  // The mosaic and whole-photo switches (web-apps-layout.md §4.9).
  setLayout(mosaic: boolean, fit: boolean): Promise<{ readonly ok: true } | NativeFailure>
  setColumns(columns: number): Promise<{ readonly ok: true } | NativeFailure>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const DriveSettings: { getInstance(): DriveSettingsInstance }
  export const PhotosSettings: { getInstance(): PhotosSettingsInstance }
}
