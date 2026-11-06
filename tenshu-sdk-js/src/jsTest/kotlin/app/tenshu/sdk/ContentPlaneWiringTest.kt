@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import org.tenshu.sync.proxy.RestContentBlobStore
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// W1 (`docs/proposals/drive-web-parity.md`). The web runtime carried every part of the
// two-plane file-content path except the personal `ContentBlobStore`, and that one null
// severed personal file bytes in BOTH directions while reading as an empty result rather
// than an error:
//
//  - inbound  — `initSyncService` builds a `FileContentManifestApplier` only when a content
//               store AND a file database are both present, so an arriving
//               `FileContentManifestPayload` was a clean no-op and `File.Data` resolved
//               `null` forever on a device that had not uploaded the file itself.
//  - outbound — `SyncObserver.emitFileContent` opens `contentBlobStore ?: return false`, so
//               a browser upload emitted metadata and never offered its bytes.
//
// ⚠⚠ **What actually guards W1 is the type, not this test.** `RuntimeContext.contentBlobStore`
// is declared non-nullable, so "left null" no longer compiles. That is deliberate: a runtime
// assertion catches the regression on someone's phone, a type catches it in the editor.
//
// ⚠⚠ **This is a seam inventory, NOT a behavioural guard — and that was measured, not
// assumed.** Deleting `contentBlobStore = runtime.contentBlobStore` from the
// `initSyncService` call in `FirstPartyPlugins` leaves this whole suite GREEN (60/60,
// checked 2026-08-27). Read that before trusting it as W1 coverage.
//
// It cannot be better from this module: the applier is a private field of `SyncProcessor`,
// both applier-dependent methods (`openContentStream*`) are `internal` to
// `:sync-service-proxy`, and `emitFileContent` returns the same `false` for "no store" as
// for "the PUT failed" — so no assertion here can distinguish wired from unwired. What the
// mechanism does once wired is already proven a layer down by `FileContentReadPathTest`.
//
// So the real coverage for W1 is, in order: the non-nullable type above (a regression stops
// compiling), then the cross-device pass in `drive-web-parity.md` §5, which needs a second
// device and a live relay and cannot be CI'd. This file pins the seams that ARE observable
// — a store swapped for a stub, a missing file database, a broken Mode-B factory — and
// nothing more.
class ContentPlaneWiringTest {

    @Test
    fun contentPlaneSeams_areWiredForBothRealmKinds() = GlobalScope.promise {
        TenshuSdk.awaitReady().await()

        // A real relay-backed store, not an inert stand-in — the whole defect was a plane
        // that existed in name and moved nothing.
        assertTrue(
            JsRuntime.contentBlobStore is RestContentBlobStore,
            "the personal content plane must be a relay-backed store",
        )
        // The applier needs both halves; a store without a database builds nothing.
        assertNotNull(
            JsRuntime.fileDatabase,
            "the personal applier needs a file database alongside the store",
        )
        // MB-3 must keep working — W1 added the personal plane beside it, not instead of it.
        assertNotNull(
            JsRuntime.sharedRealmContentBlobStoreFactory("owner-under-test"),
            "Mode-B's per-realm content store must still resolve",
        )
        // ⚠⚠ Deliberately NOT asserted here: that the personal `SyncProcessor` is published
        // into `syncProcessorHolder`. Reaching it needs `JsRuntime.executionContext`, because
        // plugin `initialize` runs inside `ClientRegistry`'s initialization hook rather than
        // under `awaitReady()` — and materialising that from THIS class breaks eight tests in
        // four other suites. The singleton runtime lets the first class to touch it fix the
        // relay URL for the whole run, so a later `JsRuntime.configure(...)` throws
        // "called with a different URL after the sync chain was already constructed". This
        // class sorts first alphabetically, so it must stay a *reader* of already-built lazies
        // and never a *builder* of the sync chain. If you need that assertion, configure the
        // URL here first and accept that this file now owns the suite's relay URL.
    }
}
