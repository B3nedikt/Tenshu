package org.tenshu.sync.relay.mailbox

/**
 * CR-5's candidate predicate, in one place because two callers need it and a second copy is how the
 * two mechanisms drift apart (`content-plane-refcount.md` §9.5).
 *
 * A candidate is **described ∧ no live edge names it** — the difference between `content_ref` (what
 * the relay knows the bytes of) and `content_edge` (what a holder still references).
 *
 * ⚠⚠ **Candidates are not orphans.** A recreate (ADR 0006) and a move both leave a live row pointing
 * at content whose edge was legitimately dropped, so a client MUST veto against its own live walk
 * before reclaiming, and MUST reclaim nothing if that walk did not finish.
 *
 * ⚠ Content that is **not described** can never appear here, which is §9.5's *"never touch an
 * undeclared ref"* made structural. That same exclusion is why the undescribed population needs a
 * separate instrument — [computeContentInventory].
 */
fun candidateContentIds(
    ownerId: String,
    refs: ContentRefRegistry,
    edges: ContentEdgeRegistry,
): Sequence<String> {
    val referenced = edges.referencedContentIds(ownerId)
    return refs.declaredContentIds(ownerId).asSequence().filterNot { it in referenced }
}
