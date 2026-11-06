# photos-ios

The SwiftUI shell for Tenshu **Photos** on iOS (`docs/proposals/photos-ios.md`).

Native SwiftUI over the shared, Compose-free `:photos-shared-logic` view models, reached through the
`PhotosSurface` seam on Photos' own `TenshuSdk` framework, `:tenshu-sdk-ios-photos`
(`ios-per-app-frameworks.md` P4; `ios-proxy-architecture.md` §9.3 —
*share view models + repos, native UI*). The twin of `calendar-ios` / `contacts-ios`.

## Route 1

Photos is **born on route 1**: it runs the production proxy graph in its own process over the
shared App-Group replica Control writes (`PhotosLocalRuntime`). Every service it reads is already
hosted by that graph, so the runtime is thin.

Without provisioning it shows the shared **wall** (`ConnectToControlView`, ADR 0022), not the
banner — a photo library has nothing legible without data (PIO-D4).

⚠ **Photos does not back anything up.** Drive runs the PhotoKit drain on iOS, as on Android
(PIO-D5); Photos shows what is in the realm.

## The rule this app is built by

**Swift decides nothing** (PIO-D2). Nothing iOS compiles in the cloud sessions that write most of
this app, so every judgement — which lines are headers, which thumbnail rung a tile asks for, how a
date reads, whether an empty feed is *empty* or *unreachable* — lives in tested `commonMain`
(`PhotosFeedBinding`, `FeedLayout`, `PhotoDates`, `tilePreviewRequestPx`, `heroPreviewRequestPx`).
When a screen here needs a new decision, move it down first.

## What is here

**PIO-1**

- `PhotosIosApp.swift` — `@main`, the route-1 gate, the wall, the foreground rehydrate.
- `LibraryScreen.swift` — the feed as a plain Day-density grid over `PhotosFeedBinding.layout`,
  and its four states (loading / empty / unreachable / failed).
- `PhotoTileView.swift` — one tile; ImageIO downsampled decode + a per-path cache (PIO-D8).
- `PhotoViewerScreen.swift` — a read-only pager over the feed's loaded photographs; per-page hero
  bytes that sharpen as better rungs arrive.
- `PhotosTheme.swift` — the hand-rolled empty state (`ContentUnavailableView` is iOS 17+).

## Build

```sh
./gradlew generatePhotosIosProject          # or: cd photos-ios && xcodegen generate
scripts/ios-smoke.sh --build-only photos     # compile (simulator SDK, no signing)
scripts/ios-smoke.sh --build photos          # build + install + launch on TENSHU_IOS_DEVICE
```

⚠ Run `scripts/ios-smoke.sh --build-only` for **every** app after a change to `:tenshu-sdk-ios-core`
or the base export list: every app's framework exports it, and an export can rename a type in the
same framework (parity doc D12; `scripts/check-objc-clashes.py`).
