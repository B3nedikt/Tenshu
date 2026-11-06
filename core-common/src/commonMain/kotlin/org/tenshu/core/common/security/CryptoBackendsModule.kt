package org.tenshu.core.common.security

import org.koin.core.module.Module

// Platform-specific Koin bindings for crypto backends (Ed25519, X25519,
// AEAD, KDF). One `expect fun` seam for the whole crypto track, with
// each platform supplying the right impls in its `actual`. Loaded by
// `coreCommonModule()` via `includes(cryptoBackendsModule())`, so every
// caller of `coreCommonModule()` automatically pulls in the right backend
// for its target — no wiring required at the app or test boundary.
//
// **Why a function, not a `val`.** Top-level `val` Koin modules share
// `single<>` instances across separate `koinApplication { }` instances
// in Koin 4.2 (verified empirically). Anything that needs to spin up
// multiple isolated Koin scopes — most notably `:system-test-utils`
// per-device `TestClient`s — needs a fresh module per call. Production
// (single global Koin) is unaffected; the call-site cost is two `()`.
//
// Bindings provided per platform after slices A + B + C:
//   - JVM (desktop + Android): `BouncyCastleEd25519Backend`,
//     `BouncyCastleX25519Backend`, `BouncyCastleAeadBackend`,
//     `BouncyCastleKdfBackend`.
//   - JS: `NobleEd25519Backend`, `NobleX25519Backend`,
//     `NobleAeadBackend`, `NobleKdfBackend`.
//   - iOS: stub backends that throw on use (Phase 4 iOS work parked
//     until the iOS app starts using the SDK; the throw is the clear
//     error point if someone wires it up before then).
expect fun cryptoBackendsModule(): Module
