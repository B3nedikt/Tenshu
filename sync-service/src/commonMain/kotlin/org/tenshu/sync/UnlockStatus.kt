package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.toTBoolean

/**
 * Snapshot of the local-unlock primitive's state per
 * `docs/proposals/control-onboarding-ux.md` §2 and §3 — read by the
 * landing-state router on app launch and by the §3.1 unlock screen.
 *
 * - `isSetUp` true when a passphrase / biometric wrapper for the
 *   recovery phrase has been persisted (post-§4b.5 / §4a.6).
 * - `isLocked` true when the wrapper exists but the master keys are
 *   not loaded into memory — i.e. the user has to unlock to proceed.
 *
 * Both can be false simultaneously: legacy installs that pre-date the
 * unlock primitive store the recovery phrase plaintext; they auto-load
 * on proxy startup and present as `Active` until the user opts into
 * setup. v1 keeps that path working alongside the wrapper path.
 */
interface UnlockStatus : Composite {

    override val schema: CompositeSchema get() = Schema

    val isSetUp: TBoolean
        get() = getField(Fields.IS_SET_UP)

    val isLocked: TBoolean
        get() = getField(Fields.IS_LOCKED)

    /** True when a passphrase wrapper specifically exists — the passphrase floor for biometric. */
    val hasPassphrase: TBoolean
        get() = getField(Fields.HAS_PASSPHRASE)

    companion object {
        const val ID = "tenshu/sync/unlockStatus"

        val Schema = composite(
            id = ID,
            fields = {
                boolean(Fields.IS_SET_UP)
                boolean(Fields.IS_LOCKED)
                boolean(Fields.HAS_PASSPHRASE)
            }
        )

        fun create(
            context: TContext,
            isSetUp: Boolean,
            isLocked: Boolean,
            hasPassphrase: Boolean,
        ): UnlockStatus = context.create(
            type = ID,
            fields = listOf(
                isSetUp.toTBoolean(context),
                isLocked.toTBoolean(context),
                hasPassphrase.toTBoolean(context),
            )
        )
    }

    object Fields {
        const val IS_SET_UP = "$ID/isSetUp"
        const val IS_LOCKED = "$ID/isLocked"
        const val HAS_PASSPHRASE = "$ID/hasPassphrase"
    }
}
