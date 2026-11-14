// Shared Kotlin/JS bundle import. Every per-service entry point
// (`./core`, `./identity`, `./notifications`, `./files`) imports the
// `@JsExport`-ed objects from here so the path + reference directive
// live in one place.
//
// The bundle is built via `:tenshu-sdk-js:jsBrowserDistribution` (which
// runs `:jsBrowserProductionWebpack` and copies into `build/dist/`). The
// webpack output is **ESM** (see `tenshu-sdk-js/webpack.config.d/esm.js`)
// so Vite — which loads this file via a relative `.js` import inside a
// `"type": "module"` package — gets proper named exports. Each named
// export from the Kotlin side is an `@JsExport`-ed `object`, exposed as
// a `{ getInstance(): T }` accessor; the per-service files below call
// `.getInstance()` to surface the actual singletons.
//
// All entry points share a single Kotlin bundle today — the per-service
// `exports` map is API ergonomics only, not real bundle splitting. See
// docs/web/architecture.md §12.11.

/// <reference path="./native.d.ts" />

export {
  Apps,
  Bip39,
  Calendar,
  Contacts,
  ContentStream,
  Devices,
  FileSystem,
  Files,
  Fit,
  Folders,
  Home,
  IngestSources,
  MapRegion,
  Maps,
  Membership,
  Mia,
  Notifications,
  Overview,
  Pairing,
  Photos,
  Realm,
  Recovery,
  Session,
  ShareViewer,
  SharedFiles,
  Shares,
  Storage,
  SyncHealth,
  SyncStatus,
  TenshuSdk,
  Timeline,
} from '../../../tenshu-sdk-js/build/dist/js/productionExecutable/tenshu-sdk-js.js'
