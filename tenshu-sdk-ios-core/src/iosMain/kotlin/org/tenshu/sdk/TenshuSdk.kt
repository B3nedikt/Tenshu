package org.tenshu.sdk

/**
 * Entry point for the Tenshu iOS SDK, in `:tenshu-sdk-ios-core` and so in every app's `TenshuSdk`
 * framework (`docs/proposals/ios-per-app-frameworks.md`).
 *
 * Phase 1 of `docs/proposals/ios-proxy-architecture.md` is build + CI only: this
 * object existed purely so the old umbrella module `:tenshu-sdk-ios` (removed in
 * per-app frameworks P5) had a Kotlin source to compile and link a framework
 * binary from (an export-only module links as `NO-SOURCE` and produces no artifact). The client-facing API surface is the re-exported
 * `:core-client` + service `-client`/`-common` modules; the EPC client entry
 * points (pairing, channel) land in Phase 2.
 */
public object TenshuSdk {
    /** Marker the iOS host can read to confirm the framework linked. */
    public const val VERSION: String = "0.1.0-phase1"
}
