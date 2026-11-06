# Shared-realm file model — identity, placement, and genericity

**Status:** Phase 3d.2b UI **complete** (3d.2b-i…v landed); only on-device round-trip verification (manual) +
the deferred mount-into-personal-tree (its own future design slice) remain. **3d.2b decisions locked (maintainer, 2026-06-02):** (1) the inbound
*Shared with me* tab is **membership-driven, mount optional** — it lists `SharedRealmMembership` rows with
`role = member` and browses each realm directly via the read ops; creating a `Placement` (filing the realm
into the personal tree) is an explicit user action, not automatic on join. (2) The **joiner-naming gap** is
closed by a **wrapper root `SharedFile`**: `shareFolderIntoRealm` now represents the shared folder as a single
named node at the realm root (`isFolder = true`), with the folder's contents nested under it — so the joiner
reads the folder's human-readable name straight from realm content (where it belongs, the file domain), never
needing a name field on the generic `SharedRealmMembership`. **3d.2b-i (wrapper root) + 3d.2b-ii (browse
screen) + 3d.2b-iii (`getSharedRealmMemberships`) + 3d.2b-iv (inbound *Shared with me* tab + folder-only
Share gate) + 3d.2b-v (outbound *Shared folders* role-filtered to `role = owner`) landed.**
Outstanding in 3d.2b: on-device outbound→inbound round-trip verification
(manual — can't run in a worktree). **Deferred (own future slice):** the explicit "Add to my Drive" mount — it
needs a personal-FS placement-rendering design first (a mounted realm has *no* visible home in the file browse
today; the inbound tab already gives browse access, so mounting adds nothing until that lands).
3d.2a landed (the shared-realm runtime is now wired into Control's prod proxy DI — Drive
uses Control over IPC, so the native apps can now create/sync shared realms). The service
layer + the read path are proven device-to-device over the dev relay. Phase 1 + 2 + 3a + 3b.1 + 3b.2a +
3b.2b + 3c.1 + 3c.1b + 3c.1c + 3c.1d + 3c.2a + 3c.2b + 3d.1 + 3d.2a landed
(the
realm-internal file record + its tree maintainer + the share op + the SharedFile/Placement model re-home +
host-side content fan-out + realm-aware recipient reassembly + the navigable-tree foundation + the read ops
+ the cross-device browse proof + the realm-routed OnDemand read + the `Placement` connector + its
enumeration + the Drive share-a-folder producer + the Control prod-DI runtime wiring). Reframes SR-3c
(`sync-multi-realm-runtime.md` §3.2 F/G) after the maintainer observed that Tenshu's file model
conflates a file's *identity* with its *location*, but a shared file lives in several virtual file
systems at once. The shared-realm **runtime** (SR-M + SR-3b) is complete and unaffected; this is the
file-service **application** model on top.

> **Decision (2026-06-01) — additive identity, personal `File` untouched.** The original D1 proposed
> *re-keying* the existing personal `File` from `stringDataId(path)` to a stable `fileId`. That is
> withdrawn. Realms are separate encrypted stores (per-realm keys, `$realmId::`-namespaced DataIds,
> per-realm content stores), so a file appearing in both your personal FS and a shared realm is
> **already two stored records** regardless — the "one identity" of the hard-link is *logical* (a
> shared `fileId` each realm's record carries), not one physical row. Re-keying the personal `File`
> therefore buys nothing a sidecar record can't, while forcing a migration on the hottest code paths;
> and it doesn't even save the per-realm content fan-out on edit (different realm keys ⇒ a manifest is
> emitted per realm either way). So the personal realm keeps **path-as-identity, unchanged on the
> wire, zero migration**, and the stable `fileId` is introduced **additively** as the *cross-realm*
> identity — native for files born in a shared realm, and attached to a personal file (via a new
> `Placement`) only when it is shared. D1 below is rewritten accordingly. (Aligns with the
> "add new content, don't restructure" house rule.)

> **Decision (2026-06-01) — two distinct links: `SharedFile` (content) + `Placement` (the connector).**
> The original design conflated two different things under one "Placement". They are now split:
>  - **`SharedFile`** is the *content* side — a file living **inside a shared realm**, located in that
>    realm's own internal tree by a stable `fileId` (under a `parentFileId`, with a `name`). Same
>    structure for every member; realm-namespaced (`"$realmId::$fileId"`). *This is the role the merged
>    `Placement` (§6 items 1–3) was actually filling, so it is renamed `SharedFile`.*
>  - **`Placement`** is repurposed to the *connector* — a per-member **mount** of a whole shared realm
>    into that member's **personal** FS at a location of their choosing (`/Work/X` for me, `/Projects/Y`
>    for you). It references a **realm id** (opaque), never a content type — so it is content-type-
>    agnostic *by construction*: a future `ChatService`/`SharedDocument` realm is mounted the same way
>    (see §4 "Generalisation to other services"). Built with the read path (Phase 3c).
>
> *Why a realm-level connector, not a typed item reference?* Because "a shared folder is a realm of its
> own" (SR-3b), the unit of sharing is always a **realm** — so the connector only ever points at a realm
> id, dissolving the "reference any content type" problem rather than engineering around it (a marker
> trait / `(type,id)` pointer / per-service subtypes were the rejected alternatives). The realm's
> *internal* structure is the content entities' own concern (`SharedFile`'s `parentFileId`/`name`).

