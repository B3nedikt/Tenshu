# contacts-ios

The Tenshu **Contacts** iOS app — native SwiftUI over the shared `:contacts-shared-logic` view
models, resolved through the `ContactsSurface` seam on Contacts' own `TenshuSdk` framework,
`:tenshu-sdk-ios-contacts` (`docs/proposals/contacts-app.md` §9.9, Ph10;
`docs/proposals/ios-per-app-frameworks.md` P4).

Contacts is **born on route 1**: it never had an EPC tier, so there is nothing to migrate off and
no live-Control channel. When Control has provisioned the route-1 key material,
`ContactsLocalRuntime` runs the production proxy graph *in this process* over the shared
App-Group replica; absent that, the app shows the honest not-connected surface and says why.

## Build

```bash
./gradlew generateContactsIosProject        # or: cd contacts-ios && xcodegen generate
xcodebuild -project ContactsIos.xcodeproj -scheme ContactsIos \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build
```

Contacts' Kotlin framework is linked by a pre-build script (`scripts/ios-build-sdk.sh contacts`),
so the first build is slow.

## Render probes (DEV_HARNESS scheme only)

`simctl` has no tap verb, so each screen can be mounted head-less over the *shipping* views:

```
-contactsScreen list
-contactsScreen detail [personId]     # defaults to a MIXED keyring — the Q2b state
-contactsScreen profile
-contactsScreen duplicates
```

⚠ Probes run over `ContactsSurface.installMock()`, so they prove the **view**, not the data path:
the shipping default has no contacts at all.

## What is not here

- **No map, so no `MapBearingApp` template** — that include would pull MapLibre and ~40 MB of tile
  archive into an app with nothing to plot.
- **No app icon yet** (the icon track has not reached Contacts — `:contacts-desktop`'s posture).
- **The write surfaces the Compose app has and this shell does not**: manual add, import, the
  invitation sheet and the completion prompt. See §9.9 for why those are a follow-up rather than an
  omission — each needs a native equivalent of a Compose sheet, and the read surfaces are what
  prove the runtime.
