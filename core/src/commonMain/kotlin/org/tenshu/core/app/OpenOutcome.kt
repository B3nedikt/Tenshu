package org.tenshu.core.app

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * What [OpenTarget] did, for a caller that has to be able to say something
 * (`control-cross-app-open.md` CO-D9).
 *
 * ⚠⚠ **"Delivered" is the strongest honest claim.** Whether the user then *saw* the row is not
 * knowable from here — the handler may open behind another window, or the user may switch away — so
 * this promises the weaker thing rather than a "shown" nobody can verify.
 *
 * ⚠ A composite rather than a bare [TString] for the reason `IntentChoice` is one: a return shape
 * with one field today is the cheapest thing to grow, while growing a bare *value* into a composite
 * is a reshape at every call site.
 */
interface OpenOutcome : Composite {

    override val schema: CompositeSchema get() = Schema

    /**
     * The `appId` that received it — `app.tenshu.calendar`, the realm-wide identity, **not** an
     * Android package or a macOS bundle id (CO-D4).
     */
    val appId: TString
        get() = getField(Fields.APP_ID)

    /**
     * Empty when Control already handed the request over; otherwise the id of a launch **the
     * caller must perform** (`control-cross-app-open.md` §5c).
     *
     * ⚠⚠ **Not a courtesy field — it is the whole reason this return is not just `appId`.** A
     * background proxy may not start an activity on Android (CO-D3, measured), so a cold handler
     * is opened by the foreground app that holds the user's gesture. Control pushes an immutable
     * ticket over the caller's own connection and names it here; the caller looks it up and fires
     * it. ⚠ The caller never learns *which* app — the ticket is opaque, which is what keeps CO-D1
     * enforced rather than advisory.
     *
     * ⭐ It doubles as the cold-start signal the earlier `coldStarted` boolean carried: a
     * non-empty ticket *means* the handler was not running. Two fields would have been two ways
     * to say one thing, and the second could disagree.
     */
    val launchTicketId: TString
        get() = getField(Fields.LAUNCH_TICKET_ID)

    companion object {
        const val ID = "tenshu/app/openOutcome"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.APP_ID)
                string(Fields.LAUNCH_TICKET_ID)
            },
        )

        fun create(
            context: TContext,
            appId: String,
            launchTicketId: String,
        ): OpenOutcome = context.create(
            type = ID,
            fields = listOf(
                appId.toTString(context),
                launchTicketId.toTString(context),
            ),
        )
    }

    object Fields {
        const val APP_ID = "$ID/appId"
        const val LAUNCH_TICKET_ID = "$ID/launchTicketId"
    }
}

/**
 * The payload the **receiving** app is handed — one shape, three carriers (CO-D3, CO-D8).
 *
 * ⭐ It rides whichever transport the situation allows and the app parses the same composite each
 * time: the existing proxy→client callback when it is running, the launch itself when Android or
 * iOS cold-starts it.
 *
 * ⚠⚠ **No new AIDL.** `IClientInterface.callback(in byte[] request)` already carries an opaque
 * serialised payload proxy→client, which is why CO-D3's *"the OS is only for cold start"* holds at
 * the transport level rather than merely in principle.
 *
 * ⚠ It carries an **identity, never content**. A handler not entitled to the row still cannot read
 * it; this says which row to try, not what is in it.
 */
interface OpenTargetRequest : Composite {

    override val schema: CompositeSchema get() = Schema

    val target: DataId
        get() = getField(Fields.TARGET)

    companion object {
        const val ID = "tenshu/app/openTargetRequest"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                composite(Fields.TARGET, type = DataId.Schema)
            },
        )

        fun create(context: TContext, target: DataId): OpenTargetRequest = context.create(
            type = ID,
            fields = listOf(target),
        )
    }

    object Fields {
        const val TARGET = "$ID/target"
    }
}
