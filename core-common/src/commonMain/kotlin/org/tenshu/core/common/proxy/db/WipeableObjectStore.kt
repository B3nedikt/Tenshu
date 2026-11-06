package org.tenshu.core.common.proxy.db

/**
 * A store that can drop everything it holds — the **D3 discard path**
 * (`ios-identity-reinstall-gate.md` D3, decided 2026-08-23; RG-3).
 *
 * ## ⚠⚠ Why this is a separate capability, not a method on [ObjectStore]
 *
 * `ObjectStore` has ~65 references and most are test doubles with no use for a wipe. Widening the
 * interface would force every one of them to implement it, and this repo has been bitten three
 * times in a single day by widening an interface without compiling every implementor. There are
 * only four real implementations, so the capability is opt-in and the doubles stay untouched.
 *
 * ## ⚠⚠ And why the caller must report whether it applied
 *
 * An optional capability is **silently inert** wherever it is not implemented — which is the exact
 * trap this track keeps falling into. A discard that quietly skips the wipe is not a smaller
 * discard; it is the half reset D3 exists to prevent. So callers resolve it with `as?` and fold the
 * outcome into their user-visible result, never assume it ran. `ForgetThisDeviceImplementation`
 * does this alongside its retirement and queue-purge notes.
 *
 * ## What "everything" means
 *
 * Every object row **and** the cursors co-located with them. A store keeping its sync cursor while
 * dropping the rows it cursors over would resume mid-history against data that is gone.
 */
interface WipeableObjectStore {

    /**
     * Drop every object this store holds. Idempotent — wiping an empty store is a no-op, not an
     * error, because a discard may retry.
     *
     * ⚠ `suspend` because the durable implementations commit through a writer queue and the caller
     * needs the wipe to have *happened* before it reports success. A fire-and-forget wipe that
     * loses the race with process exit leaves exactly the state D3 forbids.
     */
    suspend fun wipeAllObjects()
}

/**
 * ⚠⚠ A wipe that **started and did not finish** (`ios-shared-store-sqlite.md` §5.1, §13's rule):
 * the store is marked as being wiped, part of it is gone, and a later open finishes the wipe.
 * Thrown by [WipeableObjectStore.wipeAllObjects] of the iOS shared store on SQLite
 * (`WipeNotFinished` there); no other store throws it.
 *
 * It is **not** "not wiped": a caller that goes on to the steps that follow a completed wipe (the
 * custody purge) leaves custody gone over a store still present, the state D3 forbids. So a caller
 * tells it apart, stops before the custody purge, and reports "not finished"
 * (`ForgetThisDeviceImplementation`, `ControlSurface.wipeDeviceState`).
 */
open class StoreWipeNotFinished(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A store handle that no longer serves: a write through it is refused before it touches anything.
 * Thrown only by the iOS shared store on SQLite (`ReplicaReplaced` there), once its handle was
 * fenced by a wipe, a migration or its release (`ios-shared-store-sqlite.md` §6). No other store
 * throws it, so a caller that catches it changes nothing off iOS.
 */
open class StoreHandleFenced(message: String) : RuntimeException(message)
