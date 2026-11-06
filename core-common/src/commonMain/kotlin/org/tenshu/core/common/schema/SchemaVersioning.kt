package org.tenshu.core.common.schema

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.schema.migration.SchemaMigrationRegistry
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/*
 * ⚠ FROZEN FOREVER (`core-schema-evolution.md` §6.2). These composites are the
 * schema-negotiation channel: the announce op's request/response and the block
 * version stamp. If their own wire shape ever changed, mismatched peers would
 * garble each other *before reaching the version check* — the negotiation
 * channel must never itself need negotiating. Additions go in a NEW composite
 * id, never as fields here. This freeze notice is normative, not decorative.
 *
 * ⚠⚠ **One exception, already spent** (§6.2, Appendix A.4): [ServiceVersionMismatch],
 * [SessionAdmission] and the `AnnounceSession` params were reshaped *in place* on 2026-10-28,
 * because the pre-Play reset wiped every store and no installed client or proxy could be on the
 * old shape. [ServiceVersion] and [VersionVector] were not touched — they also ride every synced
 * block's version stamp. After the Play open test ships its first build there is no such window:
 * a changed contract is a NEW op id with new composites, as §6.2 always said.
 */

/** One `(serviceId, version)` pair of a [VersionVector]. ⚠ Frozen forever (§6.2). */
interface ServiceVersion : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** The service's schema id, e.g. `"tenshu/file/service"`. */
    val serviceId: TString
        get() = getField(Fields.SERVICE_ID)

    /** The service's declared schema version (`ServiceSchema.version`). */
    val version: TInteger
        get() = getField(Fields.VERSION)

    companion object {
        const val ID = "tenshu/core/schema/serviceVersion"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.SERVICE_ID)
                int(Fields.VERSION)
            }
        )

        fun create(
            context: TContext,
            serviceId: String,
            version: Int,
        ): ServiceVersion = context.create(
            type = ID,
            fields = listOf(
                serviceId.toTString(context),
                version.toTInteger(context),
            )
        )
    }

    object Fields {
        const val SERVICE_ID = "$ID/serviceId"
        const val VERSION = "$ID/version"
    }
}

/**
 * A device's per-service schema version vector plus the global core floor
 * (`core-schema-evolution.md` §4): the one type shared by the announce op (§6),
 * the block version stamp (§5/§4.1.2), and — at E2 — the realm epoch record
 * (§7). ⚠ Frozen forever (§6.2).
 *
 * [coreVersion] is `:core`'s version — the `TENSHU_STORAGE_FORMAT_VERSION`
 * successor constant; `:core` registers no single `ServiceSchema`, so its
 * version rides this dedicated slot as the global floor (§4's caveat: a core
 * composite change shifts every descendant across all services).
 */
interface VersionVector : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val coreVersion: TInteger
        get() = getField(Fields.CORE_VERSION)

    val services: TList<ServiceVersion>
        get() = getField(Fields.SERVICES)

    companion object {
        const val ID = "tenshu/core/schema/versionVector"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                int(Fields.CORE_VERSION)
                list(Fields.SERVICES, of = ServiceVersion.Schema)
            }
        )

        fun create(
            context: TContext,
            coreVersion: Int,
            services: List<ServiceVersion>,
        ): VersionVector = context.create(
            type = ID,
            fields = listOf(
                coreVersion.toTInteger(context),
                SimpleTList(services),
            )
        )
    }

    object Fields {
        const val CORE_VERSION = "$ID/coreVersion"
        const val SERVICES = "$ID/services"
    }
}

/**
 * One refused service in a [SessionAdmission], with every number both sides hold about it, so the
 * client renders the right message without re-deriving the proxy's rule
 * (`core-schema-evolution.md` §6.3, `core-version-compatibility.md` §4.7):
 * `clientRequires > proxyCurrent` → "update Control" (the proxy is behind what the client needs);
 * `clientCurrent < proxyFloor` → "this app needs updating" (the client is cut off).
 *
 * ⚠ The client's two numbers are separate on purpose: a client whose *requirement* is below the
 * proxy's floor while its *current* version is above it must be admitted, and one field cannot
 * hold both (`core-version-compatibility.md` §4.3/§4.7).
 *
 * ⚠ Frozen (§6.2) — reshaped once, in place, at the pre-Play reset (2026-10-28, Appendix A.4).
 */
