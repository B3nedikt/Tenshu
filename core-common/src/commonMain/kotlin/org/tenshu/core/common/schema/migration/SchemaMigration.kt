package org.tenshu.core.common.schema.migration

import org.tenshu.core.Data
import org.tenshu.core.core.TContext
import org.tenshu.core.schema.CompositeSchema

/**
 * One composite type's reshape for one service version bump
 * (`core-schema-evolution.md` §8.4). The ONE transform artifact shared by both
 * migration seams: local store rows migrate eagerly at open (§8.1), relay
 * history upcasts lazily at read (§8.2) — same domain transform, registered
 * once per service per bump.
 *
 * The normal authoring surface is the declarative field-mapping builder
 * ([schemaMigration] / [FieldMappingBuilder]), which GENERATES the [migrate]
 * implementation from mapping rules. Hand-implementing this interface is the
 * ESCAPE HATCH for shapes a per-field mapping can't express (type splits /
 * merges, cross-type moves).
 */
interface CompositeMigration {

    /** The composite type this migration reshapes (its stable schema id). */
    val typeId: String

    /**
     * Frozen snapshot of the type's schema AS AT the step's `fromVersion` —
     * the DECODE key. Positional decode needs the *producing* field list
     * (§2 fact 1: composites serialize positionally, no tags, no count), so
     * the current schema cannot parse old bytes, period. Declared as an
     * inline `composite(...)` literal, never a reference to the live schema
     * object — the live one keeps moving.
     */
    val from: CompositeSchema

    /**
     * Frozen snapshot AS AT `fromVersion + 1` — the ENCODE key.
     * Chain-integrity invariant, verified at init
     * ([SchemaMigrationRegistry.verify]): must structurally equal the NEXT
     * migration's [from] for this type, or the LIVE schema when no later
     * migration touches the type. Referencing the live schema here is
     * legitimate *only* while the type has no later migration — the day a
     * later bump reshapes it, this must be frozen into a literal or init
     * refuses to start.
     */
    val to: CompositeSchema

    /**
     * Reshape one value decoded under [from] into its [to] shape. Pure.
     * `null` ⇒ drop the value — a deliberate decision, never a parse
     * accident (replacing today's silent parse-to-null-and-drop).
     */
    fun migrate(old: Data, context: MigrationContext): Data?
}

/**
 * The registered unit (`core-schema-evolution.md` §8.4): one service, one
 * version bump, the composite types the bump reshaped. Most bumps touch 1–3
 * types out of a service's many; a bump that reshaped no types registers an
 * empty [migrations] list to keep the per-service chain contiguous (the
 * readable floor is DERIVED as the lowest contiguous [fromVersion] — cutting
 * off old formats = deleting migration steps, §4).
 */
class SchemaMigration(
    val serviceId: String,
    /** The version this step migrates FROM; its output version is `fromVersion + 1`. */
    val fromVersion: Int,
    val migrations: List<CompositeMigration>,
    /**
     * Named-map property slots this bump retires ([SchemaMigrationBuilder.dropProperty]). Stripped
     * from every stored row of the type at the store seam; see [DroppedProperty].
     */
    val droppedProperties: List<DroppedProperty> = emptyList(),
)

/**
 * A property slot retired by a bump — `valueProperty` (inline) or relation (child-row id) alike.
 *
 * ## Why a retired property needs a step at all
 *
 * A property lives in a row's **named** map, outside the positional field list [CompositeMigration]
 * covers, so the codec preserves it verbatim — and `DbEntryMapper` reads every slot by looking its id
 * up in the live registry, failing loudly on an id it does not know. Unregistering a property whose
 * slots are still stored therefore breaks every full read of an existing row. This removes the slots
 * in the same journaled pass as the step's composite rewrites (CR-2 B6 retired the reference roots
 * this way, `content-plane-cr2-b6-spec.md` §6).
 *
 * ⚠ **Store seam only.** The relay-history upcast ([PayloadUpcaster]) does not strip it: a peer
 * still writing the property is below the bump and fenced by the realm epoch, and B6's bump coincided
 * with a relay reset, so no stored history carries it. A later retirement that must read old history
 * owes that half.
 */
class DroppedProperty(val typeId: String, val propertyId: String)

/**
 * What a migration may consult — deliberately narrow: no store writes, no IO,
 * and deliberately **NO CLOCK** (`core-schema-evolution.md` §8.4). A migration
 * cannot synthesize timestamps, which makes the fake-default anti-pattern
 * (`createdAt = now()` for rows that predate the field) unrepresentable by
 * construction: a new optional field starts absent ("this row predates the
 * field"), and a new required field must [FieldMappingBuilder.derive] from
 * data that actually exists.
 */
class MigrationContext(
    /** Schema-registry access for building the new shape (`context.create`). */
    val context: TContext,
    val deviceId: String,
)
