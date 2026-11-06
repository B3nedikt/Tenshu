# control-ios

The Tenshu **Control** iOS app shell — Control-on-iOS **C2b**
([`docs/proposals/ios-control-handover.md`](../docs/proposals/ios-control-handover.md)).

Control-on-iOS is a full, key-holding realm device (unlike Drive, the keyless EPC client). This
slice stands up the **shell over fakes**: the main sections (Home / Data / Apps / Settings) bound to
the real `:control-shared-logic` view models through the `ControlSurface` umbrella seam, with the
`Mock*` repositories standing in for a proxy — exactly as `drive-ios` first stood up over
`FakeFilesRepository`. Genesis onboarding (create realm → mint key via the C1 keystore → capture
recovery phrase) is **C2c**; the provisioning bridge (C3's `epc-session` presence token, retired 2026-11-05 —
route 1 publishes the shared-Keychain key material instead) is **C3**; the real on-device data plane is **C4**.

The Xcode project + `Info.plist` are generated from `project.yml` and gitignored (build from source,
like the Kotlin framework + libsodium).

## Build (simulator)

```bash
# One-time: generate the Xcode project
cd control-ios && xcodegen generate && cd ..

# Build the app. Its pre-build step links one slice of Control's own Kotlin framework,
# `:tenshu-sdk-ios-control` (`scripts/ios-build-sdk.sh control`), so nothing is assembled first
# (docs/proposals/ios-per-app-frameworks.md P2).
xcodebuild -project control-ios/ControlIos.xcodeproj -scheme ControlIos \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath control-ios/build build
```

## Run (simulator)

```bash
xcrun simctl boot 'iPhone 16' 2>/dev/null || true
xcrun simctl install booted control-ios/build/Build/Products/Debug-iphonesimulator/ControlIos.app
xcrun simctl launch booted app.tenshu.control.ios.smoke
```
