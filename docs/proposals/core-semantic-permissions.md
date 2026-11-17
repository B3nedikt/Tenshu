# Semantic Permissions — Request Templates and Capability Grants

**Status:** Proposal (2026-11-17). Nothing here is built and no SP decision is taken. It refines
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
| Mia's grants | `MiaSource` → `mia.grant.<source>` booleans (+ a write tier for calendar) | Per service only. |
| Mia's checker | `MiaChainValidator.Policy`: op and service allowlists, a field-use table (`FieldUse` PROJECT/FILTER/SORT/TRAVERSE), bounds | A hand-maintained constant. Predicates, sort keys and traversals are checked since 2026-11-17 (§3.2). |
| Caller identity on desktop | `UnixSocketServer` hardcodes `clientId` | ⚠ No permission can be attributed to an app on desktop until this is fixed (`mia-query-layer.md` MQ-D10). |

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
the chokepoint Rei Q14 already names. ⚠ A template is **not** converted into a capability set:
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

⚠ **How templates are produced is open (SP-Q1).** A source-time scan cannot evaluate DSL-building
Kotlin. A realistic first-party route is a **recording transport** under each app's repository
tests that captures the callstacks the app actually issues, pinned exactly, as
`BundledAppTablesTest` pins the declared ops today.

### SP-D3 — Capability grants: what a planner may reach, and how it may use it

A capability grant is a set over the schema graph:

| Capability | Granularity | Mia today |
|---|---|---|
| **reach** a service or service relation | `ServiceTypeReference` / relation id | `MiaSource.chainReferenceId` |
| **invoke** an operation | op id, read class unless a write tier is granted (MQ-D6) | `allowedOperationIds` |
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
- ⚠ **Derived properties count.** `dynamicProperty` and `@defer` properties (`File.Preview`,
  `File.Thumbnail`) are properties here and fail closed like the rest. `RecursiveListWalk`
  dropping the projection (`ios-drive-parity.md` §24.5) is why "not projected" must also mean
  "not materialised".
- **The grant is derived, not hand-written.** Mia's grant is computed from the user's switches
  (`mia.grant.*`, the MQ-D6 write tier) and from a per-source capability table that ships with the
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

- **SP-Q1 — template production.** A recording transport under repository tests, a KSP pass over a
  restricted DSL subset, or both. Measure on Drive: does the recording cover every request the app
  issues on a device (a report-only proxy run tells)?
- **SP-Q2 — where the walker lives.** In `:core-proxy` at dispatch, so every caller meets it, with
  `MiaChainValidator` becoming its capability matcher. Owes the desktop `clientId` fix first.
- **SP-Q3 — capability tables per source.** Who owns "what may Mia do with `File`": the source's
  lowering (closest to the chain) or the service's schema (closest to the data)? Lean: the
  service's schema declares what is *grantable*, and Mia's lowering declares what it *needs*. The
  grant is the intersection.
- **Threat model.** SP-D1–D3 change what A4's per-app boundary means. The amendment lands with the
  first enforcing code (House Rule 2), not with this proposal.
