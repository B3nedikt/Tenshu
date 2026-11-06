package org.tenshu.core.common.schema.migration

import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ConstantSchema

/**
 * The **shape baseline** — a pinned record of every composite's positional
 * shape, so reshaping one without bumping its service's version fails loudly at
 * development time instead of garbling a peer's parser
 * (`core-schema-evolution.md` §8.9).
 *
 * ## Why this has to exist
 *
 * Composites serialize **positionally** — no field tags, no count, no version
 * marker (§2 fact 1) — so *any* field added, removed, reordered or
 * required/optional-flipped changes how every older reader decodes the bytes
 * that follow it. No blanket wipe absorbs that: a store at or above the derived
 * core floor migrates rather than being wiped and resynced (House Rule #9), so a
 * reshape shipped without its migration is silent data corruption. This class is
 * what turns it into a refused build.
 *
 * ## Why [SchemaMigrationRegistry.verify] is not already enough
 *
 * That check compares a chain's last frozen `to` against the live schema, which
 * catches "the live schema moved without a new migration step" — but **only for
 * types that already appear in some registered migration**. A type with no
 * snapshot history has nothing to compare against, and that is very nearly
 * every type in the repo (most types appear in no registered chain). The
 * baseline supplies the missing counterpart: a frozen shape for *everything*.
 *
 * ## What it does and does not demand
 *
 * A reshaped type must have seen a **service-version bump**. It is deliberately
 * *not* required to have a `CompositeMigration` — ⚠ not every composite is a
 * stored row. Op parameter objects are client-serialized composites too
 * (§2 fact 3), so reshaping them breaks old *callers* mid-parse and owes a bump
 * and lockstep, but owes no store migration because nothing persisted them.
 * Telling those two classes apart is the store gate's problem (§8.9); this
 * check demands the bump, which both classes owe.
 *
 * The fingerprint is [shapeFingerprint] — the same
 * `(field id, base type id, isOptional)` triple, in declaration order, that
 * [structurallyEquals] already uses for the chain check, so a change this
 * refuses is exactly a change that would move the positional decode plan.
 *
 * ⚠ **Development-time only.** Nothing here runs in production: at runtime a
 * schema's shape simply *is* whatever it is, and a check could only report a
 * problem no user can act on. The enforcement point is a test over the widest
 * available registry.
 */