---

## 1. The problem

`File` is keyed by its path — `FileDataIdProvider = stringDataId(File.Field.PATH)`, so the DataId
string *is* the path. "One file" therefore *is* "one path". But a shared file inherently has **multiple
locations at once**: its place in the owner's personal FS, its place in the shared realm's FS, and
potentially several shared realms. A single `path`-as-key cannot represent that, which is why the
`$realmId::`-prefix approaches (SR-3c options A/B) both fought the path arithmetic.

Three candidate models (`sync-multi-realm-runtime.md` §3.2 G):

1. **Move** — the file leaves the personal FS, lives only in the shared realm. Contradicts "it should
   stay where it is *and* appear in the share."
2. **Copy** — independent duplicates per FS; edits diverge. Wrong for collaboration.
3. **Placement / hard-link** — **one** file identity, *placed* at multiple `(realm, path)` locations,
   edits propagating to all. The Unix inode/hard-link model. **Chosen.**

## 2. What's already decoupled (so this is bounded)

File **content** is already identity-independent: chunks are content-addressed
(`blob-$sha256(ciphertext)`, [ADR 0017](../adr/0017-file-content-two-plane-channel.md)), so the bytes don't care about any path or
realm. In each realm the manifest binds content to that realm's file record via
`targetEntityId.getDataIdHash()`; in the personal realm that stays the path-derived DataId (unchanged),
and in a shared realm it is the `fileId`-derived DataId of the shared record.

Under the additive model the new surface is contained to:
- **(a) Cross-realm identity** — a stable `fileId` (random, allocated once at a file's birth in a
  shared realm, or at share time for a personal file). The personal `File` is **not** re-keyed; path
  stays its local identity.
- **(b) The shared-realm content + internal tree** — a **`SharedFile`** record per file in a realm:
  `(realmId, fileId, parentFileId, name)` keyed `"$realmId::$fileId"`. A realm's internal tree is walked
  over its `SharedFile`s (the `SharedFileRelationMaintainer` derives per-parent children arrays). The
  personal realm's tree stays path-derived via the existing `ParentFilesRelationMaintainer` — unchanged.
- **(c) The connector** — a **`Placement`** record (personal realm) mounting a shared realm into the
  member's personal FS at a chosen location. Per-member; references a realm id.
- **(d) Manifest binding in shared realms** — the `SharedFile` is `fileId`-keyed, so its content
  manifest binds to the `fileId`-derived DataId; content follows the identity within that realm.

## 3. Decisions

**D1 — Stable `fileId` is the *cross-realm* identity, introduced additively; the personal `File` is
untouched** (revised 2026-06-01; see the decision banner above). The personal realm keeps
`FileDataIdProvider` on `path` — same wire shape, no migration. A stable, location-independent `fileId`
(a random id, allocated once) is the identity used **across** realms: it is the key of a file's record
in a shared realm and the `fileId` carried by every `Placement`. A personal-origin file acquires a
`fileId` *only when it is shared* (minted into a `Placement` at share time); a file born in a shared
realm gets one at create. There is no re-key and no `hash(path)` seed — the personal `File`'s
serialization never changes. (`path`/`name` remain the personal realm's local identity/attribute; in a
shared realm, `name` + `parentFileId` on the `Placement` carry location.)

