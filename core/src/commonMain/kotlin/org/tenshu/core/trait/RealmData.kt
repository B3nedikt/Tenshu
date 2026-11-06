package org.tenshu.core.trait

import org.tenshu.core.schema.trait
import org.tenshu.core.schema.valueTraitRequirement

/**
 * Marker trait declaring that a data-type schema is **realm data** — a data type Tenshu
 * provides to apps and surfaces to consumers (Control's Data section, the JS SDK walker,
 * future schema-introspection tooling). See `docs/proposals/core-realm-data.md`.
 *
 * A **fieldless** marker, like [HeadlessSafe]: a type opts in by placing [RealmData.Schema]
 * in its schema's `traits = listOf(...)` list; presence is read by walking that list (see
 * `SchemaProvider.realmDataTypeIds`). The trait deliberately carries **no presentation**
 * (display name, icon, ordering): those are localisable / consumer-specific and live in each
 * consumer's UI (e.g. a `control-shared` presentation registry keyed by type id), not baked
 * into the wire-shared, consumer-agnostic metamodel.
 *
 * What the trait *does* assert beyond membership is a data **contract**: a realm data type
 * exposes a creation timestamp ([CREATED_AT], a `UnixInstant` [ValueTraitRequirement]) — the
 * structural sort *key* the Data section orders items newest-first by. A type binds its
 * fulfilling property via `ModuleConfiguration.fulfilValueRequirement`
 * (`File.CreatedAt` → `RealmData.CREATED_AT`), resolved through
 * `SchemaProvider.resolveValueRequirement`. This is data shape, not presentation, so it
 * belongs on the trait; the sort *order between types* is presentation and does not (§8).
 *
 * Enumeration keys off **declared**, not inherited, traits, so a sub-type whose schema
 * parents a realm-data type folds into its parent rather than appearing separately (§2.4).
 */
interface RealmData : Trait {
    companion object {
        const val ID = "tenshu/core/trait/realmData"

        /**
         * Requirement id for the creation-timestamp value property the Data section sorts
         * items newest-first by (§2.6 / §8). `File` fulfils it with `File.CreatedAt`.
         */
        const val CREATED_AT = "$ID/createdAt"

        val Schema: TraitSchema = trait(
            id = ID,
            traitRequirements = listOf(valueTraitRequirement(CREATED_AT)),
        )
    }
}