class SchemaShapeBaseline(
    private val pinned: Map<String, PinnedShape>,
) {

    /**
     * One type's pinned state: the shape, plus the version of the service that
     * owns it *at the moment the shape was pinned*. [serviceId] is `null` for a
     * type no module attributes to a service, in which case the version is
     * `:core`'s slot — which is [TENSHU_STORAGE_FORMAT_VERSION] (see
     * `SchemaVersioning.currentVersionVector`).
     */
    data class PinnedShape(
        val serviceId: String?,
        val serviceVersion: Int,
        val shape: String,
    )

    /** Why a type failed the baseline. */
    sealed interface Violation {
        val typeId: String

        /**
         * ⚠⚠ The hard one: the shape moved and the owning service's version did
         * not. Old readers will mis-parse these rows positionally.
         */
        data class ReshapedWithoutBump(
            override val typeId: String,
            val serviceId: String?,
            val version: Int,
            val pinnedShape: String,
            val liveShape: String,
        ) : Violation

        /**
         * The shape moved *and* the version was bumped — the change is
         * accounted for, the pinned file just needs regenerating.
         */
        data class StaleBaseline(
            override val typeId: String,
            val serviceId: String?,
            val pinnedVersion: Int,
            val liveVersion: Int,
        ) : Violation

        /**
         * A live type the baseline has never seen.
         *
         * ⚠⚠ [isConstant] is not decoration — the two cases owe different things. A new
         * **composite** is safe on the wire: nothing older can be handed one, so it is recorded
         * and the baseline regenerated. A new **constant** is the opposite. It joins a family that
         * already exists, so an older peer *can* be sent it on a field it already reads, cannot
         * resolve the id, and throws `UnknownTypeTagException` mid-parse — which reaches the app as
         * a failed read and gets reported as "Control is unreachable". It therefore owes a
         * service-version bump, so the announce refuses the pairing and the schema-skew surface
         * says the true thing instead.
         */
        data class Unpinned(
            override val typeId: String,
            val serviceId: String?,
            val isConstant: Boolean = false,
        ) : Violation

        /**
         * A pinned type the live registry does not know. Only meaningful when
         * [check] runs against a registry that is supposed to hold everything —
         * a narrower registry legitimately knows fewer types, which is why this
         * is reported separately rather than mixed in with the hard failures.
         */
        data class Retired(override val typeId: String) : Violation
    }

    /**
     * Compares [live]'s composites against the pinned shapes.
     *
     * [expectExhaustive] controls whether [Violation.Retired] is reported: pass
     * `true` only from a check whose registry holds every module in the repo.
     * Everything else is registry-width-independent — an absent type is simply
     * not judged.
     */
    fun check(live: SchemaRegistry, expectExhaustive: Boolean = false): List<Violation> {
        // ⚠ [pinnedShapes], the same source [render] uses. They must not drift: a `check` that
        // read only composites would report every pinned constant as [Violation.Retired] on the
        // very first run — the file says one thing and the check looks at another.
        val liveShapes = live.pinnedShapes()
        val violations = mutableListOf<Violation>()

        for ((typeId, liveShape) in liveShapes) {
            val serviceId = live.owningServiceOf(typeId)
            val liveVersion = live.versionOf(serviceId)
            val pin = pinned[typeId]

            if (pin == null) {
                violations += Violation.Unpinned(typeId, serviceId, isConstant = liveShape.isConstantShape())
                continue
            }
            if (pin.shape == liveShape) {
                // ⚠⚠ A bump that reshapes nothing still has to move the pinned version, or the
                // guard DISARMS ITSELF: with `pin.serviceVersion` behind `liveVersion`, the next
                // real reshape takes the `StaleBaseline` branch below — "accounted for, regenerate"
                // — instead of `ReshapedWithoutBump`, so a positional change ships with no bump, no
                // migration step and no epoch fence. That is not hypothetical: it happened on
                // `feat/n1-describe-handles`, where file-service 13→14 and health 2→3 left 108
                // pins stale and nothing failed.
                if (pin.serviceVersion != liveVersion) {
                    violations += Violation.StaleBaseline(typeId, serviceId, pin.serviceVersion, liveVersion)
                }
                continue
            }

            violations += if (liveVersion > pin.serviceVersion) {
                Violation.StaleBaseline(typeId, serviceId, pin.serviceVersion, liveVersion)
            } else {
                Violation.ReshapedWithoutBump(
                    typeId = typeId,
                    serviceId = serviceId,
                    version = liveVersion,
                    pinnedShape = pin.shape,
                    liveShape = liveShape,
                )
            }
        }

        if (expectExhaustive) {
            pinned.keys.filterNot { it in liveShapes }.forEach { violations += Violation.Retired(it) }
        }
        return violations
    }

    companion object {

        /** Field separator in the pinned format. Absent from every type/field id. */
        private const val SEP = '|'

        /** Stands in for an unattributable type's service id (see [PinnedShape]). */
        const val NO_SERVICE = "-"

        /**
         * Parses the pinned format — `typeId|serviceId|version|shape` per line;
         * blank lines and `#` comments skipped, so the pinned file can carry a
         * header explaining how to regenerate it.
         */
        fun parse(text: String): SchemaShapeBaseline {
            val pinned = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val parts = line.split(SEP, limit = 4)
                    require(parts.size == 4) { "Malformed baseline line: '$line'" }
                    val serviceId = parts[1].takeUnless { it == NO_SERVICE }
                    val version = requireNotNull(parts[2].toIntOrNull()) {
                        "Malformed baseline version in line: '$line'"
                    }
                    parts[0] to PinnedShape(serviceId, version, parts[3])
                }
                .toMap()
            return SchemaShapeBaseline(pinned)
        }

        /**
         * Pins [live]'s current state — the generator half of [parse].
         *
         * ⚠ `sortedBy`, not `toSortedMap`: the latter is a JVM-only stdlib
         * extension and this file is `commonMain`, so using it broke
         * `:core-common:compileKotlinJs` (and with it the web build) while every
         * JVM/desktop task stayed green.
         */
        fun render(live: SchemaRegistry): String =
            live.pinnedShapes()
                .entries
                .sortedBy { it.key }
                .joinToString("\n") { (typeId, shape) ->
                    val serviceId = live.owningServiceOf(typeId)
                    val version = live.versionOf(serviceId)
                    listOf(typeId, serviceId ?: NO_SERVICE, version.toString(), shape)
                        .joinToString(SEP.toString())
                }

        /**
         * Everything this baseline pins: composites by positional layout, **and constants by
         * existence**.
         *
         * ⚠⚠ Constants are pinned too. A `TConstant` is an identity, not a layout, so it has no
         * positional shape to protect — but adding a member to a sealed family (a new
         * `FileCategory`, a new `PairingFailure`) changes the **wire vocabulary**, and with only
         * layouts pinned every guard stays green and no version bump is required.
         *
         * That matters because House Rule 9 offers this baseline as the reason an author does not
         * have to be sure: *"Unsure whether you reshaped anything? You do not have to be."* For a
         * constant-only change that reassurance would be false. The announce compares a per-service
         * version vector, so it catches the skew — **if** the version moved, and nothing else makes it.
         *
         * The failure is not loud: a peer sends an id this build cannot resolve,
         * `BinaryParser` throws `UnknownTypeTagException`, and because that is an
         * `IllegalStateException` raised *outside* the executor's own `try`, it surfaces to an app
         * as a failed read — which every repository reports as **"Control is unreachable"**. The
         * proxy is fine; the client is out of date, which is exactly what the schema-skew surface
         * (SK-1…SK-4) exists to say and never gets the chance to.
         */
        private fun SchemaRegistry.pinnedShapes(): Map<String, String> =
            compositeShapes() + constantShapes()

        /**
         * Every registered constant, keyed by id, valued by the family it belongs to.
         *
         * ⚠ The value is the **parent** id rather than a layout, because a constant has none — and
         * it is not merely filler: it makes the diff read as *"a new member of `FileCategory`"*
         * rather than a bare id, and it means a constant that changes families is a shape change
         * rather than a silent retire-plus-add.
         *
         * ⚠ Added and removed constants both surface through the checks that already exist —
         * [Violation.Unpinned] and [Violation.Retired] — so `check` treats them like any type.
         */
        private fun SchemaRegistry.constantShapes(): Map<String, String> =
            allSchemas()
                .filterIsInstance<ConstantSchema>()
                .associate { it.id.get() to "$CONSTANT_PREFIX${it.parent?.id?.get() ?: "?"}" }

        /**
         * Marks a pinned line as a constant rather than a positional layout.
         *
         * ⚠ Read back out of the pinned *shape* rather than carried in a fifth column, so the
         * on-disk format — `typeId|serviceId|version|shape`, four fields — is unchanged and every
         * existing line parses exactly as before.
         */
        private const val CONSTANT_PREFIX = "constant@"

        internal fun String.isConstantShape(): Boolean = startsWith(CONSTANT_PREFIX)

        /**
         * Every registered composite that **has** a positional layout, keyed by
         * type id.
         *
         * ⚠ The `isNotEmpty()` filter is load-bearing, not tidying. A registry
         * holds far more than data types: `ModuleConfigurationImpl.registerFields`
         * synthesises a placeholder schema for **every field id it ever sees**, and
         * relation/service/trait schemas are composites too. All of them have empty
         * `values`, so pinning them would add thousands of content-free lines and
         * bury the diff that is this baseline's entire product.
         *
         * Sound because a composite with no fields has no positional decode plan
         * to protect — there is nothing an older reader could mis-parse. A type
         * that *gains* its first field appears as a new entry
         * ([Violation.Unpinned]) rather than slipping through.
         */
        private fun SchemaRegistry.compositeShapes(): Map<String, String> =
            allSchemas()
                .filterIsInstance<CompositeSchema>()
                .associate { it.id.get() to it.shapeFingerprint() }
                .filterValues { it.isNotEmpty() }

        /**
         * The version the baseline judges [serviceId] by: the declared
         * [org.tenshu.core.schema.ServiceSchema.version], or `:core`'s slot for
         * an unattributable type — which is [TENSHU_STORAGE_FORMAT_VERSION], the
         * same value `currentVersionVector` puts in the vector's core slot.
         */
        private fun SchemaRegistry.versionOf(serviceId: String?): Int =
            serviceId
                ?.let { id -> serviceSchemas().firstOrNull { it.id.get() == id }?.version }
                ?: TENSHU_STORAGE_FORMAT_VERSION
    }
}