**D2 — `SharedFile` carries a realm's content + internal tree.** A `SharedFile(realmId, fileId,
parentFileId, name)` composite (Syncable, realm-namespaced `"$realmId::$fileId"`) is a file living
**inside** a shared realm, located in that realm's *own* internal tree by its stable `fileId`. A shared
realm's `FileSystem.list` walks `SharedFile`s, not path strings (the *personal* realm's tree stays
path-derived via the unchanged `ParentFilesRelationMaintainer` — `SharedFile` is additive, not a
replacement there). One `fileId` → one `SharedFile` per realm it's in; the records are logically distinct
per realm (different encryption, different namespace) but share the `fileId` so a member sees "the same
file," and an edit fans a manifest into each realm that holds it (per-realm content routing,
SR-3c-prereq) — content converges by content-addressing. Content + metadata (size, timestamps, bytes)
live here; the on-demand `SharedFile.Data` bytes + the manifest binding land with content fan-out
(§6 Phase 3b).

**D3 — `Placement` is the connector: a per-member mount of a *realm*.** A `Placement(sharedRealmId,
parentPath, name)` composite (Syncable, **personal** realm) records that this member has mounted a shared
realm into their personal FS at a chosen location. It references a **realm id** — never a `SharedFile`,
never a content type — so it is content-type-agnostic **by construction**. Two members can mount the same
realm at different personal locations; the realm's internal structure (the `SharedFile` tree) is the same
for both. When the personal FS navigates *into* a mounted location, the listing switches to that realm's
`SharedFile` tree. (Keyed by `sharedRealmId`; unprefixed, so it routes to the personal realm.)

**D4 — Genericity: the realm holds any service's content; the connector references a realm.** The SR-M
runtime syncs *any* namespaced Syncable, so a shared realm is realm-scoped storage for any service's
types. Genericity is achieved **not** by a `Share`/`SharedItem` supertype on entities (that would couple
every shareable type to a sharing notion and collides with the Mode-A `Share`, asset A17), but by making
the **connector reference a realm**: `Placement` mounts a realm, and what's *inside* (a `SharedFile`
tree, a `ChatConversation`, a `SharedDocument`) is the realm's own concern. A new sharing-capable service
adds its own realm-namespaced content type and reuses `Placement` unchanged (see §4).

**D5 — "Share a folder" = create `SharedFile`s + a `Placement`, not move.** SR-3 / Q23 is re-framed:
sharing a subtree bootstraps a realm (`SyncService.createSharedRealm()`), mints a `fileId` per file, and
saves a `SharedFile` for each in the realm's internal tree; the sharer also gets a `Placement` mounting
the new realm. The files **stay** in the personal FS unchanged (their path-keyed personal `File` records
are untouched). Not a move, not a copy — one logical identity, now present in two realms. This supersedes
the SR-3c "recreate-then-tombstone" migration shape.

## 4. Generalisation to other services

The two-link split (`SharedFile` = content, `Placement` = realm mount) is the *files* instance of a
service-agnostic pattern. Because `Placement` mounts a **realm** (not a typed item), a new
sharing-capable service needs **zero** changes to the connector, the realm runtime, or the sync
protocol — it only adds its own realm-namespaced content type:

- **A shared chat (`ChatService`).** The realm holds `ChatMessage(realmId, messageId, authorId, sentAt,
  body)` rows (append-only, `"$realmId::$messageId"`), ordered by `sentAt` — no folder tree, so no
  per-parent children maintainer; the service's own "list messages" reads the realm's message
  collection. A member "joins the chat" exactly as they "mount a folder": a `Placement(sharedRealmId,
  …)` in their personal space. The connector doesn't know or care that the realm contains messages
  rather than files.
- **A shared document (`SharedDocument`).** The realm holds one `Document(realmId, …)` plus its CRDT/op
  log as realm-namespaced rows; there's *one* logical item, so there isn't even a collection to list —
  the `Placement` mounts the realm and the editor opens the single `Document`. Still the same connector.
- **Files (this doc).** The realm holds a `SharedFile` *tree* (the one case needing a per-parent
  children maintainer) + content via the per-realm content plane. `Placement` mounts the realm; the FS
  surfaces the `SharedFile` tree under the mount point.

The contract each service implements is therefore minimal and *optional per service*: "what realm-scoped
Syncable(s) do my things become, and how do I list them?" — files need a tree, a chat needs ordering, a
document needs neither. The realm just holds them; `Placement` just mounts the realm.

### Why this generalises well

- **Multi-realm by construction** — a file shared into three realms is one `fileId` with three
  `SharedFile`s; no duplication, edits converge by content-addressing.
- **New services for free** — Chat/Document/Board reuse the *same* shared-realm runtime + the *same*
  `Placement` connector; only their content type is new. No `Share` plumbing, no per-type sharing code,
  no connector changes.
- **The content plane is already there** — content-addressed chunks + the SR-3c-prereq per-realm
  `ContentBlobStore` mean a `SharedFile`'s bytes are reachable in the realm that holds it.

## 5. Cost / risk

The additive model **removes** the original §5 risk: there is no `File` wire-shape change, no
re-key, and no migration of existing personal data — the hottest paths (`UploadFile` / `CreateFolder` /
`MoveFile` / `DeleteFile` / `ParentFilesRelationMaintainer` / `FileSystem.list` / the personal manifest
binding) are untouched. The new surface is *additive*: a `SharedFile` content type + its tree
maintainer, a `Placement` connector, and the share op. All greenfield in the shared-realm layer, with
the existing `FolderSyncSystemTest` / file-service suites plus new tests as the safety net.

*Rename note (3b):* the type that §6 items 1–3 introduced as `Placement` was actually the realm-internal
file record (D2), so it is renamed **`SharedFile`** (and `PlacementRelationMaintainer` →
`SharedFileRelationMaintainer`). The name `Placement` is freed for the connector (D3), built in Phase 3c.
No production data exists, so this is a pure code/doc rename.

## 6. Phasing (additive model)

1. **`SharedFile` primitive (realm-internal file record) — LANDED (renamed in 3b):** the
   `SharedFile(realmId, fileId, parentFileId, name)` composite + `SharedFileDataIdProvider` (keyed by the
   derived `compositeKey = "$realmId::$fileId"`) + `Syncable(Eager)`. Schema + DTO + wire round-trip
   tests; **zero change to the personal `File` or any existing op**. (Landed as `Placement`; renamed.)
2. **`SharedFileRelationMaintainer` — LANDED (renamed in 3b):** a realm-aware `DataObserver`
   that derives a realm's internal tree by maintaining, per parent, the array of its children — keyed on
   the parent's DataId (`EntityPropertyDataId` under `SharedFile.CHILDREN_RELATION_ID`), mirroring
   `FileHistoryRelationMaintainer`. One observer serves all realms (shared-realm saves flow through the
   same `ExecutionContext`; a `SharedFile` carries its own `realmId`); the personal realm keeps its
   path-derived `ParentFilesRelationMaintainer`. Save-side only; `onDataDeleted` is a no-op until removal
   (Phase 4). **Routing key resolved:** `realmId` **is** the `::` namespace prefix of the key
   (`RealmRegistry.realmForDataId` routes on `key.substringBefore("::")`).
3. **Share op — `ShareFolderIntoRealm` — LANDED (wrapper root added in 3d.2b-i):**
   `FileSystem.shareFolderIntoRealm(folderPath, realmId)` enumerates the personal folder subtree, mints a
   fresh `fileId` per descendant, and saves a `SharedFile` for each. The
   personal path-keyed `File` is untouched (D5); the maintainer builds the realm's tree. **Bootstrap is
   *not* folded in** — realm creation is `SyncService.createSharedRealm()`; the caller passes the
   `ownerId` as `realmId`, keeping the op pure `:file-service`. **fileIds minted fresh per call**;
   idempotent re-share via a path→fileId sidecar is Phase 4.
   **3d.2b-i — wrapper root:** the shared folder is now represented in the realm by a single **wrapper**
   `SharedFile` at `ROOT_PARENT` (its `name` = the folder name, `isFolder = true`), and the folder's contents
   nest under the wrapper's `fileId` (direct children under the wrapper; deeper under their parent's `fileId`).
   This carries the folder's human-readable name into the realm so a joiner — who only ever sees realm
   content, never the owner's personal path or `Placement` — reads it via `listSharedFolder(realmId,
   ROOT_PARENT)`. Closes the joiner-naming gap without a name field on the generic `SharedRealmMembership`
   (name belongs in the file domain). The sharer accepts the minor duplication (folder name on the personal
   `File` *and* the wrapper `SharedFile`); the joiner has the one source.
3b. **Shared-realm content — IN PROGRESS.** Sub-sliced:
   - **3b.1 — model re-home — LANDED:** renamed the merged `Placement`→`SharedFile` (it was
     the realm-internal record, D2) + `PlacementRelationMaintainer`→`SharedFileRelationMaintainer`; freed
     the name `Placement` for the connector (D3). Doc rewritten to the two-link model + §4 generalisation.
   - **3b.2a — content fan-out, host side — LANDED:** `SharedFile.DATA_PROPERTY_ID` (the
     content slot) + `ShareFolderIntoRealm` fans each placed *file*'s bytes into the realm via the
     realm-aware `FileContentSyncEmitter` — reads plaintext from the host's own `FileDatabase` (personal
     path), chunks + AEAD under the **realm's** keys, `PUT`s to the realm content store, manifest binds
     to the `SharedFile`'s namespaced DataId. Folders carry no bytes. One `awaitEmitIdle()` barrier
     (entity-before-bytes, ADR 0015). Threat-model **T-SR-05** (content readable by realm membership;
     House Rule #1 intact).
   - **3b.2b — recipient reassembly, realm-aware (this slice):** the read path was **not** realm-aware —
     the per-realm `SyncProcessor` reused the *personal* `FileContentManifestApplier` (personal content
     store + personal-key decrypt), so a shared file's chunks couldn't be fetched/decrypted. Fixed:
     `FileContentManifestApplier` gains `realmKeys` (decrypt under the realm's `sharedVolumeKey`; null =
     personal, unchanged), and the per-realm `processorFactory` wires an applier with the **realm's**
     `ContentBlobStore` + `derivedKeys`. `FileBytesPropertyWriter` now also supports
     `SharedFile.DATA_PROPERTY_ID`, resolving a realm byte-path (`/.shared/$realmId/$fileId`) — a
     deterministic location decoupled from the human name/tree (which live on the `SharedFile`). Proven
     by `FileContentReadPathTest` (explicit-realm-keys reassembly + wrong-keys gate-fail) +
     `FileBytesPropertyWriterTest`.
   - **3b.2c — OnDemand read chain + cross-device proof (deferred to 3c):** the projectable OnDemand
     `SharedFile.Data` handle + a `SharedFile` read op (so a member's `.get(SharedFile.Data)` triggers
     the resolve→applier) + the cross-realm system test (host shares, joiner reads the bytes). These
     need the same read machinery 3c builds for `FileSystem.list`, so they land there with the UI
     consumer.
4. **Read path — Phase 3c.** A shared realm browses as a navigable file system of its own.
   **Surface decision (2026-06-02): dedicated shared-realm ops, NOT the chain DSL rooted at a realm.**
   Two platform limits ruled out reusing the personal browse chain for a *runtime* realm: a literal
   per-realm `FileSystem` service needs parameterised-service resolution (a service keyed by `realmId`),
   AND — more fundamentally — `RequestChain` only addresses entities by **static relation ids**
   (`addReference(String)`), with no way to root a chain at a runtime-computed realm DataId. Both are
   sizable platform additions for no UX gain. So the shared-realm read surface is **ops** carrying
   `realmId` as a parameter: `listSharedFolder(realmId, parentFileId) → TList<SharedFile>` (drill by
   `fileId`; realm root = `ROOT_PARENT`) + `readSharedFile(realmId, fileId) → bytes` (OnDemand fetch from
   the realm content store → realm-aware reassembly). The UI walks parent→children exactly as a personal
   folder browse does. (The two Drive sections — *Shared* outbound + *Shared with me* inbound, by
   `SharedRealmMembership` role — each enumerate realms and browse one; IA leaning **two** entries,
   link-shares grouped under *Shared*, finalised at 3d. Personal Drive unchanged.) Sub-sliced:
   - **3c.1 — navigable-tree foundation — LANDED:** `SharedFile.isFolder` (folder vs leaf, set
     by the share op) + `SharedFile.Children` entity relation (the per-parent children array the
     maintainer builds) + a `SharedFile` client RO. (The `Children` *chain projection* is superseded by
     the `listSharedFolder` op, which reads the same array; `isFolder` + the array are the live data.)
   - **3c.1b — read ops — LANDED:** `ListSharedFolder` + `ReadSharedFile` ops (schema +
     client RO + proxy impl + registrations). `listSharedFolder` reads the maintainer's children array;
     `readSharedFile` dispatches `OnDemandResolution.resolve(…, SharedFile.DATA_PROPERTY_ID)` (realm-aware
     applier reassembles to the realm byte-path) then reads it back. Proven locally by
     `ListSharedFolderOperationTest` (browse incl. drilling into a subfolder).
   - **3c.1c — cross-device BROWSE proof — LANDED (this slice):** `SharedRealmFileReadSystemTest`
     (`:system-test-utils`) pairs a host + joiner, the host shares a one-file folder into a freshly
     bootstrapped shared realm, and the joiner — which auto-joins via the membership — **browses the
     realm's tree over the dev relay with real crypto** (`listSharedFolder(realmId, ROOT_PARENT)` surfaces
     `doc.bin`). This exercises the SharedFile path end-to-end: 3a placement → realm emit-routing → joiner
     per-realm pull → `SharedFileRelationMaintainer` firing on the pulled saves → the read op. Green and
     fast (~1.6s; the earlier `@Ignore` "browse times out" diagnosis was wrong — the timeout was on the
     *read* step, see 3c.1d). The cross-device *byte read* is deliberately **not** asserted here.
   - **3c.1d — cross-device READ proof — LANDED (this slice):** `readSharedFile` works cross-device end
     to end. Two changes closed the two gaps 3c.1c surfaced:
     - **Realm-routed OnDemand fetch (the load-bearing one).** `OnDemandResolutionImpl` was hard-wired to
       the *personal* relay + processor. It now takes an optional `RealmRegistryHolder` and, when the
       entity's key carries a registered shared realm's `"$realmId::"` prefix
       (`RealmRegistry.realmForDataId`), routes both the `fetchEntityBlocks` **and** the
       `processSyncBlockAsync` ingest through *that* realm's runtime — its own relay (JWT scoped to the
       realm `ownerId`, so the fetch hits the realm's content keyspace) and its own processor (whose
       `FileContentManifestApplier` decrypts under the realm keys + writes the realm content store, 3b.2b).
       Personal entities — and any wiring without the holder — keep the personal relay + processor
       unchanged (the holder defaults to `null`). No threat-model change: this is the read side of T-SR-05,
       gated by the realm-scoped JWT the member already holds.
     - **Harness now mirrors production's two-plane content path.** `SystemTestPlatformModule` wires a
       `sharedRealmContentBlobStoreFactory` *and* a personal `ContentBlobStore` (both `RestContentBlobStore`
       against the same in-process test relay), passes `fileDatabase` to `initSyncService`, and sets
       `blockEmitterHolder.fileContentEmitter`. Before this the harness ran *no* content plane at all —
       personal file bytes rode inside the SyncBlock via the legacy `emitPropertyBlock`. **Decision:** the
       legacy whole-file block push is dead in production for file bytes (production always wires
       `fileContentEmitter` → `UploadFileImplementation` always takes `emitFileContent`; a
       `FileContentManifestPayload` is applied **OnDemand-only** — `SyncProcessor.processSyncBlock` no-ops
       it on the regular drain by design), so the harness should mirror production and exercise the content
       plane uniformly. `FileBytesOnDemandSystemTest.joinerReceivesProactivelyPushedBytes_…` (which pinned
       that dead proactive-push path) was **removed** — OnDemand delivery is already covered by its sibling
       test, and there is no proactive file-byte push to assert. Consequence to remember: **shared (and
       personal) file bytes are delivered lazily on open, never proactively pushed.**
     - Proof: `SharedRealmFileReadSystemTest.joinerBrowsesAndReadsSharedFileCrossDevice` browses **and**
       reads `doc.bin`'s bytes cross-device (reliable across repeated runs); full `:system-test-utils` (25),
       `:sync-service-proxy` (137), `:file-service-proxy` (118) green.
   - **3c.2 — `Placement` connector (D3):** the per-member realm-mount records + enumeration powering the
     two sections; "open a mount" = browse that realm via the read ops. Sub-sliced:
     - **3c.2a — the connector primitive + `mountSharedRealm` op — LANDED (this slice):** the
       `Placement(sharedRealmId, parentPath, name)` composite (`:file-service`,
       `org.tenshu.core.file.placement`) — `Syncable(Eager)` + `PlacementDataIdProvider` keyed on the
       **unprefixed** `sharedRealmId` (one mount per realm per member), so it routes to the **personal**
       realm (the connector is the member's own private bookkeeping, not shared content). Plus DTO +
       provider impl (`:file-service-common`), registrations (composite + 2 traits + the op), a client RO,
       and the `FileSystem.mountSharedRealm(sharedRealmId, parentPath, name)` op + proxy impl
       (`MountSharedRealmImplementation` — create + `saveData`). References only a *realm id*, so it is
       content-type-agnostic by construction (§4). Tests: `PlacementSchemaTest` (Syncable trait,
       unprefixed-key routing, getter + serialize→parse wire round-trip per the wire-registration gap) +
       `MountSharedRealmOperationTest` (create / re-mount-overwrites / per-realm keying). (Also corrected
       a stale `ShareFolderIntoRealm` KDoc that still pointed at `placement.Placement` for the
       realm-internal record — it creates `SharedFile`; `Placement` is now the distinct connector.)
       **Zero change to `SharedFile`, the read ops, or any personal-FS path.**
     - **3c.2b — enumeration — LANDED (this slice):** `FileSystem.listPlacements(): TList<Placement>` — the
       member's mounts, the read surface for the Drive *Shared* / *Shared with me* sections. Backed by a flat
       realm-wide array keyed `RelationDataId(TList.ID, FileService.Relations.PLACEMENTS)`, derived by a new
       `PlacementRelationMaintainer` (`:file-service-proxy`) that mirrors `ShareRelationMaintainer` exactly:
       idempotent remove-then-add on every (local or sync-arriving) `Placement` save, `removeFromArray` on
       delete (correct *now* — a `Placement`'s key *is* its full identity, the `sharedRealmId`, unlike the
       `SharedFile` tree case whose deletes wait for Phase 4). The op reads the array directly (a dedicated
       op, same rationale as `listSharedFolder`) — **no `serviceDataRelation` / `fileService.placements`
       chain accessor**, since nothing consumes a second access path. Tests: `ListPlacementsOperationTest`
       (lists all mounts / empty / re-mount keeps one). Grouping into the two sections (by
       `SharedRealmMembership` role) is finalised with the UI at 3d. Whether `ShareFolderIntoRealm` /
       `SharedRealmJoinObserver` *auto-create* a `Placement` (vs. the UI calling `mountSharedRealm`
       explicitly at a user-chosen location) is a 3d UX decision — deferred, since auto-create needs a
       default-mount-point policy the UI owns.
5. **UI — Phase 3d.** Drive *Shared* / *Shared with me* surfaces + the "share this folder" entry.
   **IA decision (maintainer, 2026-06-02): reuse the existing `Shared` tab's two sub-tabs** (A1) —
   *My shares* (outbound: Mode-A links **+** Mode-B shared folders, grouped) + *Shared with me* (inbound).
   **Build order: outbound first** (B1) — the producer + naming live with the sharer, sidestepping the
   joiner-naming gap. Sub-sliced:
   - **3d.1 — outbound producer + listing — LANDED (this slice):** `FilesRepository.shareFolder(folderPath)`
     (bootstrap realm → `shareFolderIntoRealm` → self-`mountSharedRealm` with the folder's name) +
     `sharedFolders()` (`listPlacements` → `SharedFolderModel`). The Mode-B **producer** reuses the Shared
     screen's already-reserved *Share* action (`SelectionViewModel.onShareClicked`, previously a no-op) — so
     no new nav/action surface; the *My shares* tab gains a *Shared folders* group above the link list.
     Tests: `ShareFolderViewModelTest` (producer shares the single selection + clears; multi-select no-ops;
     the tab loads the mounts). `:drive-shared` desktopTest 62 green; `:drive-desktop` compiles.
     **⚠ Finding — the Drive runtime can't yet create a realm:** an integration test against the Drive
     proxy harness failed at `createSharedRealm` (*"no realm id"*) because the Drive app's DI/identity
     bootstrap doesn't wire shared-realm creation (the same gate as prod Control DI — no
     `sharedRealmRelayFactory`/content factory, and no owner-identity bootstrap for realms). So `shareFolder`
     is verified at the VM/repository-interface level (fakes) + the underlying ops are proven by the 3c
     cross-device system tests, but **end-to-end on-device share needs the shared-realm runtime wired into
     Drive/Control DI** — the explicit 3d.2 prerequisite.
   - **3d.2a — Control proxy DI wiring — LANDED (this slice):** the shared-realm runtime is now wired into
     the **real** proxy (`control-shared`'s `PlatformProxyModule`, android + desktop), mirroring
     `SystemTestPlatformModule`: `initSyncService` gets `sharedRealmRelayFactory` +
     `sharedRealmContentBlobStoreFactory` (per-realm relay + content-plane clients, JWT-scoped to the realm
     `ownerId`, against the same relay) and a `RealmRegistryHolder` is registered + handed to
     `OnDemandResolutionImpl` (so a shared-realm read routes to that realm's runtime). **Drive runs no proxy
     of its own — it talks to Control over IPC** — so this is the single native wiring point; the web
     `JsRuntime` (`:tenshu-sdk-js`) is a separate track and stays unwired. Verified: `:control-shared`
     desktop compiles + desktopTest 137 green; the wiring is identical to the harness proven by the 3c
     cross-device system tests (android symmetric, not locally compilable — no SDK in the build env).
     **Not yet closed:** the realm-identity *bootstrap* (a paired/onboarded device has an owner identity, so
     `createSharedRealm` works in the real app — but this isn't covered by an automated test here; on-device
     manual verification is the open step). Personal sync unchanged (the factories only fire for *registered*
     shared realms).
   - **3d.2b — inbound + browse + UX — IN PROGRESS.** Decisions locked (see the header): inbound is
     **membership-driven, mount optional**; the joiner-naming gap is closed by the **wrapper root `SharedFile`**
     (not a field on `SharedRealmMembership`). Sub-sliced:
     - **3d.2b-i — wrapper root `SharedFile` — LANDED:** `ShareFolderIntoRealm` represents the
       shared folder as a single named wrapper node at `ROOT_PARENT`, contents nested under it (see the
       share-op item above). 3c cross-device system test shifted one drill level; `:file-service-proxy` +
       `:system-test-utils` green.
     - **3d.2b-ii — browse screen — LANDED (this PR):** `Destination.SharedBrowse(realmId, parentFileId, title)`
       + `SharedBrowseScreen`/`SharedBrowseViewModel` drill `listSharedFolder` and open `readSharedFile`
       (`FilesRepository.browseSharedFolder` + `readSharedFile` + `SharedFolderEntry`). Consumed first by the
       outbound *Shared folders* rows (now clickable). **Realm-root unwrap:** opening a realm at `ROOT_PARENT`
       drills straight through the lone wrapper node (3d.2b-i) and titles the screen with the wrapper's name —
       so a folder share reads as "the folder", and the joiner gets the name from realm content. File-open shows
       a minimal readout (name + size); a rich in-app preview of shared bytes is a follow-up (the personal-FS
       image preview is path-keyed). `SharedBrowseViewModelTest` (4) + `:drive-shared` desktopTest green;
       `:drive-desktop` compiles.
     - **3d.2b-iii — membership read op — LANDED (this PR):** `IdentityService.getSharedRealmMemberships(): TList<SharedRealmMembership>`
       (op + client RO + proxy impl) backed by a new flat `IdentityService.Relations.SHARED_REALM_MEMBERSHIPS`
       array a `SharedRealmMembershipRelationMaintainer` derives on every (local or sync-arriving) membership
       save — mirrors `DeviceRelationMaintainer` / the file-service `PlacementRelationMaintainer` exactly (plain
       array-key, no `serviceDataRelation`). The op returns full rows (like `getDevices`); `wrappedMasterKey` is
       inert ciphertext to the client (only the proxy-held A13 unwraps it), and callers read only `sharedRealmId`
       + `role`. The inbound primitive. `SharedRealmMembershipRelationMaintainerTest` (3) + `:identity-service-proxy`
       / `-common` suites green. No threat-model change (A12/A13 membership, not A1–A4/A9; relay + sync protocol
       unchanged; the row was already Eager-synced + stored).
     - **3d.2b-iv — inbound *Shared with me* tab + folder-only Share gate — LANDED (this PR):** the inbound tab
       lists `FilesRepository.sharedWithMe()` (`getSharedRealmMemberships` filtered to `role = member`; each
       realm's name read from its wrapper-root `SharedFile`, `null` until that node syncs in → a fallback label),
       each row clickable into the 3d.2b-ii browse screen. Replaces the old "coming soon" placeholder. **Folder-only
       Share gate:** `SelectionViewModel` tracks the selected folder paths and exposes `canShareSelection` (true iff
       a single folder is selected); `onShareClicked` gates on it and the *Share* card greys out otherwise (a single
       file or multi-selection no longer bootstraps an empty realm). `ShareFolderViewModelTest` extended (file no-op,
       gate flow, inbound load); `:drive-shared` desktopTest green; `:drive-desktop` compiles.
     - **3d.2b-v — outbound role-filter — LANDED (this PR); explicit mount DEFERRED (maintainer, 2026-06-02).**
       `FilesRepository.sharedFolders()` now filters `listPlacements` to realms the member **owns** (cross-refs
       `getSharedRealmMemberships`, `role = owner`), so *My shares → Shared folders* means "folders I share **out**".
       A no-op today (only owners hold placements) but it makes the group correct ahead of any joiner mount.
       **The explicit "Add to my Drive" mount is deferred deliberately.** Investigation (this slice) found mounting
       has *no visible payoff* today: a `Placement` only ever surfaces in the *Shared folders* group — the personal
       Files/Folder browse walks `root.files` and never merges placements, and there is no proxy-side merge either.
       So "filing into the personal tree" first needs a **personal-FS placement-rendering** design (where a mounted
       realm appears in the normal browse — a per-folder section, a dedicated *Shared* root, or a true hard-link
       merge — and how it reconciles with the owner's self-mount at the same path). The inbound *Shared with me* tab
       already provides browse access, so mounting adds nothing until that rendering lands. Deferred to a future
       slice with its own design call; **3d.2b's UI is otherwise complete.**
     - **On-device round-trip — manual, open:** verify the full outbound→inbound flow on paired devices (now
       that 3d.2a wires the runtime); can't run in a CI/worktree harness.
6. **Resume / GC — Phase 4:** resumable share op; tombstones that drop a `SharedFile`/unmount a
   `Placement` (also gives the maintainer its `onDataDeleted`); idempotent re-share sidecar.

## 7. Threat model

No new crypto surface from the entity layer: `SharedFile` rows and the `Placement` connector are
ordinary Syncables (`SharedFile` encrypted under the *realm's* keys; `Placement` under the member's
*personal* keys, like any entity). `Placement` carries only `(sharedRealmId, parentPath, name)` — a
realm id its holder already possesses + a personal-FS location — so it leaks nothing new.

The substantive event is **content fan-out (Phase 3b.2)**: writing a `SharedFile`'s bytes into the
realm's content store exposes that file's *content* to the realm's members (by design — sharing **is**
the authorization act). This reuses the existing realm-aware `emitFileContent` (SR-3c-prereq): chunks are
AEAD'd under the **realm's** keys and stored in the realm's own `ContentBlobStore`, so House Rule #1
(relay sees ciphertext only) holds and the personal realm's content is never exposed. Revocation =
dropping the `SharedFile` (+ realm re-key on member removal, §2.3). **Landed as threat-model.md
`T-SR-05`** (§8.9) with the 3b.2a content fan-out — a member who fetched while authorized retains those
plaintext bytes (capability semantics, like `T-S-01`); re-key-on-remove is the forward-secrecy boundary
for *future* content, not already-fetched bytes.

---

## References
- `docs/proposals/sync-multi-realm-runtime.md` §3.2 (F/G) — the friction + reframing that motivated this.
- `docs/proposals/drive-shared-realms.md` — the earlier Mode-B sketch (to reconcile with placement).
- `docs/sync/design.md` §2.7 + [ADR 0017](../adr/0017-file-content-two-plane-channel.md) — content-addressed chunks (the already-decoupled half).
- `docs/file-sharing.md` + [ADR 0018](../adr/0018-file-sharing-mode-a.md) — the existing Mode-A `Share`
  (capability link; distinct from realm placement — D3).
