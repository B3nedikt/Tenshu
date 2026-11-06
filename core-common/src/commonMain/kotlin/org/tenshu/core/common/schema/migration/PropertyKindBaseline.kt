package org.tenshu.core.common.schema.migration

import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ValueSchema

/**
 * The **property-kind baseline** — a pinned record of how each property id's value is *stored*, so
 * changing a property from inline to child-row (or back) fails at development time instead of
 * silently re-addressing rows on every device that already has them.
 *
 * ## The defect this exists for
 *
 * A property's stored slot carries **no type tag**: [org.tenshu.core.common.proxy.db.DbEntryMapper]
 * reads it by looking the property *id* up in the schema registry and branching on what it finds.
 * The two authoring forms put different kinds under the same id:
 *
 * | authoring | registers | stored as | read arm |
 * |---|---|---|---|
 * | `valueProperty` | a [ValueSchema] | the value **inline** | `is ValueSchema` |
 * | `dynamicProperty` | a `DynamicPropertyRelationSchema` | a child-row **reference** | `is CompositeSchema` |
 *
 * So flipping the form re-interprets every already-stored slot. Moving an inline `TByteArray`
 * property (`File.Thumbnail`/`ThumbnailLarge`) to a child row casts existing rows' inline bytes
 * to a child-row id: `ClassCastException` on every read, and — when the throw escapes the ingest
 * path outside its try — the pull cursor stops advancing and **sync wedges permanently**.
 *
 * ## ⚠⚠ Why the reader cannot be made to catch it
 *
 * The reader closes the arm it can: a stored kind that *cannot* be a child-row reference is dropped.
 * That closes `TByteArray` and every other non-integer type. It cannot close the general case,
 * because on a `TInteger` property the stale slot **is** a valid `LongValue` — byte-for-byte what a
 * legitimate child-row reference looks like. The stored *value* is then dereferenced as a row id,
 * and when something happens to live at that address the reader returns **another row's data as
 * this property's value, with no error anywhere**
 * (`ValueToDynamicPropertyMigrationTest.aCollidingRowIsReturnedAsThePropertysValue_withNoErrorAnywhere`
 * demonstrates precisely that).
 *
 * A reader can reject a kind *mismatch*. It can never reject a kind *match that means something
 * else*. So the check has to happen where the two versions are both visible — and the only place
 * that is true is a pinned baseline, exactly as for positional shape.
 *
 * ## Why [SchemaShapeBaseline] does not already cover it
 *
 * ⚠ It pins **positional** composite layout, and a property relation is stored in a **named map**,
 * not positionally. A kind flip leaves that baseline honestly green ("0 changed shapes"), and an
 * empty migration registered alongside the bump on the reasoning that "nothing reshaped
 * positionally" is true, and not sufficient. A relation's stored value *kind* is a stored-shape
 * change no positional check can see. This class is that missing counterpart, and the two are
 * deliberately separate files rather than one widened check: they answer different questions and
 * fail with different remedies.
 *
 * ## What a violation demands
 *
 * A kind change is **not forbidden** — a move to the content plane can be right. What it owes is
 * that the stale slots stop existing: a migration that strips them, shipped with the bump. The
 * baseline's job is to make that a deliberate act with a diff a reviewer can read, rather than a
 * consequence nobody noticed until a device wedged.
 *
 * ⚠ **Development-time only**, like its sibling: at runtime a property's kind simply *is* what this
 * build declares, and a check could only report a problem no user can act on.
 */