/**
 * This composite's positional shape as one stable line: `fieldId@typeId` per
 * field in declaration order, `?`-suffixed when optional.
 *
 * Deliberately the same information [structurallyEquals] compares — ordered
 * `(field id, base type id, isOptional)`. Rendered readably rather than hashed
 * so a baseline diff shows *what* moved (which field, to which position), which
 * is the whole review signal for a positional format.
 */
fun CompositeSchema.shapeFingerprint(): String =
    structuralShape().joinToString(",") { (fieldId, typeId, isOptional) ->
        "$fieldId@${typeId ?: "?"}${if (isOptional) "?" else ""}"
    }

/**
 * Whether this violation's only difference is the operation's return type — see the split in
 * [formatShapeBaselineFailure] for why that deserves its own message.
 */
private fun SchemaShapeBaseline.Violation.ReshapedWithoutBump.differsOnlyByReturnType(): Boolean =
    pinnedShape.withoutReturnEntry() == liveShape.withoutReturnEntry() &&
        pinnedShape.returnEntry() != liveShape.returnEntry()

private fun String.returnEntry(): String? =
    split(",").firstOrNull { it.startsWith(RETURN_SHAPE_KEY) }

private fun String.withoutReturnEntry(): String =
    split(",").filterNot { it.startsWith(RETURN_SHAPE_KEY) }.joinToString(",")

/**
 * Renders [violations] as the actionable failure message a developer sees, or
 * `null` when there is nothing to report.
 */
