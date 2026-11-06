// Ambient declaration for the Kotlin/JS webpack bundle.
//
// :tenshu-sdk-js:jsBrowserDistribution emits an **ES-module** bundle
// (see `tenshu-sdk-js/webpack.config.d/esm.js`) whose named exports are
// `@JsExport`-ed Kotlin `object`s wrapped as `{ getInstance(): T }`
// accessors — that's the convention Kotlin/JS uses under
// `useEsModules()`. The per-service files in `web/sdk/src/` call
// `.getInstance()` to surface the actual singletons; the shape of those
// singletons is what's declared below.
//
// Composite types come through as plain objects whose keys are the last
// path segment of each schema field id (e.g. `tenshu/identity/device/deviceId`
// → `deviceId`) and whose values are JS-native primitives. Mapping:
//   TString     → string
//   TBoolean    → boolean
//   TByteArray  → Int8Array
//   TInteger    → number  (lossy beyond 2^53)
//   UnixInstant → number  (epoch ms; wrap in `new Date(...)` if needed)
//   TList<T>    → Array<unwrap(T)>
//   Composite   → recursive flattened object
// The flattening lives in the Kotlin schema walker — see
// `tenshu-sdk-js/src/jsMain/.../internal/CompositeSnapshot.kt`.
//
// ⚠ Split per service (web-app-suite.md F4): each `native/<svc>.d.ts` holds that service's
// instance + row types AND its own `declare module` block naming its exports — TypeScript merges
// the repeated ambient-module declarations. A new service adds a fragment and one reference line
// below; `_bundle.ts` adds its export name. Shared shapes (`NativeFailure`, `NativeFile`) live in
// `native/core.d.ts`; the fragments are global script files, so any one may use them.

/// <reference path="./native/core.d.ts" />
/// <reference path="./native/files.d.ts" />
/// <reference path="./native/share.d.ts" />
/// <reference path="./native/ingest.d.ts" />
/// <reference path="./native/storage.d.ts" />
/// <reference path="./native/sync.d.ts" />
/// <reference path="./native/contact.d.ts" />
/// <reference path="./native/membership.d.ts" />
/// <reference path="./native/sharedFiles.d.ts" />
/// <reference path="./native/content.d.ts" />
/// <reference path="./native/timeline.d.ts" />
/// <reference path="./native/calendar.d.ts" />
/// <reference path="./native/photos.d.ts" />
/// <reference path="./native/mapRegion.d.ts" />
/// <reference path="./native/maps.d.ts" />
/// <reference path="./native/fit.d.ts" />
/// <reference path="./native/home.d.ts" />
/// <reference path="./native/mia.d.ts" />
