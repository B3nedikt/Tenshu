# Sync roadmap — retired

⚠⚠ **This document is retired. Do not add to it, and do not trust the state it recorded.**

It was a 2025-era phase checklist for the sync service. Every phase it tracked is now described
more accurately elsewhere, and by the time it was retired it carried at least three claims that
were simply false:

- it ticked `FetchOnDemandProperty` as implemented — that operation was removed and no longer
  exists (`docs/agent.md`, `sync-service/sync-agent.md` both say so);
- it left **Semantic Merging** unchecked, while eleven `SemanticMerger` implementations ship
  across six services, with their own ADR;
- it tracked **Ghost Entities**, `SyncBackendClient` and a `ReKeyingWorker` — none of which exist,
  and the last of which is explicitly *not* the design (ADR 0017 rules out bulk re-encryption; key
  retirement is GR-7's keyring).

That is the failure mode a checklist has and a design doc does not: it goes stale silently, because
nothing breaks when it is wrong.

## Where the content went

| What you wanted | Read instead |
|---|---|
| Sync architecture and protocol semantics | [`design.md`](design.md) |
| Deletion / tombstones | [ADR 0006](../adr/0006-sync-tombstones.md) + `design.md` §2.6 |
| Device management, pairing, revocation | [ADR 0007](../adr/0007-sync-device-management.md) + [`pairing-flow.md`](pairing-flow.md) |
| File content, the two-plane channel | [ADR 0017](../adr/0017-file-content-two-plane-channel.md) + `design.md` §2.7 |
| Merge strategy configuration | [ADR 0024](../adr/0024-merger-field-resolution.md), `sync-merge-strategies.md` |
| The relay wire surface | [`sync-relay-api.md`](../proposals/sync-relay-api.md) |
| Relay backend and deployment | [`sync-relay-persistence.md`](../proposals/sync-relay-persistence.md) |
| What is actually in flight | [`milestones.md`](../milestones.md), and the tracks table in `AGENTS.md` |
