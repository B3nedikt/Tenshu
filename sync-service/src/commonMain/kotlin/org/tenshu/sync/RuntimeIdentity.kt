package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTString

/**
 * **Who is hosting this sync runtime** (`core-schema-skew-surface.md` SK-2, §8.4): the app id and
 * version of the binary that owns the proxy a client is talking to.
 *
 * ## Why this exists — and why it is not a field on [SchemaFenceStatus]
 *
 * §3.1's rule is that *a fence belongs to a runtime, and a runtime is owned by exactly one
 * installable binary — name that binary*. On Android and desktop that binary is Control, serving
 * every app from one process; under iOS route 1 each app links its own proxy and is its own owner.
 * A client cannot infer which without asserting a deployment topology it does not own, so the
 * runtime says so itself.
 *
 * ⚠⚠ It must not be an `owningAppId` **field on [SchemaFenceStatus]** (§3.2's first design). That
 * is a catch-22:
 *
 *  - **Without a version bump** the appended field shifts a positional composite's layout, so a
 *    version-skewed app↔Control pair garbles it — the exact pair the banner exists to serve — and
 *    `SchemaShapeBaselineEnforcementTest` fails.
 *  - **With a bump** it is worse. Per `SyncService`'s own version KDoc, bumping this service makes
 *    *"the epoch writer raise the realm's `SyncService` entry on the next sync-attributed write
 *    and then pull-fence every not-yet-updated device"* — so the change meant to *explain* fences
 *    would *cause* them, realm-wide, over a purely local status row.
 *
 * A **new** composite escapes both horns, which is why this is one: a new non-`Syncable` type
 * reshapes nothing and no peer can garble on it — the same reason [SchemaFenceStatus] and its
 * relation owe **no bump**.
 *
 * ## Locally-derived, **NOT `Syncable`**
 *
 * Like [SchemaFenceStatus] and `SyncHealthStatus`: a row describing *this process*. It is
 * meaningless on another device, never enters a `SyncBlock`,
 * never reaches the relay (House Rule #1 untouched), and crosses only the already-authenticated
 * app↔Control IPC channel. It therefore owes **no `TENSHU_STORAGE_FORMAT_VERSION` bump and no
 * `SyncService` version bump** (HR #9) — see above for why the latter would be harmful rather
 * than merely unnecessary.
 *
 * ## One row per writer, not one row
 *
 * The key is the **writer's** host app id ([appId]), not a constant — the same shape as
 * [SyncCatchUpStatus], for the same reason. Under iOS route 1 every first-party app runs its own
 * proxy over **one** shared App-Group replica; a singleton row would name whichever app launched
 * last, so Fit could read Control's id and tell the user to update Control. Keyed by writer, each
 * runtime replaces its own row and leaves its siblings'.
 *
 * ⚠⚠ A reader that knows its host id (route 1) reads its own row with
 * `runtimeIdentity(hostAppId)`. The unkeyed read returns the *only* row and `null` when there are
 * several — see its KDoc. ⚠ A row outlives its process, so presence is not liveness.
 *
 * Keying by writer changes a stored *value*, not a field or a position, so it owes no bump either
 * (HR #9).
 *
 * ## Adding to it later
 *
 * Positional, like every composite here: a later field owes the same analysis. ⚠ Note the escape
 * that applies to [SchemaFenceStatus] applies to this type too — prefer a new composite id over
 * appending, for the same two reasons.
 */
interface RuntimeIdentity : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The writer: the host app id of the runtime this row describes — the same value as [appId]. */
    val key: TString
        get() = getField(Field.KEY)

    /**
     * The app id of the binary hosting this proxy — `"app.tenshu.control"` on Android and desktop,
     * the app's own id under iOS route 1.
     *
     * ⚠⚠ **Compare against [CONTROL_APP_ID], not against the reading app's own id.** The obvious
     * test — *"does this equal my app id? then the runtime is me"* — silently never matches,
     * because the ids in play use different conventions: a route-1 host is registered as
     * `"app.tenshu.fit.ios"` (its `clientId`) while the same app announces itself as
     * `"app.tenshu.fit"`. Anchoring on Control's id needs only **one** canonical value — the one
     * this file defines — and stays correct whatever convention any other host uses.
     */
    val appId: TString
        get() = getField(Field.APP_ID)

    /**
     * The host binary's version string, as the platform reports it (`BuildConfig` and friends) —
     * the same value `ErrorEvent.appVersion` already carries.
     *
     * ⚠ Free-text and **not** a schema version: never compare it to decide skew. Skew is decided
     * by the version vector; this is for telling a human which build they are running, in a
     * banner, an About screen or a support thread.
     */
    val appVersion: TString
        get() = getField(Field.APP_VERSION)

    companion object {
        const val ID = "tenshu/sync/runtimeIdentity"

        /** What a host that did not declare a version reports, rather than an empty string. */
        const val UNKNOWN_VERSION = "unknown"

        /**
         * The canonical id Control's proxy publishes on every platform — Android, desktop and
         * Control-on-iOS.
         *
         * ⚠⚠ The **one** value that has to be exact, and it is deliberately not reused from the
         * logging service, which registers Control as the bare `"control"`. Two conventions
         * already exist in this codebase and a third would be indistinguishable from a typo;
         * anything not equal to this is treated as "some other binary hosts the runtime", which
         * is the safe default — it names the app the user is in rather than sending them to
         * update something that may be current.
         */
        const val CONTROL_APP_ID = "app.tenshu.control"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(RuntimeIdentityDataIdProvider.Schema),
            fields = {
                string(Field.KEY)
                string(Field.APP_ID)
                string(Field.APP_VERSION)
            },
        )

        fun create(
            context: TContext,
            appId: String,
            appVersion: String = UNKNOWN_VERSION,
        ): RuntimeIdentity = context.create(
            type = ID,
            fields = listOf(
                appId.toTString(context),
                appId.toTString(context),
                appVersion.toTString(context),
            ),
        )
    }

    object Field {
        const val KEY = "$ID/key"
        const val APP_ID = "$ID/appId"
        const val APP_VERSION = "$ID/appVersion"
    }
}

/**
 * Stable identity for a [RuntimeIdentity] row — keyed on [RuntimeIdentity.Field.KEY], the
 * writer's id, so each writer's upsert replaces its own row and leaves the others.
 */
interface RuntimeIdentityDataIdProvider : CompositeDataIdProvider<RuntimeIdentity> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/sync/runtimeIdentity/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = RuntimeIdentity.Field.KEY,
        )
    }
}

/**
 * Whether Control hosts this runtime — the question a shell banner actually needs answered
 * (`core-schema-skew-surface.md` SK-2).
 *
 * ⚠ `null` (no row published) is **not** `false`: it means no host declared itself, so a caller
 * must say nothing about *which* app to update rather than guessing. Treating `null` as "not
 * Control" would tell every user on an un-updated wiring to update the app they are in.
 */
fun RuntimeIdentity?.isControlHosted(): Boolean? =
    this?.let { it.appId.get() == RuntimeIdentity.CONTROL_APP_ID }