interface ServiceVersionMismatch : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val serviceId: TString
        get() = getField(Fields.SERVICE_ID)

    /** The version the client was built at. Compared against [proxyFloor]. */
    val clientCurrent: TInteger
        get() = getField(Fields.CLIENT_CURRENT)

    /** The lowest proxy version the client can work against. Compared against [proxyCurrent]. */
    val clientRequires: TInteger
        get() = getField(Fields.CLIENT_REQUIRES)

    /** The oldest client version the proxy still serves (its wire floor). */
    val proxyFloor: TInteger
        get() = getField(Fields.PROXY_FLOOR)

    /** The version the proxy runs; `0` for a service the proxy does not have at all. */
    val proxyCurrent: TInteger
        get() = getField(Fields.PROXY_CURRENT)

    companion object {
        const val ID = "tenshu/core/schema/serviceVersionMismatch"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.SERVICE_ID)
                int(Fields.CLIENT_CURRENT)
                int(Fields.CLIENT_REQUIRES)
                int(Fields.PROXY_FLOOR)
                int(Fields.PROXY_CURRENT)
            }
        )

        fun create(
            context: TContext,
            serviceId: String,
            clientCurrent: Int,
            clientRequires: Int,
            proxyFloor: Int,
            proxyCurrent: Int,
        ): ServiceVersionMismatch = context.create(
            type = ID,
            fields = listOf(
                serviceId.toTString(context),
                clientCurrent.toTInteger(context),
                clientRequires.toTInteger(context),
                proxyFloor.toTInteger(context),
                proxyCurrent.toTInteger(context),
            )
        )
    }

    object Fields {
        const val SERVICE_ID = "$ID/serviceId"
        const val CLIENT_CURRENT = "$ID/clientCurrent"
        const val CLIENT_REQUIRES = "$ID/clientRequires"
        const val PROXY_FLOOR = "$ID/proxyFloor"
        const val PROXY_CURRENT = "$ID/proxyCurrent"
    }
}

/**
 * The announce op's symmetric reply (`core-schema-evolution.md` §6.3). A refusal is a SUCCESSFUL
 * typed reply carrying this composite — never a bare error — because the payload IS the UX.
 *
 * The verdict is two booleans, [updateProxy] and [updateClient], rather than a constant family:
 * they span `core-version-compatibility.md` §4.7's four verdicts exactly (`ADMITTED`,
 * `UPDATE_PROXY`, `UPDATE_CLIENT`, `UPDATE_BOTH`). ⚠⚠ **Do not turn them into a `TConstant`.** A
 * client that has not been rebuilt cannot parse a constant id it does not register, so a fifth
 * member added later would break the parse of the negotiation channel itself, which is the one
 * thing §6.2 exists to prevent. Two booleans cannot gain a member.
 *
 * [proxyAppId]/[proxyAppVersion] name the binary the proxy is running in, so copy can say *which*
 * Control is installed (`core-version-compatibility.md` D4). They ride the reply because a refused
 * session cannot read the `RuntimeIdentity` row that otherwise carries them. Empty when the proxy's
 * wiring registers no [ProxyHostIdentity] (tests, the web runtime).
 *
 * ⚠ Frozen (§6.2) — reshaped once, in place, at the pre-Play reset (2026-10-28, Appendix A.4).
 */
interface SessionAdmission : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** `!updateProxy && !updateClient`, carried explicitly so the common check is one field. */
    val admitted: TBoolean
        get() = getField(Fields.ADMITTED)

    /** Some service's `clientRequires > proxyCurrent`: the proxy is behind. "Update Control". */
    val updateProxy: TBoolean
        get() = getField(Fields.UPDATE_PROXY)

    /** Some service's `clientCurrent < proxyFloor`: the client is cut off. "Update this app". */
    val updateClient: TBoolean
        get() = getField(Fields.UPDATE_CLIENT)

    /** The proxy's current vector. */
    val proxyVector: VersionVector
        get() = getField(Fields.PROXY_VECTOR)

    /** The oldest client version the proxy still serves, per service. */
    val wireFloors: VersionVector
        get() = getField(Fields.WIRE_FLOORS)

    /** Empty iff [admitted]. */
    val mismatches: TList<ServiceVersionMismatch>
        get() = getField(Fields.MISMATCHES)

    /** The app id of the binary hosting the proxy, e.g. Control's; empty when unknown. */
    val proxyAppId: TString
        get() = getField(Fields.PROXY_APP_ID)

    /** That binary's version name; empty when unknown. */
    val proxyAppVersion: TString
        get() = getField(Fields.PROXY_APP_VERSION)

    companion object {
        const val ID = "tenshu/core/schema/sessionAdmission"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                boolean(Fields.ADMITTED)
                boolean(Fields.UPDATE_PROXY)
                boolean(Fields.UPDATE_CLIENT)
                composite(Fields.PROXY_VECTOR, type = VersionVector.Schema)
                composite(Fields.WIRE_FLOORS, type = VersionVector.Schema)
                list(Fields.MISMATCHES, of = ServiceVersionMismatch.Schema)
                string(Fields.PROXY_APP_ID)
                string(Fields.PROXY_APP_VERSION)
            }
        )

        fun create(
            context: TContext,
            updateProxy: Boolean,
            updateClient: Boolean,
            proxyVector: VersionVector,
            wireFloors: VersionVector,
            mismatches: List<ServiceVersionMismatch>,
            proxyAppId: String,
            proxyAppVersion: String,
        ): SessionAdmission = context.create(
            type = ID,
            fields = listOf(
                (!updateProxy && !updateClient).toTBoolean(context),
                updateProxy.toTBoolean(context),
                updateClient.toTBoolean(context),
                proxyVector,
                wireFloors,
                SimpleTList(mismatches),
                proxyAppId.toTString(context),
                proxyAppVersion.toTString(context),
            )
        )
    }

    object Fields {
        const val ADMITTED = "$ID/admitted"
        const val UPDATE_PROXY = "$ID/updateProxy"
        const val UPDATE_CLIENT = "$ID/updateClient"
        const val PROXY_VECTOR = "$ID/proxyVector"
        const val WIRE_FLOORS = "$ID/wireFloors"
        const val MISMATCHES = "$ID/mismatches"
        const val PROXY_APP_ID = "$ID/proxyAppId"
        const val PROXY_APP_VERSION = "$ID/proxyAppVersion"
    }
}

