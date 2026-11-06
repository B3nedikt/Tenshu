package org.tenshu.sync.relay.mailbox

/**
 * Which **blob refs** each content object is made of — the hop `content_edge` does not carry
 * (`content-plane-refcount.md` CR-4b).
 *
 * `(ownerId, contentId) -> {ref}`, where a ref is the `blob-<sha256(ciphertext)>` key the content
 * plane actually stores bytes under.
 *
 * ## ⚠⚠ Why an edge set alone cannot free a byte
 *
 * A [ContentEdgeRegistry] edge names a **contentId**. From one the relay can derive exactly one
 * ref — the manifest's, `sha256("manifest:" + contentId)` — because that address is a pure
 * function. Every **chunk** ref is `sha256(ciphertext)` and exists only inside the AEAD-encrypted
 * manifest, which the relay may not read (HR #1). So `allRefs − referencedRefs` computed from
 * edges alone reduces to *"every chunk ∪ every orphan manifest"*, which is not a diff at all — and
 * orphans are overwhelmingly **chunks**.
 *
 * ## ⚠ Keyed by content, not by holder
 *
 * That is why this is a second table rather than a wider `content_edge`. Content is immutable and
 * content-addressed, so a row here is written once and never rewritten, while an edge is per-holder
 * and mutable. Widening the edge key to include the ref would cost a row per chunk **per holder**
 * and destroy the `(owner, holder, property)` upsert that makes an overwrite supersede its
 * predecessor.
 *
 * ## ⚠⚠ HR #1 is untouched
 *
 * The relay is *told* this set; it decrypts nothing and reads no manifest. Which blobs form one
 * content object is the disclosure the threat model accepts at Low (ADR 0017).
 */
interface ContentRefRegistry : OwnerScopedStore {

    /**
     * Replace the ref set of [contentId] with [refs], atomically.
     *
     * ⚠⚠ **Replace, and atomically, are both load-bearing** (`content-plane-refcount.md` §7b.3 #6).
     * A connection dropped mid-declaration must leave the previous set intact rather than a partial
     * one: a partial set is a content object that looks like it is made of fewer chunks than it is,
     * and the missing ones then read as orphans. That is the failure mode this whole table exists
     * to make impossible, so it must not be introduced by the table itself.
     *
     * Idempotent: re-declaring the same set is a no-op, so a client may redeclare freely rather
     * than track what it has already said.
     *
     * ⚠ Transaction-safe: called inside an outer transaction — a push's revive, a whole
     * describe request — it joins that transaction rather than committing it.
     */
    fun declareRefs(ownerId: String, contentId: String, refs: Collection<String>)

    /** The refs [contentId] is made of, or empty when nothing has declared it. */
    fun refsOf(ownerId: String, contentId: String): Set<String>

    /**
     * [refsOf] for a whole page at once — `contentId -> refs`, omitting ids nothing has declared.
     *
     * ⚠⚠ **Grouped, which is what [referencedRefs] cannot give.** That one unions the refs of many
     * contents into one keep set; the age window needs to ask *per content* whether any of **its**
     * blobs is young, so a union would answer a different question.
     *
     * ⚠ Exists for cost, not for expressiveness: calling [refsOf] across a 512-id page is 512 round
     * trips through the single `RelayDatabase` connection on a route a sweep polls.
     */
    fun refsByContent(ownerId: String, contentIds: Collection<String>): Map<String, Set<String>>

    /**
     * Every contentId [ownerId] has **described** — i.e. that has ref rows.
     *
     * ⚠⚠ Described is not referenced. Joined against [ContentEdgeRegistry.referencedContentIds],
     * the *difference* is the freeing candidate set: content whose bytes are known and that no live
     * holder names. ⚠ Content that is **not** described is not in this set at all, and therefore
     * can never become a candidate — which is the whole of §9.5's "NEVER touch an undeclared ref",
     * made structural rather than left to a client's discipline.
     */
    fun declaredContentIds(ownerId: String): Set<String>

    /** Every owner with at least one described content — the sweep's arming seed walks them (D4). */
    fun describedOwnerIds(): Set<String>

    /**
     * Every ref [ownerId] still holds through *some* declared content — the **keep** set of an
     * enumeration pass.
     *
     * ⚠⚠ Not the same as "every ref that is live". A ref absent here is only a *candidate*: four
     * populations legitimately carry no declaration (`content-plane-refcount.md` §9.3), starting
     * with every byte uploaded before declarations existed. Freeing on absence alone deletes durable
     * content (`retention-quota-model.md` §2) — the client's own live walk is the authority.
     */
    fun referencedRefs(ownerId: String, contentIds: Collection<String>): Set<String>

    /**
     * Drop [contentId]'s ref rows — the counterpart of [declareRefs], for whatever eventually frees
     * the bytes.
     *
     * @return how many rows were dropped, so a caller can report rather than guess.
     */
    fun forgetContent(ownerId: String, contentId: String): Int
}