class PropertyKindBaseline(
    private val pinned: Map<String, PinnedKind>,
) {

    /**
     * How a property id's value is stored. Deliberately **binary** — it mirrors the only
     * distinction `DbEntryMapper` draws, and a finer taxonomy would pin churn that cannot corrupt
     * anything.
     */
    enum class StorageKind {
        /** `is ValueSchema` — the value sits inline in the row's property map. */
        INLINE,

        /** `is CompositeSchema` (which `RelationSchema` extends) — the slot is a child-row id. */
        CHILD_ROW,
    }

    /** One property's pinned state. [receiverTypeId] is diagnostic — it names where to look. */
    data class PinnedKind(
        val receiverTypeId: String,
        val kind: StorageKind,
    )

    sealed interface Violation {
        val propertyId: String

        /**
         * ⚠⚠ The hard one: the same property id stores a different kind of value than pinned.
         * Every row already holding a slot under this id is read through the other arm.
         */
        data class KindChanged(
            override val propertyId: String,
            val receiverTypeId: String,
            val pinnedKind: StorageKind,
            val liveKind: StorageKind,
        ) : Violation

        /** A live property the baseline has never seen. Additions get recorded, not blocked. */
        data class Unpinned(
            override val propertyId: String,
            val receiverTypeId: String,
        ) : Violation

        /**
         * A pinned property the live registry does not know. Only meaningful against a registry
         * that is supposed to hold everything — same caveat as [SchemaShapeBaseline.Violation.Retired].
         */
        data class Retired(override val propertyId: String) : Violation
    }

    fun check(live: SchemaRegistry, expectExhaustive: Boolean = false): List<Violation> {
        val liveKinds = live.propertyKinds()
        val violations = mutableListOf<Violation>()

        for ((propertyId, live) in liveKinds) {
            val pin = pinned[propertyId]
            if (pin == null) {
                violations += Violation.Unpinned(propertyId, live.receiverTypeId)
                continue
            }
            if (pin.kind == live.kind) continue
            violations += Violation.KindChanged(
                propertyId = propertyId,
                receiverTypeId = live.receiverTypeId,
                pinnedKind = pin.kind,
                liveKind = live.kind,
            )
        }

        if (expectExhaustive) {
            pinned.keys.filterNot { it in liveKinds }.forEach { violations += Violation.Retired(it) }
        }
        return violations
    }

    companion object {

        private const val SEP = '|'

        /** `propertyId|receiverTypeId|kind` per line; blank lines and `#` comments skipped. */
        fun parse(text: String): PropertyKindBaseline =
            PropertyKindBaseline(
                text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { line ->
                        val parts = line.split(SEP, limit = 3)
                        require(parts.size == 3) { "Malformed property-kind line: '$line'" }
                        val kind = requireNotNull(
                            StorageKind.entries.firstOrNull { it.name == parts[2] }
                        ) { "Unknown storage kind in line: '$line'" }
                        parts[0] to PinnedKind(parts[1], kind)
                    }
                    .toMap()
            )

        /** Pins [live]'s current state — the generator half of [parse]. */
        fun render(live: SchemaRegistry): String =
            live.propertyKinds()
                .entries
                .sortedBy { it.key }
                .joinToString("\n") { (propertyId, pin) ->
                    listOf(propertyId, pin.receiverTypeId, pin.kind.name).joinToString(SEP.toString())
                }

        /**
         * Every registered relation id that resolves to a schema whose kind decides a stored
         * slot's read arm, keyed by property id.
         *
         * ⚠ Sourced from the **relation** map rather than from `allSchemas()`: a schema id alone
         * says nothing about whether any row stores a slot under it, and the registry synthesises
         * placeholder schemas for every field id it ever sees. A relation entry is precisely the
         * statement "this id can appear as a slot on that receiver", which is the set at risk.
         *
         * ⚠ An id registered on several receivers is pinned once. The hazard is per *id* — the
         * read arm never sees the receiver — so a duplicate would add lines without adding
         * coverage. The receiver recorded is simply the first seen, and is diagnostic only.
         */
        private fun SchemaRegistry.propertyKinds(): Map<String, PinnedKind> {
            val out = LinkedHashMap<String, PinnedKind>()
            for ((receiverTypeId, propertyId) in registeredRelations()) {
                if (propertyId in out) continue
                val kind = when (getSchema(propertyId)) {
                    // ⚠ Order matters: `RelationSchema : CompositeSchema`, and a `ValueSchema` is
                    // neither — so testing ValueSchema first keeps this branch exactly as narrow
                    // as `DbEntryMapper`'s.
                    is ValueSchema -> StorageKind.INLINE
                    is CompositeSchema -> StorageKind.CHILD_ROW
                    // Anything else (or unregistered) has no stored-slot read arm to protect.
                    else -> continue
                }
                out[propertyId] = PinnedKind(receiverTypeId, kind)
            }
            return out
        }
    }
}

/** Renders [violations] as the failure a developer sees, or `null` when there is nothing to report. */
fun formatPropertyKindBaselineFailure(
    violations: List<PropertyKindBaseline.Violation>,
    regenerateHint: String,
): String? {
    if (violations.isEmpty()) return null

    val changed = violations.filterIsInstance<PropertyKindBaseline.Violation.KindChanged>()
    val unpinned = violations.filterIsInstance<PropertyKindBaseline.Violation.Unpinned>()
    val retired = violations.filterIsInstance<PropertyKindBaseline.Violation.Retired>()

    return buildString {
        if (changed.isNotEmpty()) {
            appendLine(
                "${changed.size} propert(ies) CHANGED HOW THEIR VALUE IS STORED. A property's " +
                    "stored slot carries no type tag, so every row that already holds one is now " +
                    "read through the other arm of DbEntryMapper — inline bytes dereferenced as a " +
                    "child-row id, or a row id read as a value. On a TByteArray that throws and " +
                    "wedges sync; on a TInteger it silently returns another row's data."
            )
            appendLine(
                "This is allowed, but it owes a migration that strips the stale slots, shipped " +
                    "with the version bump — not a reader that tolerates both. Then regenerate:"
            )
            changed.forEach {
                appendLine("  - ${it.propertyId} (on ${it.receiverTypeId})")
                appendLine("      pinned: ${it.pinnedKind}")
                appendLine("      live:   ${it.liveKind}")
            }
        }
        if (unpinned.isNotEmpty()) {
            appendLine("${unpinned.size} new propert(ies) are not pinned yet. Regenerate the baseline:")
            unpinned.forEach { appendLine("  - ${it.propertyId} (on ${it.receiverTypeId})") }
        }
        if (retired.isNotEmpty()) {
            appendLine(
                "${retired.size} pinned propert(ies) no longer exist. If that is intentional, " +
                    "regenerate the baseline:"
            )
            retired.forEach { appendLine("  - ${it.propertyId}") }
        }
        append(regenerateHint)
    }
}