/**
 * Assemble this runtime's current [VersionVector] by walking the registered
 * module graph (`core-schema-evolution.md` §4.1.1): every registered
 * [org.tenshu.core.schema.ServiceSchema] contributes its never-serialized
 * declared version; `:core`'s slot is [TENSHU_STORAGE_FORMAT_VERSION]. Sorted
 * by service id so the vector (and the block stamp built from it) is
 * deterministic. Nothing is declared separately, so nothing can drift.
 */
fun SchemaProvider.currentVersionVector(context: TContext): VersionVector =
    VersionVector.create(
        context = context,
        coreVersion = TENSHU_STORAGE_FORMAT_VERSION,
        services = serviceSchemas()
            .map { it.id.get() to it.version }
            .sortedBy { it.first }
            .map { (id, version) -> ServiceVersion.create(context, id, version) },
    )

/**
 * The per-service floors this runtime can still read
 * (`core-schema-evolution.md` §4/§8.4): DERIVED as the lowest contiguous
 * `fromVersion` of each service's registered
 * [SchemaMigration][org.tenshu.core.common.schema.migration.SchemaMigration]
 * chain ([SchemaMigrationRegistry.floorFor]) — cutting off old formats =
 * deleting migration steps. Migrations ride the module graph, so only a
 * [SchemaRegistry] provider carries them; for any other provider (and for a
 * service with no registered chain) the floor equals the current version.
 *
 * ⚠⚠ **`:core`'s slot is the exception: it is always [TENSHU_STORAGE_FORMAT_VERSION],
 * and it never consults the core chain**, even though the per-service floors next to it
 * do. A `schemaMigration(CORE_SERVICE_ID, …)` step therefore lowers the *store* floor
 * ([org.tenshu.core.common.schema.migration.StoreSchemaMigrations.coreReadableFloor]),
 * and an older store migrates. A *client* one core version back is still refused
 * (`admitted = false`, `floor == current`).
 *
 * The asymmetry is deliberate. "Can I read rows written by the old build?" and "May an
 * app compiled against the old core primitives talk to this proxy?" are different
 * questions. For IPC the strict answer is the safe one: callstack params are
 * client-serialized composites, and a new nested core op fails at *decode*, before any typed
 * refusal can fire (pre-reset fmt 22). The obvious expectation is that a core chain relaxes
 * both floors. It doesn't, so don't "fix" this.
 */
fun SchemaProvider.currentVersionFloors(context: TContext): VersionVector {
    val registered = (this as? SchemaRegistry)?.schemaMigrations().orEmpty()
    if (registered.isEmpty()) return currentVersionVector(context)
    val chains = SchemaMigrationRegistry().apply { registered.forEach(::register) }
    return VersionVector.create(
        context = context,
        coreVersion = TENSHU_STORAGE_FORMAT_VERSION,
        services = serviceSchemas()
            .map { it.id.get() to it.version }
            .sortedBy { it.first }
            .map { (id, version) ->
                ServiceVersion.create(context, id, chains.floorFor(id, version))
            },
    )
}

/**
 * The lowest proxy version, per service, that this client can work against: its **wire
 * requirement** (`core-version-compatibility.md` §4.3), announced beside its current vector.
 *
 * ⚠ Equal to [currentVersionVector] until VC-1 lands: a requirement walks back over the client's
 * own migration steps whose declared effects let an older proxy serve it, and no step declares
 * effects yet. The envelope carries the two vectors separately *now* so that VC-1 changes only this
 * function and never the frozen wire shape.
 */
fun SchemaProvider.currentVersionRequirement(context: TContext): VersionVector =
    currentVersionVector(context)

/**
 * Which binary is hosting this proxy — echoed in every [SessionAdmission] so a refused client can
 * name the Control it is talking to (`core-version-compatibility.md` D4).
 *
 * Bound by the proxy's DI as a [TComponent] under [COMPONENT_ID]; absent ⇒ the reply carries empty
 * strings, which the client must read as "unknown", never as a name.
 */
class ProxyHostIdentity(
    val appId: String,
    val appVersion: String,
) : TComponent {
    companion object {
        const val COMPONENT_ID = "tenshu/core/component/proxyHostIdentity"
    }
}

/** This proxy's [ProxyHostIdentity], or `null` when its wiring registers none. */
fun TContext.proxyHostIdentity(): ProxyHostIdentity? =
    components[ProxyHostIdentity.COMPONENT_ID] as? ProxyHostIdentity

/** Convenience for comparisons: the vector as a plain `serviceId → version` map. */
fun VersionVector.toMap(): Map<String, Int> =
    services.toList().associate { it.serviceId.get() to it.version.get().toInt() }