fun formatShapeBaselineFailure(
    violations: List<SchemaShapeBaseline.Violation>,
    regenerateHint: String,
): String? {
    if (violations.isEmpty()) return null

    val allReshaped = violations.filterIsInstance<SchemaShapeBaseline.Violation.ReshapedWithoutBump>()
    // ⚠ Split out, because the two need different advice. A positional reshape mis-parses old
    // rows; a changed RETURN TYPE cannot — a return value is written tagged. Reporting the
    // second under the first's headline would send the reader hunting for a decode bug that
    // does not exist, and the honest failure here is subtler: nothing crashes, the stale peer
    // just answers `null` and the caller silently reads a zero.
    val returnOnly = allReshaped.filter { it.differsOnlyByReturnType() }
    val reshaped = allReshaped - returnOnly.toSet()
    val stale = violations.filterIsInstance<SchemaShapeBaseline.Violation.StaleBaseline>()
    val unpinned = violations.filterIsInstance<SchemaShapeBaseline.Violation.Unpinned>()
    val retired = violations.filterIsInstance<SchemaShapeBaseline.Violation.Retired>()

    return buildString {
        if (reshaped.isNotEmpty()) {
            appendLine(
                "${reshaped.size} type(s) were RESHAPED WITHOUT A VERSION BUMP. Composites " +
                    "serialize positionally, so every older reader will mis-parse these rows " +
                    "(core-schema-evolution.md §2 fact 1). Bump the owning service's version " +
                    "(`service(id, version = N)`) and register the SchemaMigration step for it, " +
                    "then regenerate the baseline:"
            )
            reshaped.forEach {
                appendLine("  - ${it.typeId} (service ${it.serviceId ?: "core"} @ v${it.version})")
                appendLine("      pinned: ${it.pinnedShape}")
                appendLine("      live:   ${it.liveShape}")
            }
        }
        if (returnOnly.isNotEmpty()) {
            appendLine(
                "${returnOnly.size} operation(s) changed their RETURN TYPE without a version " +
                    "bump. This does NOT mis-parse stored rows — a return value is written " +
                    "tagged, so no decode plan moves. It is a wire-contract change between " +
                    "peers: a client built against the new type talking to an older proxy gets " +
                    "the old value back and nothing fails loudly, because the announce gate " +
                    "compares version numbers rather than shapes. Bump the owning service's " +
                    "version so that pairing is refused instead, then regenerate the baseline:"
            )
            returnOnly.forEach {
                appendLine("  - ${it.typeId} (service ${it.serviceId ?: "core"} @ v${it.version})")
                appendLine("      pinned return: ${it.pinnedShape.returnEntry() ?: "(none)"}")
                appendLine("      live return:   ${it.liveShape.returnEntry() ?: "(none)"}")
            }
        }
        if (stale.isNotEmpty()) {
            appendLine(
                // ⚠ Two different causes reach this branch, and naming only the first sends an
                // author diffing for a positional change that does not exist: a shape change that
                // WAS accounted for by a bump, and a bump that reshaped nothing at all (which must
                // still move the pin, or the guard disarms itself for that service).
                "${stale.size} type(s) have a stale pinned version — either the shape changed and " +
                    "the bump accounted for it, or the version moved and nothing reshaped. Both " +
                    "need the baseline regenerated; neither is a defect. Regenerate it:"
            )
            stale.forEach {
                appendLine("  - ${it.typeId} (v${it.pinnedVersion} → v${it.liveVersion})")
            }
        }
        val newConstants = unpinned.filter { it.isConstant }
        if (newConstants.isNotEmpty()) {
            appendLine(
                "${newConstants.size} new CONSTANT(s) widen the wire vocabulary. Unlike a new " +
                    "composite, a constant joins a family older peers already read — they will be " +
                    "sent an id they cannot resolve and throw mid-parse, which surfaces to the " +
                    "user as \"Control is unreachable\" rather than as the version skew it is. " +
                    "Bump the owning service's version so the announce refuses the pairing and " +
                    "the schema-skew banner says the true thing, then regenerate the baseline:"
            )
            newConstants.forEach {
                appendLine("  - ${it.typeId} (service ${it.serviceId ?: "core"})")
            }
        }
        val newComposites = unpinned.filterNot { it.isConstant }
        if (newComposites.isNotEmpty()) {
            appendLine(
                "${newComposites.size} new type(s) are not pinned yet. Regenerate the baseline:"
            )
            newComposites.forEach { appendLine("  - ${it.typeId} (service ${it.serviceId ?: "core"})") }
        }
        if (retired.isNotEmpty()) {
            appendLine(
                "${retired.size} pinned type(s) no longer exist. If that is intentional, " +
                    "regenerate the baseline:"
            )
            retired.forEach { appendLine("  - ${it.typeId}") }
        }
        append(regenerateHint)
    }
}
