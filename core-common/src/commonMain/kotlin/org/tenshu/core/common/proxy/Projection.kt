package org.tenshu.core.common.proxy

/**
 * A read-time projection captured from a chain's `select` and threaded into the
 * dbEntryMapper so a read materialises only what `select` will keep — instead of
 * walking the full object graph and discarding most of it after the fact.
 *
 * The **presence** of a `Projection` on a read means "a `select` is downstream and
 * will rebuild the entity it needs." That single fact licenses two skips in
 * `DbEntryMapper.deSerialize`:
 *
 *  1. **All composite relations are skipped.** Every relation read in the engine
 *     goes through `ReferenceResolver.resolveProperty` (a fresh DB re-load), never
 *     off the loaded entity's `properties` map — so a relation `select` keeps is
 *     re-resolved there, and one it drops is never needed. The relation-stripped
 *     entity is consumed by the downstream `select` and never escapes the chain.
 *     This is the bulk of a metadata listing's over-fetch.
 *
 *  2. **Composite scalar fields not in [keepFields] are skipped — but only when
 *     [skipUnprojectedComposites] is set.** Unlike relations, scalar fields are
 *     read *directly* off the loaded entity (`resolveReference`'s `isField` arm),
 *     so an intervening predicate/sort op (`searchAll`/`filter`/`sortBy`) before
 *     the `select` could read one. Skipping is therefore only safe when the
 *     `select` materialises the array directly (no intervening field reader);
 *     that case sets [skipUnprojectedComposites] = true and lists the kept fields.
 *     When a predicate sits between, [skipUnprojectedComposites] is false and all
 *     scalar fields are kept (only relations are skipped).
 *
 * A `null` projection (every non-`select` read) means "load the whole object".
 * This is what keeps direct field/relation readers
 * (ROs, repositories, mergers, the sync processor) correct: they never pass a
 * projection, so they always see a fully materialised entity.
 *
 * **Nesting ([children]).** A projection is a *tree*: when the dbEntryMapper
 * descends into a kept composite field it recurses with that field's child
 * projection ([children]`[fieldId]`) instead of `null`, so the subtree is pruned
 * at every level rather than resolved in depth (`core-nested-projection.md`). A
 * kept field with **no** child entry is materialised fully — nesting is strictly
 * opt-in, so a flat projection (`children` empty) prunes only the top level.
 *
 * @param keepFields composite scalar field ids the `select` named (only consulted
 *   when [skipUnprojectedComposites] is true).
 * @param skipUnprojectedComposites whether composite scalar fields outside
 *   [keepFields] may be skipped (true only for a direct `<array>.select { … }`).
 * @param children per kept field / named relation id → the sub-projection to apply
 *   when descending it; absent ⇒ that child materialises fully. Empty for a flat
 *   (single-level) projection.
 * @param keepProperties **scalar-valued** relation ids (a [org.tenshu.core.schema.ValueSchema]
 *   or constant property) to keep materialised on the loaded entity *despite* the
 *   projection — an exception to skip (1) above. The ordered-window fusion
 *   (`docs/proposals/core-ordered-window-fusion.md`) reads its sort key off the
 *   loaded row rather than through a per-row `resolveProperty` re-load, so a
 *   value-property sort key must survive the projection. Composite relations are
 *   never kept (a composite sort key is rejected before the read). Empty for every
 *   other read.
 *   ⚠ A non-empty set also makes the row deserialise **as an entity**, whatever the
 *   array reference says: a composite has no properties map to keep anything on. That
 *   is how a property key fuses over a composite-typed array such as `root.files`
 *   (`drive-sorted-folder-paging.md` §3 B2) — and why anything reading a row's type
 *   under such a projection must look at `Entity.type`, not `schema`.
 */
data class Projection(
    val keepFields: Set<String>,
    val skipUnprojectedComposites: Boolean,
    val children: Map<String, Projection> = emptyMap(),
    val keepProperties: Set<String> = emptySet(),
)
