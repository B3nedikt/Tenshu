# Semantic Permissions — Request Templates and Capability Grants

**Status:** Proposal (2026-11-17); SP-D5 and SP-D6 decided 2026-11-20 (SP-Q1), SP-D7 and SP-D8 decided 2026-11-20 (SP-Q2, SP-Q3). Nothing here is built, and SP-D1–D4 are not decided. It refines
[`tenshu-rei.md`](tenshu-rei.md) §2.2, whose unit is the `(service, operation)` pair, and it is
the prerequisite [`mia-query-layer.md`](mia-query-layer.md) §23 (MQ-D11) names: a composable query
cannot be granted with the permissions that exist today.

**Scope:** what a permission *is*, for two kinds of caller: an app whose requests are fixed, and
a planner whose requests are built at run time. How each is checked at the proxy, and how both
become the sentences a user consents to. **Not** in scope: the Rei scan pipeline (§2.2 there), the
Control permission widget (§3.3 there), grant storage and sync (`AppGrant`, as built).

---

## 0. Where we are

| Piece | As built | ⚠ |
|---|---|---|
| `AppPermission(serviceId, operationId, capabilityClass, gesture, humanLabel)` | `:core` `app/AppManifest.kt`, declared per first-party app in `BundledAppManifestSource` | ⚠⚠ **Not enforced.** Nothing reads it as a gate. Control's own Apps screen says "Tenshu does not currently restrict what an installed app can do". |
| `AppGrant(appId, serviceId, operationId, grant)` | realm-wide synced rows, tri-state | No scope field: Rei's `Scoped` class has nowhere to store its scope. |
| Scope as an injected `.search { }` | decided (Rei Q13), not built | — |
| Dynamic chains | decided (Rei Q14): check the **resolved** op at dispatch | The install-time manifest cannot enumerate what a chain can do. |
| Mia's grants | `MiaSource` → `mia.grant.<source>` booleans (one per source; the calendar write tier was removed 2026-11-19) | Per service only. |
| Mia's checker | `MiaChainValidator.Policy`: op and service allowlists, a field-use table (`FieldUse` PROJECT/FILTER/SORT/TRAVERSE), bounds | A hand-maintained constant. Predicates, sort keys and traversals are checked since 2026-11-17 (§3.2). |
| Caller identity on desktop | `UnixSocketServer` names a peer by the app id its code signature attests (`desktop-ipc-client-identity.md` P2; MSIX package identity on Windows); an unattested peer gets a unique `unattested-…` id | ⚠ Corrected 2026-11-20: this row said the id was hardcoded, and SP-Q2 waited on it. It no longer blocks anything. |

So the platform has a permission *vocabulary* and no permission *boundary*. Whatever this doc
settles has to be the shape the boundary is built in.

---

## 1. The decision

### SP-D1 — A permission is stated over the callstack, not over the operation

