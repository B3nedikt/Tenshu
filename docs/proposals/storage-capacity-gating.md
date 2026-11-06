# Storage Capacity Gating — graduated

**Status:** Fully landed (Phases 1–5) and **graduated to
[ADR 0019](../adr/0019-storage-capacity-gating.md)**. This file is kept as a
redirect because the implementation references it by path in ~40 source-comment
sites; the design content now lives in the ADR.

**Where it went:**

- **Why / decisions / alternatives / when-to-revisit** →
  [ADR 0019 — Storage capacity gating](../adr/0019-storage-capacity-gating.md).
- **How it works** (the gate condition `hasStorage && fits`, the three tiers,
  the `:storage-service` module + ops + `StorageProvider` seam + typed errors,
  the Services deep-link) → ADR 0019 *Decision* + *Consequences*, and the
  `org.tenshu.storage*` source.
- **Entitlement / provision boundary** → ADR 0019 Decision 3 +
  [`tenshu-publisher.md`](tenshu-publisher.md).

**One-line summary:** every storage-consuming action (upload, folder upload,
Mode-A link share, Mode-B folder share) is gated on *account-exists AND
fits-in-quota* — proactively in the Drive UI (Tier 1), authoritatively inside
the proxy op (Tier 2, typed errors), and pre-flighted once for folder uploads
(Tier 3). `:storage-service` owns *provision* (limit-from-entitlement,
usage-from-relay) and is decoupled from the Publisher. v1 enforces against a
mocked entitlement; relay-side quota enforcement is deferred (honour-system).
