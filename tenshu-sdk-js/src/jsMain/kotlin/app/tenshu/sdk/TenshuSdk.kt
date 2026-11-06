@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.RuntimeLease
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlin.js.Promise

@JsExport
object TenshuSdk {
    val version: String = "0.0.1-dev"

    // Reports current runtime state. `ready` flips to `true` once IDB
    // hydration has completed (see `JsRuntime.hydration`); use
    // `awaitReady()` to suspend until that happens. Cheap to call — no
    // forced construction of the runtime chain.
    fun runtimeStatus(): dynamic {
        val obj: dynamic = js("({})")
        obj.ready = JsRuntime.isReady()
        obj.nowMs = JsRuntime.clock.nowEpochMillis().toDouble()
        return obj
    }

    // Resolves once the runtime is ready (IDB opened + cache hydrated).
    // Apex calls this once before mounting; SDK ops also await internally
    // so calling this is defense-in-depth, not strictly required.
    fun awaitReady(): Promise<Unit> = GlobalScope.promise {
        JsRuntime.awaitReady()
    }

    // Type ids of every data type that declares the `RealmData` marker trait
    // (`docs/proposals/core-realm-data.md`). The web Control "Data" section walks
    // this generically — one card per id, no hard-coded type list (IA §5) — with
    // the apex owning the localised display name / icon / order per id (a
    // presentation registry keyed by these ids). `File` is the only v1 entry;
    // `Folder` folds into it (§2.4). Resolves once schemas are registered
    // (`awaitReady`); the walk itself is pure and synchronous.
    fun realmDataTypeIds(): Promise<Array<String>> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.schemaRegistry.realmDataTypeIds().toTypedArray()
    }

    // One runtime per origin (`docs/web/architecture.md` "Cross-tab"). The lease outcome, acquiring
    // it if this page has not yet: "held" (this tab runs Tenshu), "elsewhere" (another tab does —
    // every SDK call here rejects with "tenshu/runtime-elsewhere"), "displaced" (this tab handed it
    // over), or "unguarded" (no Web Locks in this browser).
    fun awaitRuntimeLease(): Promise<String> = GlobalScope.promise {
        JsRuntime.awaitLease().wire
    }

    // Notified on every lease change — how a holder learns another tab took over. Returns the
    // unsubscribe function. Does not acquire anything by itself.
    fun onRuntimeLeaseChange(callback: (String) -> Unit): () -> Unit {
        val job = GlobalScope.launch {
            RuntimeLease.default.state.collect { callback(it.wire) }
        }
        return { job.cancel() }
    }

    // "Use here": ask the tab holding the runtime to hand it over (it flushes and closes its stores
    // first) and resolve once it is free. ⚠ The caller must then RELOAD the page — this page's
    // runtime never opened its stores, so only a fresh one can take the lease.
    fun takeOverRuntime(): Promise<Unit> = GlobalScope.promise {
        RuntimeLease.default.useHere()
    }

    // Override the sync-relay base URL before any sync-touching SDK call
    // (`Realm.create`, `Pairing.submit`, `Recovery.claim`, …). Default is
    // `https://relay.tenshu.app`. Apex dev typically points this at a
    // locally-running `:sync-relay-server` (`http://localhost:8080`).
    // Throws if called after the relay has already been constructed.
    fun configure(relayBaseUrl: String) {
        JsRuntime.configure(relayBaseUrl)
    }
}