The proxy executes callstacks (`List<Data>`), not bare operations, and a callstack is already data
the proxy can walk: each step carries its `OperationSchema`, predicates and selectors ride as
nested `TList<Data>` (`MiaChainValidator`'s KDoc). `(service, operation)` cannot see most of what a
chain does: which relation it walks, which field it filters on, what it returns. Rei Q14 accepted
that as a residual. This proposal removes it by making the callstack the unit, in one of two forms:

| | **Request template** (SP-D2) | **Capability grant** (SP-D3) |
|---|---|---|
| For | apps whose requests are fixed: Drive, Photos, Fit, … | planners whose requests are built at run time: Mia; later any query-capable app |
| Is | the exact callstack, with placeholders where values vary | a set of capabilities over the schema graph |
| Checked by | structural match against a declared template | every step checked against the set |
| Install-time view | complete: every request the app can make | complete: every service, composite and field it can reach, and how |

⭐ **One walker, two matchers.** Both are checked by walking the same callstack at proxy dispatch,
the chokepoint Rei Q14 already names (where exactly: SP-D7, the executor's entry). ⚠ A template is **not** converted into a capability set:
that would forget the structure and allow the template's parts to be recombined.

### SP-D2 — Request templates: the app's exact requests, with placeholders

Drive's name search, as it is issued today:

```kotlin
defaultFileSystem.root.files
    .traverse()
    .filter<File> { name.contains(?text, ignoreCase = true) }
    .take(?n)                                   // ?n : Int, 1..200
    .select { fields(File.Field.PATH); relations(File.CreatedAt) }
    .getAll()
```

The template is that callstack with `?text` and `?n` as typed placeholders.

- **A placeholder stands only in a value position**: a predicate's argument, a `take` bound, an op
  parameter. ⚠⚠ Never in a field, relation, op or projection position. A template whose *field*
  varies ("filter on `?field`") can read anything, and is a capability grant wearing a template's
  name. The matcher refuses such a template at registration, not at dispatch.
- **A placeholder carries its type and may carry a constraint**: a range for a bound, a closed set
  for an enum. The bound constraint is what keeps a template from becoming an unbounded read.
- **Matching is unification.** Same steps, same op ids in the same order, same predicate tree and
  same projection, with each placeholder bound to a value that satisfies it. Anything else is
  refused.
- **Capability class and gesture move onto the template** (Rei §2.2's traits): the same op can be
  `Ambient` in one template and `Picker` in another. `(service, operation)` survives as the UI's
  *grouping* of templates, not as the unit.
- **A scope grant is an injected predicate** (Rei Q13): the proxy adds `.filter { path startsWith
  "/Photos" }` into the matched template before execution. Templates make Q13's
  scope-completeness invariant checkable per template rather than per op.

How templates are produced is SP-D5, and what is hashed and signed is SP-D6.

### SP-D3 — Capability grants: what a planner may reach, and how it may use it

A capability grant is a set over the schema graph:

| Capability | Granularity | Mia today |
|---|---|---|
| **reach** a service or service relation | `ServiceTypeReference` / relation id | `MiaSource.chainReferenceId` |
| **invoke** an operation | op id; a write only behind MQ-D7's confirmation (MQ-D6's write tier was removed 2026-11-19) | `allowedOperationIds` |
| **project** a field, property or relation | id, per composite | `fieldUses` PROJECT |
| **filter** on a field or property | id, per composite | `fieldUses` FILTER |
| **sort** by a field or property | id, per composite | `fieldUses` SORT |
| **traverse** a relation | id, per composite | `fieldUses` TRAVERSE |
| **bounds** | rows, steps, nesting depth, a bounding param per list op | `maxRows`, `maxSteps`, `maxNestingDepth`, `boundingOperations` |
| **row scope** (optional) | an injected predicate, as in SP-D2 | — |

- ⚠⚠ **Filter is a read.** A predicate over a field leaks the field through which rows come back:
  `contains("a")`, `contains("ab")`, … reconstructs a value that was never projected. So *may
  filter on X* grants *may learn X*. The consent sentence must say so, and a field that may not be
  learned (content bytes, a key) may neither be projected **nor** filtered.
- ⚠ Sort leaks order, which is weaker and still a read: sorting by a secret field and taking the
  first row is a comparison oracle.
- ⚠ **Derived properties count.** `dynamicProperty` and deferred properties (`deferred = true`; `File.Preview`,
  `File.Thumbnail`) are properties here and fail closed like the rest. `RecursiveListWalk`
  dropping the projection (`ios-drive-parity.md` §24.5) is why "not projected" must also mean
  "not materialised".
- **The grant is derived, not hand-written.** Mia's grant is computed from the user's switches
  (`mia.grant.*`, one per source) and from a per-source capability table that ships with the
  source's lowering. It is not a constant in the validator.
- ⚠ **Capability ⊆ grant ⊆ user.** A planner acting for the user can never be granted more than
  the user's own access, and Mia's grants are a *delegation* (Rei §4's open problem). This doc
  states the bound and leaves delegation chains to that proposal.

### SP-D4 — Both kinds render to sentences derived from the DSL

A user consents to sentences, and today those are hand-written (`AppPermission.humanLabel`). Both
kinds of permission should render from the schema instead, so the consent text cannot drift from
what is enforced. This is the same rule MQ-D11 applies to Mia's explanation line: code writes it,
never a generator.

- Every schema element the walker can meet carries a **noun or verb phrase**: services ("your
  files"), relations ("the files in a folder"), fields ("its name"), ops ("search … by …").
- A **template** renders by walking it: *"Search your files by name and see each file's path and
  date, up to 200 at a time."*
- A **capability grant** renders grouped by composite and use: *"Read your files' names, paths
  and dates, and search by name. Never their contents."* A filter capability renders as a read
  (SP-D3).
- `humanLabel` becomes an **override** for the cases a derived sentence reads badly, never the
  only source. A test pins that every template and every shipped grant renders without one.

⚠ These phrases are user-facing strings and go through the i18n pipeline like any other. A German
consent sentence is composed from German phrases, not translated after composition.

### SP-D5 — Templates are written once, in one Kotlin file, and a Gradle plugin builds the manifest

**Decided 2026-11-20 (SP-Q1).** An app declares its requests as named templates in the chain DSL,
in one `PermissionSet`, and **calls the template** wherever it issues the request. A template's
lambda parameters are its placeholders:

```kotlin
object DrivePermissions : PermissionSet {
    val searchByName = template { text: String, n: Int /* 1..200 */ ->
        defaultFileSystem.root.files.traverse()
            .filter<File> { name.contains(text, ignoreCase = true) }
            .take(n)
            .select { fields(File.Field.PATH); relations(File.CreatedAt) }
            .getAll()
    }
}
// at the call site: DrivePermissions.searchByName(query, 50)
```

- ⭐ **Declaration and use are the same code**, so the manifest cannot drift from what the app
  sends. Nothing is scanned.
- **The plugin evaluates the templates; it does not parse them.** It runs each template with a
  marker value per placeholder and records the callstack that comes out, through a recording
  transport. That callstack, with the markers as typed placeholders, is the template.
- ⚠⚠ **A marker outside a value position fails the build.** A marker that reaches a field, a
  relation, an op id or a projection is SP-D2's forbidden template, and generation is where it is
  caught: mechanically, not by review.
- ⚠ **A template body must not branch on its parameters.** Evaluation records the one branch the
  markers take. The other branch's request then matches no template and is refused at dispatch, so
  the failure is closed, but it is a failure. Two shapes are two templates.
- **The `PermissionSet` lives in its own small module** that depends only on the `-common` modules
  of the services it reaches, so the plugin can compile and run it on the JVM without building the
  app. The app depends on that module like any other.
- **The plugin merges in what only the build knows**: the app id, the version and the signing
  material, from a `tenshu { … }` block. A third-party integration is one plugin, one file and one
  block.
- **Completeness is checked, not trusted.** Under the repository tests, a report-only recording
  proxy flags any request the app sends that matches no template. That is the check SP-Q1 asked
  for, and it runs in the gate rather than producing the manifest.
- ⚠ **Rejected:** a KSP or compiler-plugin scan as the source. KSP sees source but cannot evaluate
  DSL-building code; an IR plugin could, but it breaks on Kotlin bumps and still leaves third
  parties needing an artifact scanner. A test recording as the source makes the manifest only as
  complete as the tests.

⭐⚠ **Enforcement makes the manifest the boundary, and that amends Rei §2.2.** Once the proxy
refuses a request that matches no template (SP-D1's walker), an app cannot exceed its manifest
whatever its code does. Under-declaring fails on the developer's first run; only over-declaring is
possible, and that is what an evaluator judges. Rei §2.2's static scan existed because enforcement
was per `(service, operation)` and chains were invisible to it. Under SP-D5 the scan is an
evaluator's *aid* ("does the binary reach the platform outside the proxy?"), not the source of the
permission set. ⚠ Until the walker enforces, the manifest is a declaration and nothing more,
exactly as `AppPermission` is today (§3.1).

⚠ **Open (SP-Q4):** an authoring route for an app with no Kotlin. A Swift or TypeScript app can call
templates exported through the SDK, but it cannot write new ones without a Kotlin module.

### SP-D6 — The signed artifact is the manifest's canonical binary encoding

**Decided 2026-11-20 (SP-Q1).** Three forms, kept apart:

| Form | What | Hashed? |
|---|---|---|
| **Authoring** | whatever the developer writes: today the SP-D5 Kotlin file | no |
| **Canonical** | the manifest as a schema-defined `AppManifest` composite, encoded with `BinarySerializer` | **yes**: this is what is hashed, signed and anchored |
| **Rendered** | SP-D4's sentences, or a JSON dump for a reviewer | no |

- ⭐ **One encoding.** A template already *is* a callstack, and a callstack already has a binary
  form: the one the proxy decodes at dispatch. So what is signed and what is enforced are decoded
  by the same code, and cannot diverge.
- **The Rei record holds the hash and the developer's signature**, not the bytes: the whitepaper's
  RPL record is *a hash identifying an artifact, and a signature*. The bytes ship with the app and
  the publication.
- ⚠⚠ **The encoding is positional and tied to the schema version.** A reshape (House Rule 9)
  changes the bytes of a manifest whose meaning did not change. So the hashed bytes open with a
  **format header**: `TENSHU_STORAGE_FORMAT_VERSION` and the version of **every service the
  manifest references**, since a template spans services and each versions alone. A verifier
  decodes under the signed versions (through the `SchemaMigration` chain), and never re-encodes
  under the current schema to compare hashes.
- ⭐ **Audited 2026-11-20: deterministic, with the rules below.** What the encoding is:
  - **Five value kinds reach the wire**: reference, bytes, string, integer, boolean. There are no
    floats, so there is no float formatting to differ.
  - **An id is a 16-byte MD5 name-based UUID** (`idToUuid`), never the id string; an integer is 8
    bytes big-endian; a string is its UTF-8 bytes behind an 8-byte length. All three are pure Kotlin
    in `commonMain`, the same on every target.
  - **Order is the data's order.** Composite fields go in schema order and lists in list order, and
    nothing in the encoder iterates a set or a hash map.
- **The rules a manifest model must keep**, since the encoder cannot:
  - **No sets, and no maps built from hashed collections.** A list in a defined order instead.
  - ⚠ **Absent and empty are one encoding.** An absent variable-length optional field and an empty
    one both encode as a zero length (`OptionalNullEqualsEmptyContractTest` pins this as a
    contract). So the model must never give them different meanings.
  - ⚠ **Strings are not normalised.** "é" composed and decomposed are different bytes. Generation
    must produce the strings, never a user's typing of them.
  - **The bytes cannot be decoded without the schema**: ids are hashed one way. Hence the format
    header (above).
- **Pinned on every target.** `CanonicalEncodingTest` (`:core-common`, run on the JVM and the iOS
  simulator) and `CanonicalEncodingJsTest` (`:tenshu-sdk-js`, since `:core-common` compiles no JS
  tests) assert the same hex vectors. A structural test could not show this: it computes both sides
  with the same `idToUuid`.
- ⚠⚠ **The audit found a race, fixed.** One `BinarySerializer` serves every concurrent reply, and
  `LinearSerializer` kept each call's value tokenizer in a field. A reply written while another
  started finished with the other's tokenizer. On Android that **delivered one app's reply bytes to
  another app's process**. Each call now runs on its own instance (`SharedSerializerConcurrencyTest`;
  threat model T-SP-03).
- ⚠ **MD5 ids are adequate here, and colliding ones are refused.** Hitting a *first-party* id would
  need a second preimage, which MD5 does not admit in practice. A chosen-prefix collision needs
  crafted bytes in **both** ids, so it can only blur two of a developer's own ops.
  `SchemaRegistry` now refuses two ids on one set of bytes instead of resolving to the last one
  (threat model T-SP-02, corrected). Owed with the third-party registration path: no third-party
  id under `tenshu/`. ⚠ Changing the id scheme would touch every stored and synced value, so it
  stays.
- ⚠⚠ **The manifest's own digest is SHA-256** (`sha256` in `:core-common`, on every target), never the
  id scheme's MD5. It is what the Rei record anchors and what a signature covers, so it needs real
  collision resistance; the ids only need to be distinct in a registry that refuses clashes.
- ⚠ **Rejected:** deterministic CBOR or canonical JSON (a second encoding of callstacks to keep in
  step with the first, which is where the signed and the enforced drift), a text IDL (hashing text
  needs whitespace and normalisation rules, so a canonical form anyway), and JSON or YAML as
  written (not canonical). ⚠ Revisit only if verifiers outside the Tenshu SDK must check
  manifests.

### SP-D7 — A `Principal` set at the transport; a `RequestGuard` at the executor's entry

**Decided 2026-11-20 (SP-Q2).** Two facts shaped it:
- **The gateway is not the only way in.** `ProxyGateway.call` sees every request that crosses a
  process (Android Binder, desktop sockets, iOS EPC). iOS route-1 (`InProcessProxy`), the web
  runtime (`JsRuntime`) and **every planner inside the proxy** reach `LocalTaskExecutor` without
  it. Mia's lowered chains are the third kind, which is why `AskMiaImplementation` calls
  `MiaChainValidator` by hand today.
- **The executor is not only for outside requests.** The proxy's own work runs through it too, so
  a check on every executor call would check the proxy against the app's templates.

The decision separates who a request acts for from the mechanism that checks it:

- **`Principal` is identity only** (as in JAAS, Kerberos or IAM): `App(appId)`,
  `Planner(id, onBehalfOf)` or `Proxy`. It rides on `ExecutionContext`. ⚠ It carries no
  permissions: the guard resolves the policy from it on every request, so a grant the user changes
  mid-session is read as it is now, never as a copy frozen into a context.
- **The transport sets it from the attested id.** Android: the Binder calling package. Desktop:
  the attested app id. iOS: the EPC client id its pairing established (`DefaultEpcPairingHost`;
  ⚠ what that id proves about the app is unchecked here). An `unattested-…` peer is an `App` with no
  templates, so every request it sends is refused. ⚠ iOS route-1 sets `Proxy`: first-party apps
  there are realm co-owners with no inter-app boundary (threat model A4, Decision C).
- **A `RequestGuard` runs once, where the executor accepts a request**, for any principal that is
  not `Proxy`. Its shape is `(principal, callstack) → Refused | callstack′`: it checks, and it
  rewrites, because a scope grant is a predicate it injects (Rei Q13). An `App` is matched against
  its templates (SP-D2); a `Planner` is checked against its grant (SP-D3, SP-D8).
- ⚠⚠ **Check the whole request, then evaluate it; never check during evaluation.** Checking step
  by step means a step refused late runs after the earlier ones' side effects (a save, an
  on-demand content pull, network traffic). And lazy evaluation skips parts: a filter over an empty
  list is never evaluated, so a forbidden one would pass or fail depending on the data, and a
  reactive read that passed once could start failing mid-stream. Template matching needs the whole
  shape in any case. The walk is over the request (a few `Data` steps), not the rows, so the cost
  is negligible next to evaluation.
- ⚠⚠ **An operation's own code runs as `Proxy`, never as its caller.** The request's nested
  predicates and selectors are checked inside the caller's walk; a request the caller supplied is
  never re-run later under `Proxy`, which would launder it.
- **Planners declare themselves.** Mia runs a lowered chain as `Planner(mia, onBehalfOf = user)`.
  The guard checks it, the hand-written `MiaChainValidator.validate` calls go, and
  `MiaChainValidator` becomes the capability matcher the guard uses. ⚠ `onBehalfOf` is OAuth token
  exchange's *subject* to the planner's *actor* (RFC 8693): the delegation Rei §4 leaves open.

### SP-D8 — A grant is the service's ceiling ∩ the planner's needs ∩ the user's switches

**Decided 2026-11-20 (SP-Q3).** The doc's lean. Today `QueryPlanLowering.POLICY` is one
hand-written table inside Mia; nothing but review stops it listing a field the service considers
secret, and a second planner would restate it.

- **The service declares a ceiling** in the schema DSL, per field, relation and derived property:
  which uses (project, filter, sort, traverse) may ever be granted. ⚠ Absent means **not
  grantable**, and "never" is explicit for what may not be learned (content bytes, keys), so such a
  field can be neither projected nor filtered (SP-D3).
- **The planner declares its needs** beside its lowering, as Mia's table is today.
- **The enforced grant is computed**: needs ∩ ceiling ∩ the user's switches (`mia.grant.*`). It is
  never a constant (SP-D3's "derived, not hand-written").
- ⚠ **A need above the ceiling fails a test, not a request.** Every planner's needs are checked
  against the ceilings at build time, so the gap shows when it is written.
- ⚠⚠ **The ceiling is Kotlin-side DSL metadata, never a wire field.** `tenshu/core/schema/field` is
  a pinned wire composite (`id`, `type`, `isOptional`); a field added there is a `:core` reshape
  and a format bump (House Rule 9). The precedent is `deferred = true`, a declaration parameter on
  `dynamicPropertyRelation` that never reaches that composite. The guard runs in the proxy, so
  only the proxy's own schema needs it.
- ⭐ **One DSL change for three consumers.** The per-element declarations that carry the ceiling
  are where SP-D4's phrases ("your files", "its name") and `mia-query-layer.md` CL-Q4's semantic
  param types belong.

---

## 2. How the two meet in Mia

Mia's own *requests* are fixed: `askMia` and `confirmMiaAction`. Under SP-D2, the Mia app holds two
templates. What varies is the callstack Mia's proxy-side planner builds **on the user's behalf**,
and that is what the capability grant covers. Hence two layers, checked at two points:

| Layer | Who | Kind | Checked |
|---|---|---|---|
| Mia app → Mia service | the app | templates: `askMia(?utterance, ?now, ?zone)`, `confirmMiaAction(?token)` | at dispatch of the app's request |
| Mia planner → realm, **reads** | Mia's planner, for the user | capability grant from `mia.grant.*` | at dispatch of each lowered chain (today: `MiaChainValidator`) |
| Mia planner → realm, **writes** | Mia, for the user, after a confirmation | a request template per command: the command's frame (`mia-query-layer.md` §25.3) | at dispatch of the confirmed op call (today: unchecked) |

⚠ Mia's actions stay outside the grant. `CreateEvent`, `ControlDevice` and `RunRoutine` run only
through MQ-D7's confirmation, and a write capability (MQ-D6's tier) is what the confirmation
consumes. It is never a standing permission for the planner to write.

⭐ **So Mia uses both kinds, one per path.** A read chain is composed and varies, so it is checked
against a capability grant. A write is one fixed op call whose values vary, so it is a request
template: the command's frame, with the params the utterance fills as placeholders.
- ⚠ The write template is checked at **confirm-time dispatch**, not at planning. The write is built
  from values already resolved and shown, so it never appears in a read callstack.
- ⚠ As built, no check sees Mia's writes. `ConfirmMiaActionImplementation` hand-assembles each op
  call. That is safe only because the three commands are fixed in code. It stops being safe the day
  a command's values come from anything but the grammar.
- The Mia app's own templates grow by one when the clarify turn lands:
  `replyToMia(?token, ?utterance, ?now, ?zone)` (`mia-query-layer.md` §25.6).

---

## 3. Findings this proposal rests on

### 3.1 Nothing enforces `AppPermission`

Verified 2026-09-02 on a device: Photos dispatched `createFolder` and `moveFile` while declaring
only `getPreview`, and both went through. Control's Apps screen states it. So SP-D2 is not a
tightening of an existing boundary. It is the first one, and rolling it out owes a **report-only
phase** (log every unmatched request, refuse nothing) before refusal, or first-party apps break on
the first template the recording missed.

### 3.2 The Mia validator checked projections, not predicates or traversals (closed 2026-11-17)

`MiaChainValidator` checked op ids, service references, `select` projections and bounds/depth. A
predicate's field, a `sortBy` key and a traversed relation were never compared with anything, so a
chain that filtered on a field outside the projection allowlist passed as long as it projected only
allowlisted ids. Not exploitable while the grammar is the only generator, but it had to close
before any generated chain or `Query` executes (`mia-query-layer.md` §23.4 steps 2–4).

**Closed** by the first built piece of SP-D3: `Policy.fieldUses` maps each field, property and
relation id to its permitted `FieldUse`s, and the walk takes a reference's use from its position.
The probe that preceded it found two more holes of the same kind, closed with it:

- ⚠⚠ **The op allowlist read top-level steps only.** A predicate is a nested callstack whose ops
  (`contains`, `inRange`, `equalTo`, `oneOf`) the proxy evaluates. They were on the allowlist and
  nothing checked them. Any op inside a predicate now meets the allowlist like a top-level one.
- ⚠⚠ **Some chains carry no service reference.** `photosLike` and `queryOccurrences` open with an
  op on the service itself, so the service check never ran for them, and revoking CALENDAR changed
  nothing the validator could see. Each source-owned op is now bound to its source and removed
  from the policy with the grant (`QueryPlanLowering.OPERATION_SOURCES`). ⚠ A new source whose
  chain opens with an op owes an entry there.

What stays open is SP-D3's *derivation*: the table is still a hand-written constant in
`QueryPlanLowering`, not computed from the grants and per-source tables (SP-Q3).

---

## 4. Owed before deciding

- ~~**SP-Q1 — template production.**~~ **Decided 2026-11-20: SP-D5 and SP-D6.** Still owed from
  it: SP-Q4, and the `tenshu/` namespace rule for third-party ids (SP-D6, threat model
  T-SP-02). The encoding audit is
  done (SP-D6).
- ~~**SP-Q2 — where the walker lives.**~~ **Decided 2026-11-20: SP-D7.** Not at the gateway alone:
  a `Principal` set by the transport, and a `RequestGuard` at the executor's entry. The desktop
  `clientId` it waited on was already fixed (§0).
- ~~**SP-Q3 — capability tables per source.**~~ **Decided 2026-11-20: SP-D8.** The service's ceiling
  ∩ the planner's needs ∩ the user's switches.
- **Threat model.** Amended 2026-11-20 as §8.26 (T-SP-01–03), recording the boundary as decided
  and **not in force**. T-SP-01 is tightened with the first enforcing code (House Rule 2).
