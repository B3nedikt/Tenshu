# Mia Query Layer — Turning an Utterance into Grounded Realm Data

**Status:** v0.8 — **Mia on the web runs the same planner in the tab, read-only** (§20). v0.7 — **Mia on iPhone is real and read-only** (§19): a route-1 runtime in place of the mock, and a proxy that offers no action there. ⚠⚠ No question has been asked on a real device yet (§19.4).
**v0.6** — **§15.1's 200-series cap is closed** (§17). `queryOccurrences` moves the scan inside `:calendar-service` and bounds **occurrences in the window** instead of **series scanned** — the one quantity there was no safe order to truncate. ⚠⚠ Saturation is now a fact rather than a guess. ⚠ `CalendarService` → v5.
**v0.5 — MQ-1 … MQ-4 SHIPPED** 2026-08-11/14 (as-built: §7–§11, §13), plus Track A
slice A1 (§12), **`QueryCalendar` (§15)** — which completes MQ-D4a and every named read intent —
and **MQ-5a (§16)**, the model-as-planner *seam*.
⚠⚠ **Read §14 first: the track was click-through-verified on 2026-08-15 and six defects fell out,
five of which no test could see.** Remaining: the rest of **MQ-5** — answer phrasing and
model→chain — all of it gated on a model existing at all (§16.8).
Amended 2026-08-05 (v0.2, the calendar intents). Fills the gap under
[`home-and-mia-apps.md`](home-and-mia-apps.md) §4: the M-series shipped Mia's *information
architecture* (Chat / History / Settings + widget) and nothing beneath it.
`ConversationRepository` is one interface with a keyword-routed `MockConversationRepository`
behind it, its KDoc pointing at "the real backend is the Voice App Service" — which is
itself undesigned. Mia's modules do not depend on `core-client` at all, so today the app
cannot read a single byte of realm data.

⭐ **v0.2 (2026-08-05) adds the calendar intents**, from [`calendar-app.md`](calendar-app.md) §6:
a `QueryCalendar` read and — more interestingly — a `CreateEvent` **write**, since recording an
event by utterance turns out to be a primary calendar use case. New **MQ-D4a** carries it, and
it amends three existing decisions rather than sitting beside them: **MQ-D6** grows a *write*
tier (a grant needs a verb, not just a noun), **MQ-D1**'s "Mia cannot hallucinate" is recorded as
a *read* property that a write intent leaves, and **MQ-D7**'s cell must confirm the **resolved**
row rather than the utterance. ⚠ Both intents are gated on `:calendar-service` CA-1; neither
track blocks the other.

**Scope:** how a typed sentence becomes an answer grounded in the user's own data; the
service that does it; the permission model; and the path to an optional generative layer.
**Not** in scope: audio capture and STT ([`audio-and-voice.md`](audio-and-voice.md)), the
inference runtime and model distribution
([`inference-service.md`](inference-service.md)), the routines *engine* (its own doc, not
yet written), and Skills (post-Rei, D3).

---

## 1. The shape in one paragraph

An utterance becomes a **typed, inspectable `QueryPlan`** — an intent and its slots — which
**lowers to an ordinary chain-DSL callstack**, passes a validator, and returns a typed
`Answer` rendered by the rich cells M3 already built (`PhotoGrid`, `PlaceCard`,
`DeviceAck`). ⭐ Mia does not get a query language of its own: it emits the same
`List<Data>` callstack every app's read path already uses, so it inherits the read-path
optimisations and is constrained by one validator that a future model would meet unchanged. **v1 has no LLM**: the planner is a deterministic intent grammar, because the
data Mia answers over (Drive files, Timeline places and moments, Fit workouts, Home
devices) is *already structured* and the hard part is retrieval, not generation. The layer
lives **proxy-side**, in a `:mia-service` module set, because only the proxy can read
across every service. Mia's access is **capability-scoped and revocable per service**
from day one — ⚠ **per (service, `read` | `read + write`)** since MQ-D4a, with `read` the
default — mutating plans require **explicit confirmation**, and conversation history is
**device-local by default**.

---

## 2. Decisions

### MQ-D1 — Retrieval, not generation: v1 has no LLM

The instinct is that an assistant needs a language model. For this product it does not, and
starting there would be the expensive mistake.

Everything Mia is supposed to answer is *already structured realm data*: Drive's file index
(name, type, dates, and soon place and content labels), Timeline's `Moment`/`Place` spine,
Fit's workouts and rollups, Home's device inventory and state. The two examples D1 uses to
justify Mia's existence — *"find the photo I saved last summer"*, *"where was that café
from my Timeline?"* — are **queries**, not conversations. What they need is slot extraction
and a good projection, not a transformer.

- ⭐ **Corollary worth stating as a product principle: Mia's answers are citations, never
  prose.** An assistant that can only *show you your own data* cannot hallucinate. Every
  answer is a set of real rows the user can tap through to the owning app. That is a
  sharper claim than "we run the model locally", and it holds without any model at all.
  - ⚠⚠ **Amended 2026-08-05: this is a *read* property, and `CreateEvent` (MQ-D4a) is the
    first intent outside it.** A misheard slot in a read intent produces a wrong *answer*,
    which the user sees and ignores. A misheard slot in a write intent produces a wrong
    *row* — which persists, syncs to every device, and fires a reminder at 07:00 three weeks
    later, long after the utterance is forgotten. **MQ-D7's confirmation cell is therefore
    not a courtesy for write intents, it is the whole control**, and it may not be skipped
    for "obvious" ones. ⭐ Mitigating, and the reason this is a good *first* write intent
    rather than an alarming one: an event is **reversible**. A wrong one is annoying, not
    destructive — unlike deleting a file or unlocking a door — which is exactly why creating
    events, and not deleting them, is where the write path should be exercised first.
- Generation, if it ever lands, is a **layer over the same retrieval** (MQ-5), not a
  replacement for it — and it is optional, on-device, and behind `InferenceService`.

### MQ-D2 — The query layer is proxy-side: `:mia-service`

A module set `:mia-service` + `-common`/`-client`/`-proxy`, symmetric with the others.

- **Why the proxy:** the planner must read across *every* service. App-side, it would need
  each app's client stack assembled inside Mia, plus IPC fan-out per query. Proxy-side it
  is ordinary in-process reads against services that are already registered there.
- **Why one service rather than logic in the app:** three surfaces converge on it — Mia's
  own chat, Home's embedded "Ask Mia" (which already shares the same `ChatScreen` via
  `Destination.MiaChat`), and later a speaker's voice turn arriving at the hub. Authoring
  the planner once is the same argument that made the conversation surface shared in M3.
- ⚠ This is the slice that gives **Mia its first Tenshu client stack** — it has none today.
  The shape is Home's: a minimal `miaClientModule` (core client + `:mia-service-client`
  only) in commonMain, plus per-platform halves (Binder on Android, UDS on desktop,
  in-process on iOS route-1). Home's `homeClientModule` / `homePlatformClientModule` pair
  is the template, including the Koin module-order override rule.
- ⚠ Mia's shells must join the Android smoke gate when they gain a client stack, as Home
  did in HD-3 — a client that cannot bind Control fails silently and looks like an empty
  result.

### MQ-D3 — The plan is a typed, inspectable artifact that **lowers to a chain**

```
Utterance --planner--> QueryPlan --lowering--> callstack --validator--> execute --> Answer --> [rich cells]
```

`QueryPlan` = intent + resolved slots. It is deliberately **not** a query language: it
*compiles* to the chain DSL every app already reads through, and the chain is what runs.

⭐⭐ **The fact that makes this work: a chain is already data, not code.**
`RequestChain.getCallstack(): List<Data>` — each step an `OperationDto(params, schema)`,
with nested predicates riding as a `TList<Data>` (`FilterRo.addNestedArray`). So building a
query means constructing a **typed composite tree**, which can be walked, validated, costed
and rendered *before* anything executes. That is a categorically better position than a
system that would have to evaluate generated code, and it is why MQ-D10's validator is
cheap rather than heroic.

⚠ The earlier framing of this doc had `QueryPlan` naming operations directly, which
amounted to inventing a second, weaker query language beside one that already exists —
and would have forfeited the read-path investment (ordered-window fusion, deferred
properties, projection pushdown) that any *real* chain gets for free. Corrected here: the
plan is an **intent layer above** the DSL, not an alternative to it.

⭐ Inspectability is load-bearing in three separate ways, which is why it is a decision and
not an implementation detail:

1. **Testability.** The planner is pure: utterance in, plan out. Hundreds of cases can be
   asserted with zero ML, zero IO, and zero flakiness — the same reason `MovementSegmenter`
   and `classifyMode` were made pure in the movement-capture track.
2. **Explainability.** The user sees *why* Mia answered that: "searched Drive for images,
   June–August 2025". When retrieval is wrong, a wrong plan is legible; a wrong embedding
   is not.
3. **Swappability.** It is exactly the seam where a language model can later be dropped in
   **as the planner** (utterance → plan) without touching execution, permissions, or
   rendering — and where its output is still constrained to operations the user has granted.
   ⭐ A model that can only emit a `QueryPlan` cannot invent data and cannot exceed its
   permissions, which is a far better containment story than prompting a model that holds
   the data.

⭐ **What lowering buys, concretely:** a generated chain inherits every read-path
optimisation automatically — `sortBy(key).take(n)` fuses through
`core-ordered-window-fusion.md` F1 regardless of who authored it, deferred properties obey
`@defer`, and projections narrow the walk. None of that would apply to a bespoke executor.

### MQ-D4 — The v1 planner is a deterministic intent grammar

⚖️ **Target and mapper are orthogonal, and it is worth separating them explicitly** — the
two questions get conflated constantly. *What the planner emits* is settled by MQ-D3: a
chain, always. *What maps an utterance onto it* is a separate choice, and v1's answer is a
grammar **because v1 has no model** (MQ-D1), not because a closed intent set is desirable.
Choosing the DSL as the target does not remove the mapping problem; it fixes the output
format so the mapper can be replaced later without disturbing execution, permissions, or
rendering.

A small closed intent set, sized to what the realm can actually answer:

| Intent | Example | Sources |
|---|---|---|
| `FindFiles` | "photos from last summer", "the PDF I got in March" | file-service |
| `FindPlaces` | "where was that café", "places I went in Lisbon" | timeline, place |
| `RecallMoment` | "what did I do last Saturday" | timeline |
| `QueryFitness` | "how far did I run this week" | health |
| `DeviceState` | "is the kitchen light on" | home-device |
| `DeviceAction` | "turn off the kitchen light" | home-device (see MQ-D7) |
| `QueryCalendar` | "what's on tomorrow", "when am I seeing Anna" | calendar (MQ-D4a) |
| `CreateEvent` | "add lunch with Anna next Tuesday at one" | calendar — ⚠⚠ **writes**, see MQ-D4a + MQ-D7 |
| `RunRoutine` | "run my good morning routine" | routines — ⚠⚠ **writes**, `routines-engine.md` RE-5a; resolves a name to one routine and fires `RunRoutineNow` behind MQ-D7's cell |

Slots: entity kind, time range, place, person/label, device + room, free text; **event title,
start, duration, recurrence rule** (MQ-D4a).

- ⚠ **Time expressions are the fiddly part and are pure code, not ML.** "last summer", "at
  the weekend", "in March" carry hemisphere, week-start, and calendar assumptions. They are
  also **localisation-bearing** (ADR 0016) in a way that string resources are not — an
  intent grammar is per-language, and that is a real cost to size before promising Mia in a
  second locale.
  ⚠⚠ **`CreateEvent` is where this bill comes due in full** — see MQ-D4a, which is
  *almost entirely* this bullet.
- ⚠⚠ **Never guess.** The characteristic failure of a keyword router is answering a
  *different* question confidently. When no intent matches with adequate confidence, Mia
  says so and lists what it *can* do. The mock's keyword routing is a demo, not a
  foundation; the real planner needs an explicit no-match verdict from the start.
- Disambiguation is a first-class answer type: "two places called *Blue Bottle* — which?"
  rendered as selectable cells, not as a follow-up sentence to parse.

### MQ-D4a — The calendar intents, and why `CreateEvent` is the interesting one

Added 2026-08-05 from [`calendar-app.md`](calendar-app.md) §6, which found that recording an
event by utterance is a primary Calendar use case rather than an afterthought. ⚠ Both intents
are gated on `:calendar-service` existing (its **CA-1**); nothing here is buildable before that.

⭐ **The integration needs no new primitive.** MQ-D2 already puts this layer proxy-side "because
only the proxy can read across every service", so Mia reaches `:calendar-service` exactly as it
reaches file-service. The app-to-app invocation gap that `contacts-app.md` Q1 traced to
`tenshu-rei.md` Q12/Q16 — the same one `cross-app-file-access.md` D-A/D-B wants — is **not on
this path**. What Calendar owes in return is that its store be **native and chain-queryable**
(its §3 D1: an `Event` composite, not opaque iCal blobs), because MQ-D3 lowers every plan to a
chain. A blob store would be unreadable to the planner, which is why that decision is now
load-bearing on *both* sides.

**⚠⚠ `CreateEvent` is the first intent that authors a row.** `DeviceAction` commands a device;
it does not write user data. Three consequences, each of which lands somewhere else in this
doc:

1. **It pays MQ-D4's localisation bill in full.** *"tomorrow at 3"*, *"next Tuesday"*, *"in an
   hour"*, *"every second Monday"*, *"the 15th"*, *"this weekend"* — a recording intent is
   almost nothing **but** time expressions, so the per-language grammar cost that bullet warns
   about arrives here first and largest. ⭐ The offsetting news: Calendar's own schema work
   (its §3 D2/D3) forces a rigorous `(LocalDateTime, tzid)` + recurrence-rule model with an
   expander into `:calendar-service-common`, so **the grammar has a typed target that already
   exists** — it resolves an utterance to *types*, not strings, which is exactly what stops
   "next Tuesday" being reinterpreted differently in three places. And *"every Tuesday at 9"*
   is the recurrence use case, so that expander earns its keep twice.
   ⚖️ **Seam: the grammar lives in `:mia-service`** (per-language, Mia's); **the types it
   produces live in `:calendar-service-common`** (the calendar's). Neither module learns the
   other's job, and a second locale costs grammar only.
2. **It needs a write grant** — MQ-D6, amended below.
3. **It breaks MQ-D1's read-only safety property** — recorded below.

**⚠ Mia fills only the slots it heard.** Everything else — which calendar, how long, whether to
remind — comes from defaults the *service* owns, because an op invoked from here has no editor
screen to resolve them (`calendar-app.md` §6.4 makes these `:preference-service` rows designed
in at CA-1). The MQ-D7 confirmation cell then shows the **resolved** event — the actual
calendar, the actual end time, the actual reminder — never the partial utterance. The user
confirms what will exist, not what was understood.

**⚠ `DeleteEvent` is deliberately not an intent.** Deletion by utterance carries all of the
misheard-slot risk and none of the "I would have typed it anyway" payoff. Editing an existing
event is likewise out of v1: it requires resolving *which* event first, which is a
disambiguation turn on top of a write.

### MQ-D5 — Fuzzy retrieval is the shared embedding index

Where exact terms fail — "beach photos", "that picture with the red door" — retrieval falls
back to the semantic index from [`inference-service.md`](inference-service.md) (IN-3).

- ⭐ **One index serves both apps.** Drive's semantic search and Mia's retrieval are the
  same substrate; building either separately would build it twice.
- ⚠ **Graceful degradation is required, not optional.** The index is opt-in (IN-D3) and may
  be absent, disabled, or still building. Mia must fall back to exact matching and say what
  it could not do — never return empty as if the answer were "nothing".
- Character and label queries ("photos of Miku", "photos of Anna") route through the same
  index and the same per-photo labels (IN-D5), so they work identically whether the label
  came from a tagger or from the user typing a name.

### MQ-D6 — Capability-scoped read access from day one

`milestones.md` §Mia promises "semantic permissions governing exactly what data they can
see" — and frames it as a *Skills* concern. It should apply to **Mia itself** first.

- Mia declares which services it may read. The user sees the list and can revoke per
  service ("Mia may read Timeline: off"), in Mia's Settings and in Control's app surface.
- A revoked source drops out of planning entirely — the intent becomes unanswerable and
  Mia says why, rather than silently returning less.
- ⭐ **This is what makes Skills a widening rather than a new mechanism.** When third-party
  skills arrive post-Rei (D3), they slot into a permission model that already exists and
  has been exercised by the first-party assistant — instead of Rei having to invent one for
  code we do not control. It also means the first consumer of the mechanism is one we can
  fix.
- ⚠ Deliberately *not* per-file or per-row: that is the granular capability track flagged
  in the iOS route-1 migration §4 and CD-D8, and it is not blocked on here. Mia's grant is
  per service, and the UI should say so plainly rather than implying finer control.

⚠⚠ **Amended 2026-08-05 — the grant needs a verb, not just a noun.** This decision was written
read-only ("capability-scoped **read** access", revoke copy *"Mia may read Timeline: off"*), and
that was right while every intent was a read. `CreateEvent` (MQ-D4a) breaks it: **"Mia may read
Calendar" and "Mia may add events to Calendar" are two different grants**, and a user will
reasonably want the first without the second.

- A grant is therefore **(service, access)** where access is `read` or `read + write`, rendered
  as two lines rather than one switch. ⚠ Write without read is not offered — every write intent
  must first resolve slots against existing data, and a grant that cannot be satisfied is worse
  than one that is absent.
- ⚠ **`read` stays the default for every service, including Calendar.** Write is opt-in, and the
  first `CreateEvent` utterance is the natural place to ask for it — the `SCHEDULE_EXACT_ALARM`
  posture from `routines-engine.md` RE-D5, one layer up: ask at the moment the capability is
  genuinely load-bearing, with copy that says why.
- ⭐ **This is MQ-D6's own argument applied one level further.** The decision exists so that
  Skills arrive post-Rei into a permission model that already works and has been exercised by
  code we control. A first-party write intent is the cheapest possible place to discover that
  the model needed a verb — far cheaper than discovering it when the first third-party skill
  wants to write.
- ⚠ `DeviceAction` is **not** retro-fitted into the write tier: it commands a device through the
  shipped `DeviceControlSource` ops and authors no realm row. Its control is MQ-D7's
  confirmation, unchanged. Widening the tier to cover it would conflate "changes the world" with
  "writes your data", which are different user fears with different remedies.

### MQ-D7 — Reads run; actions confirm

Answering a question is free and reversible. Turning off a light, deleting a file, or
creating a routine is not.

- Any `QueryPlan` containing a **mutating** operation renders as a confirmation cell
  showing exactly what will happen, and runs only on tap.
- ⚠ This matters far more once voice lands: a spoken interface has no undo and no hover
  state, and a misheard slot ("turn off *everything*") is a bad night. The confirmation
  rule is cheap now and expensive to retrofit into a voice turn.
- Device actions reuse the shipped `DeviceControlSource` ops from the HD-3 follow-up —
  including its correlation semantics: **`setPower`, not `toggle`** (absolute intent
  against a possibly-stale reading), and outcomes resolved on the echoed `lastCommandId`.
  Mia inherits `Pending`/`Failed`/`Unconfirmed` unchanged rather than inventing a second
  vocabulary for the same thing.
- **⚠ Data-authoring actions (MQ-D4a's `CreateEvent`) confirm the *resolved* artifact.** A
  device action can render its intent — "turn off the kitchen light" is the whole truth. A
  write cannot: the row that will exist is the utterance *plus* the service's defaults, so
  the cell must show the calendar, the end time and the reminder that will actually be
  written. ⚠⚠ Confirming a partial utterance and letting defaults land unseen is the
  failure mode this bullet exists to prevent — the user would be agreeing to something they
  were never shown.

### MQ-D8 — Conversation history is device-local by default

⚠ Transcripts are the most sensitive artifact Mia produces. What someone *asks* is
routinely more revealing than what they are shown — a symptom search, a person's name, a
place they are checking on. Treating them as ordinary synced rows because they are small
would be a mistake.

- v1: local storage, device-scoped. Syncing history across devices is a **separate opt-in**
  with its own copy, not a consequence of using the app.
- ⚠ The M-series History tab currently renders mock conversations; when it becomes real it
  needs a delete affordance (per-conversation and clear-all) in the same slice. A history
  you cannot delete is a worse feature than no history.
- ⚠ Forward-looking: a Skill must never see history it did not produce. Recording it now so
  the Rei-era permission model does not have to discover it.

### MQ-D9 — Any future model runs behind `InferenceService`

If a generative layer lands (MQ-5), it is a consumer of IN-D1 — so *where* it runs (phone,
hub, co-computer) is a binding, and Mia's code does not change when the user buys a hub. It
is also strictly optional: every intent in MQ-D4 must keep working with it off.

### MQ-D10 — One chain validator, built in v1, before anything can generate chains

Every chain that reaches execution on Mia's behalf passes a validator, **whether the
planner's grammar built it or (later) a model did**. It is a pure function over the
`List<Data>` callstack — walkable because each step carries its `OperationSchema`.

⭐⭐ **Build it in v1, while the only generator is our own grammar.** A validator written
when you control the generator is one you can prove; a validator retrofitted around a model
is how a hole ships. In v1 it should never reject anything the planner emits — that is the
point. It is a standing invariant, not a v5 feature, and it costs almost nothing now.

Four checks, in order of how much they carry:

1. **Op allowlist — reads only.** ⚠⚠ The read-only property is **not** inherent to the
   chain mechanism: the same `OperationDto` callstack carries mutating operations, which is
   how every write in the platform travels. Read-only is therefore something the validator
   *enforces*, never something the DSL grants. Mutations reach the realm only through
   MQ-D7's confirmation path, never by being present in a query chain.
2. **Service allowlist** — from the MQ-D6 grants. A chain touching a revoked service is
   rejected before execution, not filtered afterwards.
3. ⭐ **Projection allowlist — the stand-in for permissions we do not have.** This is the
   check that earns the decision. ⚠ Tenshu has **no row-, field-, or property-level
   capability system**; grants are per *service* (finer granularity is the future
   capability track — `camera-service.md` CD-D8, `ios-first-party-route-1-migration.md`
   §4). An intent grammar never asks for content bytes *implicitly*; a free-form chain can
   project `File.Data` or `File.Preview` explicitly. So the validator pins the projection:
   metadata and labels yes, content bytes no, unless the intent genuinely requires them.
   ⚠ Note the direction of the argument — **more expressive querying increases the need for
   fine-grained permissions rather than being covered by the ones that exist.**
4. **Cost bounds.** A hand-written intent has a known cost; a generated chain does not.
   Require a terminal `take`, bound chain depth and nesting, and impose a wall-clock
   timeout. ⚠ Include the projection: `RecursiveListWalk` **loses the projection**
   (`ios-drive-parity.md` §24.5), so a generated chain that walks a large tree materialises
   every stored value property including `File.Thumbnail` per row and dies on heap long
   before it saturates CPU. This is `inference-service.md` IN-D6's scaling trap arriving
   early, and it is the most likely way a "clever" generated query takes the proxy down.

⚠ **Honest cost, and why the intent layer survives.** A callstack is *inspectable* but not
*legible* — MQ-D3's explainability argument ("searched Drive for images, June–August 2025")
needs prose, and rendering an arbitrary chain back into a sentence is real work that can
itself be wrong. Keeping `QueryPlan` above the chain is what preserves it: the **intent is
what the user is shown, the chain is what runs**. A chain-only design would trade a legible
explanation for a technically-accurate dump.

⚠ **Subject identity is weaker than it looks.** The validator authenticates a *chain*, not
a *caller*. App registration is proxy-side and allowlist-gated, but on desktop
`UnixSocketServer` still hardcodes `clientId = "drive-desktop-client"` — every desktop app
registers as Drive (tracked separately). Per-service grants therefore cannot yet be
enforced against a reliably-identified subject on that platform. Recorded so MQ-D6 is not
read as stronger than it is; closing it is app-registration work, not query-layer work.

---

## 3. Why this un-parks Mia

`milestones.md` gates Mia behind App Console & Tenshu Store because the *Skills* tab needs
Rei. D3 already dropped Skills from v1. What remained was that Mia had no backend at all —
and the assumed backend ("Voice App Service", "ML-services contract", "model distribution
that does not bottleneck on a single provider") is three unbuilt tracks.

This doc's claim is that **none of them are on the critical path**. A deterministic planner
over shipped services makes Mia genuinely useful — it answers real questions about real
data — with no ML, no voice, and no third-party code. Voice (`audio-and-voice.md`),
inference (`inference-service.md`), and Skills (post-Rei) then each *widen* a working app
instead of being preconditions for a first one.

## 4. Threat model duties

Per House Rule #2, per slice:

- **A new cross-service reader.** Mia is the first component that reads across every
  service on the user's behalf; MQ-D6's capability grants are the control, and the
  amendment should name the surface (assets touched: whatever the granted services hold).
- **⚠⚠ Generated chains as a confused-deputy surface (MQ-D10).** Mia executes chains it
  composed, with the user's own authority — so the validator, not the caller, is the
  boundary. The RE-1-style amendment must state the four checks as invariants, and must
  record two limits plainly: **read-only is enforced, not inherent** (the callstack carries
  writes), and **grants are per service, not per property**, with the projection allowlist
  standing in until the fine-grained capability track lands. A model-generated chain
  (MQ-5) changes the *generator*, not the boundary — which is the whole point of building
  the validator in MQ-1.
- **⚠⚠ Mia as a *writer* of realm data (MQ-D4a, lands with MQ-3).** Every duty above assumes
  Mia reads; `CreateEvent` makes it the first component to author a first-party row on the
  user's behalf. The amendment must name **two** controls, because they answer different
  questions: MQ-D6's **write grant** ("may Mia do this at all") and MQ-D7's **resolved-artifact
  confirmation** ("is *this* the row you meant"). ⚠ It must also record plainly that a written
  row is *not* contained the way an answer is — it syncs to every device and can trigger a
  reminder long after the utterance — so the blast radius of a misheard slot is durable, not
  transient. ⭐ Bounded by the fact that the only write intent is creating a reversible event;
  `DeleteEvent` and event editing are deliberately out (MQ-D4a).
- **Conversation history as a new sensitive local artifact** (MQ-D8) — device-scoped
  plaintext, deletable, not synced without opt-in.
- **Voice, when it arrives** — audio capture and the in-use plaintext of a transcript, per
  `hub-hardware-and-management-service.md` §9 and AV-D6.
- **A generative layer** — prompts as in-use plaintext; see `inference-service.md` §4.

## 5. Phasing

Every slice is provable with unit tests plus a system test against an in-process proxy; ⚠
the client-stack slices additionally need the Android smoke gate (MQ-D2).

- **MQ-1 — the spine, one source. ✅ SHIPPED 2026-08-11 — as-built in §7.** `:mia-service` module set; Mia's first client stack;
  `QueryPlan`/`Answer` types; the deterministic planner over **Drive files only** (name,
  type, date — `searchFiles` and the file index are shipped);
  `MockConversationRepository` replaced by the real implementation behind the unchanged
  `ConversationRepository` seam. ⭐ First real answer in the chat surface, on both Mia and
  Home's embedded chat, for free.
  ⭐⭐ **Ships the MQ-D10 validator**, even though the grammar cannot violate it — that is
  precisely when to build it (see MQ-D10). Lowering to a real callstack from day one is
  also what keeps MQ-D3 honest: a `QueryPlan` that never compiled to a chain would quietly
  become the second query language this decision exists to avoid.
- **MQ-2a — widen the sources. ✅ SHIPPED 2026-08-11 — as-built in §8.**
- **MQ-2b — the grants. ✅ SHIPPED 2026-08-11 — as-built in §9.** MQ-2 is complete.
- **MQ-2 — widen the sources.** Timeline places/moments, Fit workouts, Home device state.
  The rich cells built in M3 (`PlaceCard`, `PhotoGrid`) light up with real rows.
  MQ-D6 capability grants + their Settings UI land here, with more than one source to
  revoke.
  ⭐ **`QueryCalendar` (MQ-D4a) slots in here** *if* `:calendar-service` CA-1 has landed —
  it is an ordinary read source and costs a source adapter, nothing structural. ⚠ If CA-1 is
  not ready, this slice ships without it and the intent follows; the two tracks meet at a
  service boundary and neither blocks the other.
- **MQ-3a — device actions. ✅ SHIPPED 2026-08-11 — as-built in §10.** (`CreateEvent` is MQ-3b.)
- **MQ-3b — `CreateEvent`. ✅ SHIPPED 2026-08-12 — as-built in §11.** MQ-3 is complete.
- **MQ-3 — actions.** Mutating plans + the confirmation cell (MQ-D7) over the shipped
  `DeviceControlSource` ops. Routine invocation follows once the routines engine exists.
  ⚠⚠ **`CreateEvent` (MQ-D4a) also lands here, and it is the harder half** — it is the first
  *data-authoring* action, so this slice additionally owes MQ-D6's **write tier** (the grant
  UI grows a second line), MQ-D7's **resolved-artifact** confirmation cell, and the temporal
  grammar that MQ-D4 warns is per-language. ⚖️ Sequence the device half first: it exercises
  the confirmation machinery against an action whose intent and effect are the same thing,
  which is the cheaper place to get that machinery right.
- **MQ-4 — semantic retrieval.** Consumes `inference-service.md` — now its **IN-4**, after the
  C0 decisions re-shaped that phase map (`inference-service.md` §7.6). "find the beach photo".
  Degrades to MQ-1 behaviour when the capability is off.
  ⚠⚠ **C0 found that this cannot slot in behind the existing chain, which is what MQ-D5
  assumed.** The DSL has `equalTo`, `contains` and `inRange`; nearest-neighbour over a vector is
  none of them, and no predicate would make a *ranked* similarity query into a filter. Semantic
  retrieval needs a **dedicated op** — the `photosCapturedBetween` precedent — which the plan
  lowers to exactly as `FindPhotos` already does (`inference-service.md` IN-D12).
  ⭐ Label search, by contrast, *is* an ordinary MQ-2-shaped source adapter: "photos of cats"
  lowers to a `contains` over label rows and needs nothing new.
  ⚠ MQ-4 is therefore gated on IN-1…IN-3, none of which is built.
- **MQ-5 — optional generative layer.** A model as *planner* (utterance → `QueryPlan`) and
  optionally as answer phrasing, behind `InferenceService`. Strictly optional, strictly
  on-device or hub. ⚠ Keep MQ-D1's citation property: generated prose must accompany real
  rows, never replace them.
  - ⭐ **Optionally, and only here: model → chain directly** for the long tail of questions
    no intent covers. This is the payoff for MQ-D3's lowering and MQ-D10's validator — the
    generated chain meets exactly the same four checks the grammar's chains have been
    meeting since MQ-1, so the incremental risk is the *legibility* gap (MQ-D10's honest
    cost), not a new security boundary. ⚠ Constrained decoding into the callstack composite
    shape, never free-text parsed into one.
- **Later:** voice input flips `MiaCapabilities.voiceInput` (D4) with no query-layer change
  — a transcript is just an utterance; Skills widen MQ-D6 post-Rei.

## 6. Pointers

- [`home-and-mia-apps.md`](home-and-mia-apps.md) — §4 Mia IA, D3 (Skills deferred), D4
  (text-first / `MiaCapabilities.voiceInput`), D7 (OS integration).
- [`inference-service.md`](inference-service.md) — IN-D1 (the seam MQ-D9 rides), IN-3 (the
  index MQ-D5 consumes), IN-D5 (labels vs templates).
- [`home-device-service.md`](home-device-service.md) — the HD-3 follow-up
  `DeviceControlSource` ops and the `Pending`/`Failed`/`Unconfirmed` vocabulary MQ-D7
  inherits.
- [`audio-and-voice.md`](audio-and-voice.md) — AV-D6, where STT runs; the voice turn that
  later feeds this layer.
- [`core-chain-entity-typing.md`](core-chain-entity-typing.md) — the entity-aware
  `search`/`select` surface MQ-D3 lowers onto; `RequestChain.getCallstack(): List<Data>` is
  the reification the whole decision rests on.
- [`core-ordered-window-fusion.md`](core-ordered-window-fusion.md) — F1, which a generated
  `sortBy(key).take(n)` fuses through for free.
- [`core-deferred-properties.md`](core-deferred-properties.md) — `@defer`, which generated
  projections obey unchanged.
- [`control-ia.md`](control-ia.md) §6.2 — the one-line `AIService` sketch this and
  `inference-service.md` jointly replace.
- `milestones.md` §Mia — the original five-surface framing and the dependency list this
  doc argues is not on the critical path.
- [`timeline-services.md`](timeline-services.md) / [`place-geocoding.md`](place-geocoding.md)
  — the `Moment`/`Place` spine MQ-2 reads.
- [`calendar-app.md`](calendar-app.md) — §6 is the source of MQ-D4a; its §3 D1 (native
  schema, not iCal blobs) is what makes the calendar readable to the planner at all, §3 D2/D3
  is the temporal model the grammar resolves onto, and §6.4 owns the headless defaults the
  MQ-D7 cell renders. ⚠ Its **CA-1** gates both calendar intents.

---

## 7. As built — MQ-1 (2026-08-11)

The pipeline shipped as designed: `utterance → QueryPlan → callstack → validator → execute
→ MiaAnswer`. What follows is what the design did not say, in the order it was learned.

### 7.1 The grammar is two intents, and the boundary is a property of the chain DSL

MQ-1's scope reads "Drive files only — name, type, date". **Only two of those three are
expressible against the shipped file plane**, and the third is not a matter of effort:

- a **name** fragment pushes down as `contains` — the chain Drive's own search already runs;
- an image **time window** is exactly what the shipped `photosCapturedBetween` op answers;
- a **date filter over arbitrary files**, or a category filter beyond images, needs a
  *comparison* predicate, and the DSL has only `equalTo` and `contains`. There is nothing
  to lower onto.

⭐ **Closed for MQ-2 (2026-08-11): `inRange` landed in `:core`.** MQ-2 needed the same window
over Timeline, Fit and Calendar, which turned T-P2's one-caller arithmetic into a
four-caller one — so the comparison predicate this section says the DSL lacks now exists
(`core-operation-dispatch.md` §5), and the file-date gap below closes with MQ-2a's grammar
rather than needing a fourth bespoke op. The text that follows describes MQ-1 as shipped.

So `QueryPlanner` emits `FindPhotos` or `FindFilesNamed`, and **"files from last week" is an
explicit `NoMatch` that names what Mia *can* do** rather than quietly answering about
photos. ⚠ Widening the grammar ahead of a `:core` comparison predicate would only produce
plans that cannot be lowered — the honest place for that work is MQ-2, with its sources.

⚠ `FileCategory` cannot narrow a chain either: `File.Category` is a *constant* relation
registered without a value factory, so a file's category exists only as a derivation from
its name (`fileCategoryForName`). That is why the photo intent routes to an op rather than
to a predicate.

### 7.2 ⚠⚠ `CallstackMaterializer` cannot lower a chain that contains a predicate

The first lowering used `ExecutionContext.materializer` — the same seam a dynamic-property
relation uses. It produced a callstack that failed at execution with
`Could not handle protocol: tenshu/core/list`.

**Cause: `SimpleRequestChain` mutates its callstack in place, and `FilterRo` requires
copy-on-write semantics.** `FilterRo` builds the predicate on a *derived* chain, reads the
nested `TList` off it, and then appends its own `OperationDto` to the **original** — which
is correct against `RequestChainBuilder` (a `data class`, `copy()` per step) and wrong
against `SimpleRequestChain` (a shared `MutableList`). Under the mutating chain the
original has *already* grown the predicate list, so the callstack carries a stray top-level
`TList` that nothing can dispatch.

Dynamic-property relations never noticed because their chains are **linear** — no nested
arrays, so mutation is unobservable. ⚠ **This is latent for any future dynamic-property
chain that uses a predicate**, and it is not fixed here (a `:core` change with wide blast
radius, for a case nothing hits today). Recorded so the next person does not spend the same
hour: `QueryPlanLowering` builds over `RequestChainBuilder` directly, and
`QueryPlanLoweringTest.noPredicateListIsStrandedAtTheTopLevelOfTheChain` pins the shape.

### 7.3 The chain must start at `FileService`, not at `FileSystem`

`FileSystemRo.root` appends only its relation reference, while `FileServiceRo
.defaultFileSystem` appends the `ServiceTypeReference`. Rooting at `FileSystem.ID` yields a
callstack whose first step is an entity reference and dies with *"Entity reference … needs
to be called on a service"*. ⚠ Consequently the validator's service allowlist holds
`FileService.Relations.DEFAULT_FILE_SYSTEM` — **a chain names a service through the
reference that reaches it**, which is the id the walk actually sees.

### 7.4 The clock and the zone come from the caller

`askMia(utterance, nowEpochMs, timeZoneId)`. "Tomorrow" and "last summer" are answered
against the clock and calendar of the device the person is *holding* — the proxy may be a
hub in another timezone, or a desktop left running abroad. This is `routines-engine.md`
RE-D3's rule one layer up, and it is also what keeps the planner **pure**: 18 grammar tests
run with no IO and no clock.

⚠ **Seasons are northern-hemisphere meteorological.** Nothing in a realm says where the
user lives, so "summer" cannot be right for everyone. The mitigation is MQ-D3's
explainability doing real work: the answer quotes the resolved range back ("photos from
summer 2025"), so a user in Sydney sees the wrong months *named* rather than receiving a
confidently wrong set of photos. Naming the months by hand works everywhere.

⚠ There is deliberately **no `languageTag` param** — nothing would read it, and a second
locale is a new op id under the `RenewTaskLease` rule rather than an unused field.

### 7.5 What the validator ended up checking

`MiaChainValidator` takes a `Policy` (not a hardcoded set) precisely because MQ-2 replaces
`allowedServiceIds` with the user's revocable grants. Cost bounds needed one shape the
design did not anticipate: a chain is bounded either by a `take` **or** by a terminal op
that caps its own result, so the policy carries `boundingOperations`
(`photosCapturedBetween` → its `limit`) rather than searching for one operation id.

⚠ The projection check is an **allowlist**, not a denylist: `File.Data` / `File.Preview` /
`File.Thumbnail` are absent by construction, so the next content-bearing property somebody
adds is refused without anyone remembering to add it.

⚠⚠ `MiaChainValidatorTest` removes exactly one thing from the policy per case and asserts
the refusal names it — **a validator tested only on the positive side is indistinguishable
from `return Allowed`**, and in v1 it legitimately never fires.

### 7.6 The mock is not a fallback

`TenshuConversationRepository` answers *"I can't reach Control right now"* when the proxy is
unreachable. ⚠⚠ It deliberately does **not** fall back to `MockConversationRepository`:
invented photos and places rendered as real is precisely the shape
`NotConnectedTimelineRepository` had to be written to undo
(`ios-fit-timeline-maps-parity.md` P1). Three verdicts stay three sentences — *I did not
understand you* / *I understood and there is nothing* / *I could not look*.

⚠ **History is in-memory for the process.** MQ-D8 requires a delete affordance in the same
slice that makes transcripts durable; keeping this session-scoped honours "device-local by
default" now and leaves durability + deletion as one coherent later slice, rather than
shipping a history nobody can clear.

### 7.7 Wiring, and what it cost elsewhere

- `:mia-service{,-common,-client,-proxy}`, registered in `ProxyModule` (both halves — see
  `miaServiceCommonModule`'s KDoc for why the client registration alone is not enough) and
  in the Android + desktop `PlatformProxyModule` op factories, and in the iOS one since
  2026-10-02. ⚠ iOS has no model backend, so there the grammar plans every question and
  `photosLike` answers `NO_BACKEND`. ⚠⚠ The iOS factory passes `MiaActionOffer.WITHHELD`, so
  no action is offered there at all (§19) — which is what keeps a routine's typed refusal
  (`routines-engine.md` RE-D13) from ever being reached through a confirmation.
- `miaClientModule` + `miaPlatformClientModule` (Binder / UDS), included **after**
  `miaModules()` so the real repository overrides the mock. Home includes the two schema
  configs and the binding instead of a second client stack, so its embedded *Ask Mia*
  answers for real too.
- ⚠⚠ **`mia-android`'s manifest was missing `<queries>` for Control and the
  `ClientCallbackService`** — both silent failures. Added.
- Mia joined `BundledAppCatalogue`: the "no Mia entry" note there was waiting for exactly
  this (`wireSchemaAnnounce` is what gives the registration trigger a handshake to fire on).
  ⚠ Its declared permission is **`askMia` on `tenshu/mia/service`**, not the services it
  reads — naming file-service would describe a call Mia never makes.
- Mia **joined the Android smoke gate** (MQ-D2), and passes it. ⚠ That costs its canary role:
  the un-built set is now Fit and Contacts, still non-empty but thinner.
  ⚠ **Mia is expected to WARN on step 4.6 (schema announce), structurally and permanently.**
  The announce is a *session preflight* that rides ahead of the first op, and Mia issues no
  op until the user types — which a smoke run does not do. Its step-4.5 schema-version
  beacon still carries the lockstep signal, and the script now says so; nobody should chase
  this twice.

### 7.8 Format and threat model

No existing composite changed shape. `SchemaShapeBaseline` gains three entries
(`MiaAnswer`, `MiaFileHit`, `askMia`'s params), all at `tenshu/mia/service` **version 1** —
so HR #9 is satisfied with **no `TENSHU_STORAGE_FORMAT_VERSION` bump and no migration**: a
brand-new service at v1 reshapes nothing. No relay redeploy (the 426 gate reads
`RELAY_PROTOCOL_VERSION`, untouched).

Threat model: **A39 + T-MQ-01/02** (§8.22) — Mia as the first cross-service reader, and generated
chains as a confused-deputy surface whose boundary is the validator rather than the caller.

### 7.9 Left for MQ-2 and beyond

- MQ-D6's per-service grants: the `Policy` seam exists, the Settings UI does not.
- Nothing reads Timeline, Fit, Home or Calendar yet — the sources are MQ-2's slice.
- A tapped citation does not open Drive; the answer names paths but has no navigation.
- The proxy-side name search is still a full tree walk (`RecursiveListWalk` loses the
  projection), so it inherits Drive's search cost profile exactly.


---

## 8. As built — MQ-2a (2026-08-11)

Four sources — Timeline moments, Fit workouts, Places, Home devices — and the answer shape
that stops a fifth from costing anything.

### 8.1 One hit type, discriminated, replacing `MiaFileHit`

MQ-1's file-specific hit would have meant **a new list field on `MiaAnswer` per source** —
five positional fields on a wire composite that grows every time Mia learns to read
something. `MiaHit { kind, reference, title, subtitle, at }` makes adding a source cost
**zero wire shape**.

The collapse is honest rather than lossy because of what a citation is *for*: the cells
render a title, a subtitle and a glyph, and the tap-through needs an address. `PlaceCard`
wants a name and a detail line, exactly like a file row does. ⚠ `reference` carries the
**owning service's own** identifier (a path, a `placeId`, a `momentId`) — Mia never mints a
routing vocabulary of its own.

⚠ This reshaped a wire type, so `MiaService` went to **v2 with an empty migration step**.
Nothing is stored — but the floor is *derived from the registered chain*, and a bumped
service with no chain sits below its own floor, which is a store **wipe**. The `SyncService`
v2 precedent.

### 8.2 ⚠⚠ The service allowlist takes two different id shapes

The validator refused all four new sources with `service not granted` on the first run.
`ServiceRo.serviceRelation` prepends the **service's own id**, so a chain reaching
`service.moments` emits a `ServiceTypeReference` for `tenshu/timeline/service`. The file
plane is the odd one out: `defaultFileSystem` is a *service-to-service* relation, so what
lands in the callstack is that **relation's** id and no bare service id at all — which is
what MQ-1's single entry recorded, and why it read as a general rule when it was a special
case. Listing the wrong one of the two refuses every chain from that source.

### 8.3 ⚠⚠ A type needs an `roFactory` to be a *predicate receiver* — 15 did not have one

`filter<Moment> { … }` died on `class CompositeRo cannot be cast to class Moment`, thrown
from inside `FilterRo`, naming neither the module nor the registration responsible.

**`dtoFactory` builds the row when it is READ; `roFactory` builds the RECEIVER the predicate
lambda runs against.** A type registered with only the first reads perfectly — rows
materialise, projections work — and is silently **unfilterable**. Worse, a *client* module
registering a bare `CompositeRo` **shadows** its own common module's typed one, because the
later config wins at lookup.

⚠ The distinction was entirely undocumented and had been got right by accident: `Workout`,
`HomeDevice` and `FileModification` carried `object : CompositeRo(…), X {}` while `Moment`,
`Place` and a dozen others carried `CompositeRo(…)`. That is not a difference anyone can
hold in their head.

**`TypedPredicateReceiverTest` now judges the whole service graph** and found **12 more**
beyond the three MQ-2a needed — across health, identity, notification and map-region, every
one a latent landmine for the first person to filter that type. All fixed; the guard prints
the exact replacement. ⚠ Zero shapes changed (the baseline diff proves it): these are
RO-construction only, with no wire impact.

⚠ Coverage is the service graph `:system-test-utils` wires — `fit`, `timeline`, `logging`,
`ingest`, `storage` are absent from it and remain unjudged.

### 8.4 ⚠ Calendar is NOT an ordinary source adapter — MQ-D4a is wrong about this

MQ-D4a says `QueryCalendar` "is an ordinary read source and costs a source adapter, nothing
structural". It is not, for two independent reasons:

1. **`Event.startLocal` is a `string`**, not an instant — a `(LocalDateTime, tzid)` pair per
   `calendar-app.md` §3 D2/D3. `inRange` is numeric, so it would return `false` for every
   row and answer *"nothing on tomorrow"* for a full calendar. Silently.
2. **A recurring event has no occurrence rows at all.** Occurrences come from the CA-1
   expander, so "what's on tomorrow" is not a row filter under any predicate.

Deferred out of MQ-2a with that reason recorded rather than shipped half-working. It needs
its own slice built on the expander.

### 8.5 What the grammar routes, and in what order

Specific-noun-first — device and fitness nouns name exactly one source and are almost never
incidental; a photo word or a bare leftover is the vaguest signal, so they resolve last.
⭐ A **bare time window with no noun** is the "what did I do then" question, which reads as a
fallback and is in fact the most natural phrasing there is. Weekday support landed so the
doc's own "last Saturday" example resolves — ⚠ and *today* deliberately does not count as
"Saturday" when it is Saturday.

⚠ A workout is filed by when it **started**: an activity spanning midnight belongs to the day
you set out on, and filtering on either end would put it in both windows.

⚠ Devices read the inventory only. Live state is a separate `DeviceState` row keyed by the
same `deviceId` — the DSL has no join, and inventing one for a two-list lookup would be a
query language, which MQ-D3 exists to avoid. Commanding a device is MQ-3, behind MQ-D7.

### 8.6 Still open after MQ-2a

- **MQ-2b**: MQ-D6's per-service grants + Settings UI. The `Policy` seam exists and its
  `allowedServiceIds` is still a constant.
- `QueryCalendar` (§8.4) and MQ-1's file-date gap — `File.CreatedAt` is a stored *property*,
  not a schema field, so no chain predicate reaches it; it needs a chain accessor like
  `File.name` has.
- A tapped citation still does not open the owning app.


---

## 9. As built — MQ-2b (2026-08-11)

MQ-D6's grants, which is what MQ-2 had left.

### 9.1 A grant is an ordinary realm preference

One `Preference<Boolean>` per source (`MiaSource`), **realm-scoped** — revoking on the phone
revokes everywhere, which is the user's evident intent — over the existing `RealmSetting`
rows. No new storage, no new wire type, no format movement.

⚠⚠ **Granted by default.** Mia shipped able to read all five in MQ-1/MQ-2a, so defaulting to
revoked would silently break a working assistant on upgrade and hand the user a broken thing
to fix rather than a working thing to restrict. MQ-D6's own wording is that `read` is the
default.

⚠⚠ **And "absent means granted" is the one place that must not fail closed.** The row exists
only once the switch has been touched, so a never-configured realm grants everything. The
fail-closed posture lives one layer down, in the validator, which refuses anything not on the
allowlist the grants produce.

### 9.2 The refusal happens in *planning*, and names the source

MQ-D6 says a revoked source "drops out of planning entirely — the intent becomes unanswerable
and Mia says why, rather than silently returning less". So `QueryPlanner.plan` takes the
granted set, and a plan whose source is revoked becomes a `NoMatch` reading *"I understood
that, but I'm not allowed to read your timeline — you can turn that back on in Mia's
settings."*

⚠⚠ **Not an empty answer.** Answering `answered` with zero hits would assert an absence we
never established — telling the user their timeline is bare when it is full. That is the
distinction MQ-D4's no-match verdict exists to protect, applied to permissions.

⚠ To keep the check in one place, `QueryPlan` gained a `source` rather than the lowering
deriving it: by lowering time the only thing left to say is a generic refusal.

⚠ The message names the source in the **user's** word ("timeline", "workouts"), never the
service id — E2g's fence banner already established that printing an internal
`tenshu/…/service` path at a user is worse than saying nothing.

### 9.3 Two checks, and only one is the boundary

`MiaChainValidator.Policy.allowedServiceIds` is now **derived from the same grants**
(`policyFor(granted)`) — which is why the validator took a policy rather than a constant from
the start. The planner's check answers *"should Mia have tried"*; the validator's answers
*"may this callstack run"*. Belt and braces, and only the second is a security boundary.

⚠ Grants are read **per question, never cached**: a revocation must take effect on the next
question, which is what flipping a switch plainly implies. It is a handful of point lookups
against rows that are almost always resident.

### 9.4 The switches

Mia's Settings gains a *What Mia can read* card — one switch per **service**, because that is
the only granularity the platform has. ⚠ The copy says so plainly rather than implying
per-file control the system cannot enforce, and it states the consequence: Mia stops using
the source and *says so* rather than pretending there is nothing there.

⚠ With no proxy the switches render **on and disabled** — truthful, because with nothing to
read there is nothing to grant or revoke; showing them off would suggest Mia had been
restricted when it has not. Home's embedded chat gets the same binding, so one set of grants
governs both surfaces.

### 9.5 Deliberately not done

- **The write tier.** MQ-D6 was amended to `(service, read | read + write)` for `CreateEvent`,
  but no write intent exists yet, so the second line would be a switch nothing consults. It
  lands with MQ-3.
- **Control's app surface.** MQ-D6 wants the grants visible there too; Mia's own Settings is
  where a user looks first, and the Control surface is a second consumer of the same rows.
- ⚠ **Desktop subject identity is still weak** (MQ-D10): `UnixSocketServer` hardcodes the
  `clientId`, so per-service grants cannot be enforced against a reliably identified subject
  there. That is app-registration work, unchanged by this slice.


---

## 10. As built — MQ-3a (2026-08-11)

The device half of MQ-3, sequenced first exactly as the phase note says: it exercises the
confirmation machinery against an action whose intent and effect are the same thing.

### 10.1 ⚠⚠ The action does not lower into the chain — it cannot

MQ-D10's op allowlist is reads-only, and that is *enforced, not inherent*: the same callstack
shape carries every write in the platform. So a `ControlDevice` plan lowers to the **ordinary
device search** — the same read `FindDevices` uses, validated identically — and the write is
not in that chain at all.

⭐ That fell out better than expected. Resolving *which device the user meant* is genuinely a
read, so it belongs in the validated path; only the resolved action needs holding. The turn
splits cleanly:

```
askMia          → read, resolve to exactly one device, return a MiaAction   (nothing happens)
confirmMiaAction→ issue the command                                          (the tap)
```

### 10.2 The token is a narrow capability, by construction

`ConfirmMiaAction` takes **only a token**. It cannot name a device, a verb or a value — the
proxy resolved those before showing them — so the op cannot command an arbitrary device even
though it is an ordinary op anyone can call.

⚠ The alternative (client passes the resolved fields back) was rejected: it would have made
Mia's op a second, worse `IssueDeviceCommand`, and "confirm what will actually happen" would
have been a UI convention rather than a structural property.

⚠ **Single-use and expiring.** A spent token is refused ("that's no longer waiting to be
confirmed"), and a pending action lives 5 minutes. That is a *second* bound on top of
`DeviceCommand.STALE_WINDOW_MS`: the 2-minute window stops a backlog-replayed command
actuating late, this stops a stale *intent* being issued at all. ⚠ The registry is in-memory
and dies with the proxy, which is correct — a pending intent is not durable state, and the
honest answer to a tap whose action has gone is "ask me again".

### 10.3 What the grammar refuses, and why

- ⚠⚠ **"is the kitchen light on" is a question.** It contains "on"; reading that as a command
  and switching the light on would be the worst possible misreading. The **verb** is what makes
  an utterance an instruction, so a bare on/off word without one stays a read.
- ⚠⚠ **Two matching devices are never guessed between.** "turn off the lamp" with a Kitchen
  lamp and a Bedroom lamp lists both and asks. This is exactly where a keyword router picks
  one and is confidently wrong, and here the cost is physical.
- ⚠ **"toggle" is refused with its reason.** `home-device-service` ships `setPower` rather than
  `toggle` because a flip is relative to a possibly-stale reading; an utterance is even worse
  placed to know the current state than a UI control is. Mia only ever expresses absolute
  intent.
- MQ-D6 composes for free: a revoked *Devices* grant stops the action before resolution, so
  there is nothing to tap.

### 10.4 Execution reuses the shipped op

`ConfirmMiaAction` dispatches to `IssueDeviceCommand`'s own implementation rather than writing
a `DeviceCommand` row itself — that op stamps `issuedAtEpochMs` and `issuedByDeviceId`, which
the hub's staleness window and consumed journal key off. Re-writing the row here would
duplicate those rules somewhere they could drift, and MQ-D7's "reuse `DeviceControlSource`"
is precisely about inheriting the correlation semantics.

⚠ **A direct call has to know the op's kind.** `IssueDeviceCommand` is a *sync* implementation;
assuming async cast-failed at runtime, a long way from the assumption. The dispatcher normally
hides this and a direct call does not.

⚠ All it can honestly claim is that the command was **issued**. Whether the bulb moved arrives
later on the device's state row, correlated by `commandId` — the `DeviceControlSource`
contract, unchanged.

### 10.5 ⚠ MQ-D6's "authors no realm row" is not quite right

MQ-D6 justifies keeping `DeviceAction` out of the write tier by saying it "commands a device
through the shipped `DeviceControlSource` ops and authors no realm row". It **does** author a
row — `IssueDeviceCommand` writes a `DeviceCommand`, which syncs.

The *distinction* still holds, and it is the one MQ-D6 actually argues: a command row is
machinery, not the user's data, and "changes the world" is a different fear from "writes your
data" with a different remedy. So `DeviceAction` stays out of the write tier and MQ-3b's
`CreateEvent` is still the first thing that needs it. Only the wording is wrong.

### 10.6 Format

`MiaService` → **v3** with an empty migration. ⚠ Even the *optional* `action` field is a
positional reshape — composites carry no field tags, so an old reader mis-parses everything
after the insertion point rather than ignoring it. Baseline: Mia's four types plus the new op;
nothing else moved.

### 10.7 Left for MQ-3b

`CreateEvent` — the harder half, and it owes all three of the things MQ-D4a names: MQ-D6's
**write tier** (the grant UI grows its second line), MQ-D7's **resolved-artifact** cell over
service-owned defaults, and the per-language temporal grammar. ⚠ And §8.4's finding stands in
its way: `Event.startLocal` is a string and a recurring event has no occurrence rows, so
`QueryCalendar` needs the CA-1 expander before either calendar intent is buildable.


---

## 11. As built — MQ-3b (2026-08-12)

`CreateEvent`: the first intent that **authors user data**.

### 11.1 ⚠ §8.4 does NOT gate this, contrary to what MQ-3a's §10.7 said

§8.4 found that `Event.startLocal` is a **string** and that a recurring event has no occurrence
rows. Both block `QueryCalendar` — the *read*. **Neither blocks the write**, and the reason is
worth stating because the note said otherwise:

- A scheduling grammar's natural output is a **`LocalDateTime`**, which is exactly what
  `startLocal` *is*. The string typing that makes the field unfilterable makes it trivially
  writable.
- Recurrence expansion is a read-side concern. Writing a single event never touches it.

So MQ-3b shipped without CA-1 movement, and `QueryCalendar` remains the thing that needs the
expander.

### 11.2 A scheduling grammar looks FORWARD; the search grammar looks BACK

`EventTimeGrammar` is a separate object from `TimeGrammar`, and it has to be. `TimeGrammar`
resolves "saturday" to the most recent one *that has passed*, because you ask about what you
did. Here it is the *next* one, because you schedule what you will do. ⚠ Sharing one resolver
would have made one of the two silently wrong — and the wrong one would have been the write,
putting every event in the past.

⚠⚠ **A bare hour is ambiguous and the convention is only defensible because of the cell.**
"at 3" reads as 15:00 (1–7 → afternoon, 8–12 → morning), which is what every calendar app
converged on — but the cost of being wrong is a reminder at 03:00 three weeks later. It is
acceptable *only* because MQ-D7's confirmation shows the resolved time before the row exists.
Never adopt that rule anywhere the user does not see the result.

⚠ **A missing time is asked for, never guessed.** A read with a missing slot returns a wrong
answer the user ignores; a write with a missing slot creates a wrong row that persists and
fires. "When?" is the answer.

### 11.3 The resolved-artifact confirmation, and one thing it cannot say

MQ-D4a: the cell must show the row that will exist — the actual calendar and end time, not the
partial utterance. So Mia reads the **same preference rows `SaveEvent` reads**
(`DefaultCalendarId`, `DefaultEventDurationMinutes`), displays them, and passes them back
**explicitly** at execution.

⭐ Passing them explicitly is what makes the confirmation *binding* rather than a good-faith
prediction: if the default calendar changes between the cell and the tap, the event still lands
where it said it would.

⚠⚠ **No reminder is named, and MQ-D4a expects one.** `SaveEventImplementation` attaches none —
reminders are separate `Reminder` rows written elsewhere — so claiming one would be exactly the
failure the resolved-artifact rule exists to prevent. The cell says nothing about reminders
until something actually creates them.

⚠ **No default calendar → refuse before offering.** `SaveEvent` throws in that case, and a
confirmation whose button is guaranteed to fail is worse than an honest no.

### 11.4 The write tier

`MiaSource` gains `CALENDAR` and an optional `writePreference`. ⚠ **Only sources that can
actually be written carry one** — a switch nothing consults implies a capability the system
does not have, so the second line appears for Calendar alone.

⚠⚠ **The write grant defaults to FALSE, the opposite of the read grant, and both defaults are
right.** Reading was the shipped behaviour, so defaulting it off would break a working
assistant; writing has never happened, so silence means no. ⚠ Write also implies read — a write
intent resolves its slots against existing data first, so a write grant without the read one is
unsatisfiable and is treated as absent (test-pinned).

⚠ The refusal names *which* permission is missing: "I'm not allowed to add things to your
calendar" is actionable where a generic no is not.

### 11.5 ⚠⚠ A direct call to another op must know its KIND — twice now

MQ-3a assumed `IssueDeviceCommand` was async; it is sync. MQ-3b assumed `SaveEvent` was async;
it is sync too. Both compiled and both cast-failed at runtime, a long way from the assumption,
because `evaluateCallstack` normally picks the right arm and a direct call does not.

Two for two, so the guess is no longer worth making: `dispatchOperation(...)` handles either
arm and returns `false` when the op is genuinely absent — so a caller says "I can't reach that"
rather than reporting a success nothing performed.

### 11.6 ⚠ A title is not a query

`eventTitle` deliberately does **not** strip the stop-words a search query strips. "add lunch
with anna" must produce *"lunch with anna"*, not *"lunch anna"* — a query wants filler removed,
a title is free text the user reads back. The two look like the same operation and are not.

### 11.7 Format

No schema movement at all: `CreateEvent` reuses MQ-3a's `MiaAction`/`ConfirmMiaAction`, and the
grants are preference rows. `MiaService` stays at **v3**.

### 11.8 Still open

- ✅ **`QueryCalendar` — SHIPPED, §15.** ⚠⚠ §8.4's "blocked on the string-typed `startLocal`" was
  wrong about the blocker: the occurrence a question asks about **is not stored at all**, so no
  predicate over any typing of that field could have answered it (§15.1).
- **`DeleteEvent` and editing** are deliberately out (MQ-D4a): deletion by utterance carries all
  the misheard-slot risk and none of the payoff, and editing needs a disambiguation turn on top
  of a write.
- **Recurrence** ("every Tuesday at 9") — `SaveEvent` takes a `recurrenceRule`, but the grammar
  does not produce one yet.
- ⚠ The confirmation's date/time is **unlocalised** (`2026-07-16 13:00`), which is the same
  ADR-0016 debt MQ-D4 flags for the grammar itself.

---

## 12. As built — Track A, slice A1: photos by place (2026-08-12)

✅ **Merged.** **Scope:** `:file-service` + `:file-service-proxy` + `:file-service-client`
+ `:mia-service` + `:mia-service-proxy`. No wire reshape, no version bump, no relay change.

> "Show me my pictures taken in Munich" — the question that opened this track, answered with **no
> model, no geocoder and no network call**.

### 12.1 ⭐ Two reads, and the join is Mia's

The obvious implementation is a `photosAtPlace(name)` op in `:file-service`. It is the wrong one:
`:file-service` would have to know what a `Place` is, which makes the file service depend on the
place service and hard-codes one product's idea of "at" into the storage layer.

What shipped instead is two ordinary reads with the join in the planner, which is exactly the
division MQ-D2 asks for — the proxy owns the join, each service owns only its own rows:

1. **Stage one** resolves the *name* against the places the user has actually been
   (`Place.label` contains "munich") — the same `FindPlaces` chain MQ-2a already ships, reused
   verbatim via a shared `lowerPlaceSearch`.
2. **Stage two** asks `:file-service` for photos near each resolved **coordinate**.

`PhotosNear` therefore takes `(latE7, lngE7, radiusM, limit)` and no name at all. The file service
never learns that places exist.

⚠ **This is why the radius query had to be an op rather than a chain.** A radius is not expressible
with `equalTo`, `contains` or `inRange` — the three predicates the DSL has — and it is not a
predicate at all but a *ranked* distance. The same wall MQ-4 hits with nearest-neighbour search
(IN-D12), met early and in its cheapest form.

### 12.2 ⚠⚠ The honest limit, stated in the answer itself

Mia finds photos near **places you have been**. A photo taken in a city the user never dwelt in has
a coordinate but no `Place` to match it against, so it is not found.

That is a real limitation and it is not hidden: an unknown name is answered *"I don't know a place
called 'Lisbon'"*, never as an empty result. Those are different answers, and conflating them is
what makes an assistant feel like it is lying — the photo **is** there.

⚠ Rejected: falling back to the plain photo search when no place resolves. It would return every
photo the user owns in answer to a question about Lisbon, which is the confidently-wrong failure
this whole layer is built to avoid.

The explanation names what was searched around — *"Searched your photos taken around 'munich' (near
München Marienplatz, München Hauptbahnhof)"* — so the user can check the question Mia answered
against the one they asked (MQ-D1).

### 12.3 Every matching place, one fixed radius

Stage two runs against **every** place stage one matched, not the best one: "Munich" legitimately
matches a venue, a station and a district, and picking one would silently answer about a corner of
the city. Results merge and de-duplicate by path, then cap at the plan's limit.

⚠ The radius is a constant 500 m and deliberately **not** scaled by `Place.accuracyM`: that field
describes how precisely the *dwell* was located, not how large the venue is, so scaling by it would
make a poor indoor fix sweep a wider area than a clean one — exactly backwards.

⚠ Distance is `PlaceProximity.distanceMeters` (haversine), reused rather than re-derived. A bounding
box would have been cheaper and wrong: a degree of longitude shrinks with latitude, so the box is
wider than it looks and quietly admits photos that are not in the place asked for.

### 12.4 The grammar: an anchor word is required

`placeAnchor` fires only after an explicit **"in" / "at" / "near" / "around" / "from"**. A bare
leftover stays a *name* search — "photos beach" means files called beach, not photos taken at a
place called beach — and guessing between the two is the failure MQ-D4 exists to prevent.

⚠ **"from" is in that set even though it also introduces a time** ("photos from yesterday"), because
"my pictures from Munich" is how people actually ask. It is safe by construction: the residue after
the anchor has time words stripped, so a temporal "from" leaves nothing behind and the plan falls
through to the ordinary windowed photo search. Pinned by a test.

### 12.5 ⚠ The second chain is validated too

Stage two builds its own callstack and passes it through `MiaChainValidator` with the same policy.
A chain Mia assembled itself is not more trusted than the first one — the validator is the boundary,
and a boundary with an exception in it is not one. `PhotosNear.ID` joins the allowlist and the
bounded-operation list (its bound parameter is `LIMIT`).

### 12.6 What is proven

- **6 op tests** (`PhotosNearOperationTest`, over the wire): a real JPEG's EXIF parsed at upload →
  coordinate on the row → found again by radius; outside-the-radius; nearest-first ordering; the
  limit; not-an-image; and ⚠⚠ a coordinate-less photo, which must not read as `(0,0)` and match a
  query near the Atlantic.
- **6 end-to-end tests** (`AskMiaPhotosAtPlaceTest`): utterance → plan → two chains → citations,
  including the unknown-place answer, the multi-place sweep and the "a bare photo request is still
  every photo" regression guard.
- A new `exifJpegAt(lat, lng)` fixture in `:file-service-test-utils` seeds photos through the real
  upload path, so the tests exercise `readExifLocation` rather than planting the coordinate.

⚠ **Not proven:** anything on a device. Whether real photos in a real realm carry coordinates at all
is T-P4's open question (`timeline-photos.md` §11.7), and this feature inherits it exactly — a realm
whose photos have no EXIF location will answer "no photos there" forever, correctly and uselessly.

### 12.7 Format

No composite changed shape. `PhotosNear` is a **new** operation schema, so the baseline gains a line
and no version moves (HR #9's reshape rule is not triggered; `SchemaShapeBaselineEnforcementTest`
confirms "1 new type" rather than a changed one).

### 12.8 File map

| File | Change |
|---|---|
| `file-service/.../system/PhotosNear.kt` | new — the op, coordinate-only by design |
| `file-service-proxy/.../PhotosNearImplementation.kt` | new — tree walk + haversine + nearest-first |
| `file-service-proxy/build.gradle.kts` | `:place-service` (the schema module, for `PlaceProximity`) |
| `file-service-client/.../PhotosNearRo.kt` + module | the client Ro |
| `file-service-test-utils/.../ExifPhotoFixture.kt` | new — `exifJpegAt(lat, lng)` |
| `mia-service/.../QueryPlan.kt` | `FindPhotosAtPlace` |
| `mia-service/.../QueryPlanner.kt` | anchor words + routing |
| `mia-service-proxy/.../QueryPlanLowering.kt` | shared `lowerPlaceSearch`, new `lowerPhotosNear`, policy |
| `mia-service-proxy/.../AskMiaImplementation.kt` | the two-stage execution |

### 12.8b Track B built on this

B4 (`takeout-import-and-recall.md` §6) generalised the two-stage join to moments — "what did I do
in Paris", "when was I last at the gym" — and `AskMiaImplementation.placeJoin` is now shared by
both. ⭐⭐ Its stage two is the **first nested-field predicate** in the codebase
(`filter<Moment> { subject.id equalTo … }`), which works.

### 12.9 Still open in Track A

- **Photos from places you have never been** — needs a geocoder, i.e. `place-geocoding.md`'s
  reverse path run *forward* (name → coordinate). ⚖️ That is an egress decision, not a code one.
- **iOS and desktop capture location.** `readExifLocation` is JPEG-only (`ExifGps.kt`), so an iPhone
  HEIC carries its coordinate in an ISO-BMFF box this parser does not read. Desktop uploads work
  today whenever the file still has its EXIF intact.
- **Nothing renders this yet** — the answer is citations in Mia's own surface; a "photos near here"
  entry point in Drive is a separate slice.

---

## 13. As built — MQ-4: "photos like a sunset over water" (2026-08-14)

✅ **Merged.** **Scope:** `:mia-service` (a source, a plan, the grammar),
`:mia-service-proxy` (lowering, policy, the answer mapper), `mia-shared-ui` (one Settings row).
**Still no model** — `FakeInferenceBackend` all the way down.

MQ-4 has been blocked since MQ-1 on an op that did not exist. IN-4b built it; this is the
utterance-to-citation half.

### 13.1 ⚠⚠ A third grant, and it is what forced the validator to change

`MiaSource.VISUAL_SEARCH` is separate from `LABELS`: a label is something a model already wrote
down, while this runs a model *on demand* against the user's question and ranks their whole
library. Different enough that folding them would have made enabling the cheap one enable the
expensive one.

⚠⚠ **It is also the first time two sources share a service**, and that broke an assumption nothing
had written down: `policyFor` derived the validator's allowlist from *granted services*, so with
both sources on `InferenceService` a chain passes the service check on **either** grant. Revoking
visual search while keeping labels would have changed nothing the validator could see — the switch
would have been decorative at that layer.

**So `policyFor` now narrows the *operation* allowlist per grant too**, and `photosLike` drops out
when `VISUAL_SEARCH` is revoked. ⚠ Defence in depth, not the primary control: the planner already
refuses an ungranted source *by name*, which is the answer the user should get. This is what
catches a planner bug.

⚠ The general rule this leaves behind: **one grant per service was load-bearing and nobody knew
it.** The next source added over an existing service owes the same check.

### 13.2 "Like" and "of" are different questions, and there is no fallback

| Utterance | Intent | Reads |
|---|---|---|
| "photos **of** a cat" | a tag a model wrote | `MediaLabel` rows (IN-4a) |
| "photos **like** a sunset over water" | rank the library against a description | `photosLike` (IN-4b) |

⚠⚠ **Falling back from label search to visual search when the first finds nothing was deliberately
not built.** It is defensible UX and it breaks MQ-D3: the plan is what the user is *shown*, so a
silent second search would make the explanation describe something other than what ran — and it
would cross a grant boundary without saying so.

⚠ The explanation says **"Photos that look like …"**, never "of". The user is about to be shown
ranked guesses rather than rows carrying the word they typed, and the sentence is the only thing
setting that expectation before results land.

### 13.3 ⚠⚠ The refusal survives the trip

`photosLike` throws rather than returning an empty list (IN-4b §16.2), and `AskMiaImplementation`
now maps `SemanticSearchUnavailable`'s four reasons to four different sentences. Flattening them
into the generic *"I could not search your files just now"* would have undone one layer above
exactly what IN-4b won one layer below.

Each line names something the user can do — turn it on, wait for a pass, install a model. ⚠ Never
the reason token itself: `notIndexed` is our word, and an unknown reason falls back to the generic
line rather than guessing a specific one (MQ-D4).

### 13.4 ⚠ The score is computed and never shown

A hit carries no similarity. *"82% similar"* reads as a measured confidence, and it is a raw cosine
in one model's space — a number whose scale is meaningless to a user, and which `models.md` §3.7.2
shows never even reaches p = 0.5 on a real library. **The ordering is what the score is for.**

### 13.5 What is proven

- **8 end-to-end tests**, utterance → plan → validated chain → op → citations. Half are about the
  op *refusing*, because that is the half a careless mapping would have lost.
- ⚠⚠ **The validator refused the first run** — "chain is unbounded: no take and no bounding
  operation". Registering an op in `allowedOperationIds` is not enough; an op returning a list must
  declare *which param caps it*. Third op to need that, first to forget it.
- ⚠⚠ **A test seeded the wrong string and produced a confident wrong winner rather than an error.**
  The fake embeds bytes, so `"photos like a sunset over water"` reaches the op as
  `"sunset over water"` — the grammar eats the anchor *and the article*. The corrected tests now pin
  the planner's residue end-to-end as a side effect.
- ⚠ Both exhaustive `when`s over `MiaSource` failed to compile the moment the source was added —
  the planner's user-facing label and Mia's Settings row. That is the design working, exactly as
  IN-4a recorded.
- Full gate green (1900 tasks); `iosKotlinCheck` green.

### 13.6 Still open

- ✅ **Wired 2026-08-14** (`inference-service.md` §17). This line used to say production DI did not
  register the factory; it does now, against a deliberately empty backend registry.
- ✅ **Visually verified 2026-08-15 — §14.** It found six defects, five of them invisible to the
  suite.
- The grammar is three anchor words. "Photos like X" is not how everyone would ask, and the
  alternative — inferring visual intent when a description is not in any vocabulary — needs the
  vocabulary to exist first (`models.md` §3.7.4).
- Content ∧ place ("photos like a sunset, in Munich") remains unexpressible (§13.2's table is the
  reason: "like" and "in" are separate arms and only one can win).

---

## 14. Verified on a device — the first click-through of the whole track (2026-08-15)

**Scope:** an emulator pass over `master` (all nine apps installed, MQ-4 in), typing ~20 utterances
into Mia's chat and reading what came back — **and the six fixes it produced**, which shipped in the
same PR and were re-verified on the same device (§14.9).

⚠⚠ **Every slice MQ-1…MQ-4 shipped saying "not visually verified", and this is what that was
hiding.** The pipeline is sound — routing, grants, refusals and the validator all behaved as the
as-built sections describe. What broke is everything *between* the layers: two sentences that
compose into a lie, a refusal that points at the wrong switch, and a chat you cannot read while the
keyboard is open. None of it is reachable from a unit test, because every layer is individually
correct.

### 14.1 ⚠⚠ The refusal is rendered as an empty result — §12.2 undone one layer up

> *"I don't know a place called "paris", so I can't tell you when you were there. **— nothing
> matched.**"*

Both halves of the sentence are shipped code doing what it was told:

| Layer | What it does |
|---|---|
| `AskMiaImplementation.placeJoin` | returns `VERDICT_ANSWERED` + the `unknownPlace` text + **zero hits** |
| `TenshuConversationRepository.bodyOf` | appends `" — nothing matched."` to any `ANSWERED` with no hits |

§12.2 is explicit that *"I don't know that place"* and *"there is nothing there"* are different
answers and that conflating them "is what makes an assistant feel like it is lying — the photo
**is** there". The proxy kept them apart and the repository glued them back together.

⚠ It fires on **every** unknown-place path — `RecallAtPlace`, `FindPhotosAtPlace`, and (via §14.4)
any utterance that falls into the place arm by accident, which is where it does real damage.

**Fix:** the unknown-place branch owes `VERDICT_NO_MATCH`, which `bodyOf` already renders bare.
`MiaAnswer`'s own KDoc calls that verdict "a first-class outcome, not an error" — this is the case
it was for. ⚠ The general shape: **a verdict is not a synonym for "the op succeeded"**, and any
future refusal returned as `ANSWERED` inherits this bug.

### 14.2 ⚠⚠ The visual-search refusal names a switch that is already on

Asking *"photos like a sunset over water"* answers:

> *"Searching photos by what they look like is switched off — you can turn it on in Settings."*

Mia's Settings, two taps away, shows **Photo search by appearance — on**. So does every other
grant; they are all on by default (MQ-2b).

The sentence is the `NO_CONSENT` arm of `semanticUnavailableText`, and `NO_CONSENT` is **IN-D3's
inference consent** (realm *and* device, absence = NO) — a different switch, in a different app,
from `MiaSource.VISUAL_SEARCH`. §13.3's standard for these four sentences is that each "names
something the user can do"; this one names something the user has already done.

⚠ The other three arms are fine — a missing model and an unrun index are unambiguous. It is
specifically the switch-shaped reason that collides with Mia's own switch-shaped grant, because
**two independent consent mechanisms now guard one feature** and the user is shown only one of them.

### 14.3 ⚠⚠ You cannot read the answer while the keyboard is open

Send a question, and the reply lands **off the top of the screen**: the message column is panned up
by the IME, so it sits under the status bar with an empty gap where the conversation should be. The
answer only appears after dismissing the keyboard — which is not the state anyone is in immediately
after typing.

⚠ `ChatScreen` already scrolls to the newest message (`animateScrollToItem`), which is why this
looks like it should work. Scrolling is not the problem: the list is *shorter than its viewport*, so
there is nothing to scroll, and the whole column is being displaced by an inset nothing consumes.

⚠⚠ **`imePadding` does not appear anywhere in this repository**, and no app declares
`windowSoftInputMode`. Mia's chat is the platform's **first bottom-anchored text input**, so it is
the first screen where this could ever have shown up — every other surface types into a dialog or a
field the IME does not reach.

### 14.4 ⚠⚠ "my runs last week" is answered as a question about a place called "runs"

> *"I don't know a place called "runs", so I can't tell you when you were there. — nothing
> matched."*

This is MQ-D4's *"answering a different question confidently"* — the exact failure the no-match
verdict exists to prevent — and it takes **two** shipped decisions colliding to produce it:

1. `FITNESS_WORDS` holds `run` but not **`runs`**. (Also missing: `walks`, `rides`, `swims`,
   `hikes`, `swimming`, `hiking`. `workout`/`workouts` is the only pair with both forms.) So the
   fitness arm never fires and the utterance falls through.
2. The recall arm takes a **bare** residue as a place name — deliberately, because "when did I last
   visit the gym" anchors on the verb, so §12.4's mandatory-anchor rule was *not* applied here.

Measured, on the same device, one word apart:

| Utterance | Routed to |
|---|---|
| "my **run** last week" | ✅ workouts |
| "my **runs** last week" | ⚠⚠ a place called "runs" |
| "runs last week" | ⚠⚠ a place called "runs" |
| "show me my runs" | ⚠ file names containing "runs" |

⚠ The vocabulary gap is a one-line fix; the routing is the real finding. **A vocabulary miss does
not degrade to "I didn't understand" — it degrades to a confident answer about something else**,
because two arms below fitness accept any leftover text. Widening `FITNESS_WORDS` closes these four
cases and none of the next four.

### 14.5 ⚠ The calendar read gap is not a no-match, it is a file search

*"what is on my calendar tomorrow"* → **"Searched your files for names containing "calendar
tomorrow"".** `QueryCalendar` was never built (§11.8), so the utterance falls to the file-name arm,
which is the catch-all for any text the grammar cannot place.

⚠⚠ Mia's Settings advertises **Calendar** as something it reads, granted by default. The read grant
is not decorative — `CreateEvent` consults it — but there is no question Mia can answer from the
calendar, and the user cannot tell those apart from the switch. §14.4 and this are the same
structural point: **the file-name arm converts every gap in the grammar into a wrong answer instead
of an honest one.**

### 14.6 What behaved exactly as designed

- **The write tier.** *"add lunch with anna tomorrow at 1"* → *"I'm not allowed to add things to
  your calendar — you can turn that on in Mia's settings."* Default-false, refused by name, and it
  points at the switch that actually exists — the counter-example that shows §14.2 is a bug and not
  a house style.
- **Grants, all eight, rendered and switched.** "Photo contents" and "Photo search by appearance"
  are visibly separate rows (§13.1), and "Let Mia add events" is the only one off.
- **Time resolution.** "last summer" → *summer 2025*, named in the answer; "last week" likewise.
- **The device arms.** *"is the kitchen light on"* stayed a question (§10.3); *"turn on the kitchen
  light"* resolved nothing and said so, rather than inventing a device.
- **Every answer named what it searched**, which is what let this pass find the routing bugs at all
  — MQ-D1's citation principle paying for itself in diagnosis.

### 14.7 ⚠ What this pass could NOT verify

⚠ **Partly closed 2026-08-21** (`control-cross-app-open.md` §5e.6): a calendar citation cell has now
been rendered and tapped through on a device, with the realm seeded through Mia's own `CreateEvent`.
`PhotoGrid`, `PlaceCard` and the MQ-3a confirmation cell remain unverified — those still need a realm
with real content.

⚠⚠ **As written on 2026-08-15: the emulator's realm is empty, so every single answer was a refusal or
"nothing matched" — no citation cell has still ever been rendered.** `PhotoGrid`, `PlaceCard` and the tap-through to
the owning app remain unverified, as does the MQ-3a confirmation cell (it needs a resolvable Home
device) and the token round-trip behind it. Those need a realm with data, i.e. the maintainer's own
phone.

⚠ Also unverified: the History tab keeps **one row per session**, titled by the first utterance
(by design — `TenshuConversationRepository` holds one conversation per session), so MQ-D8's
deletion affordance was not exercised. Its date is unlocalised (`2026-08-15`), the same ADR-0016
debt §11.8 records for the confirmation cell.

### 14.8 ⚠ Mia's onboarding renders dark-themed with light-theme text

The three onboarding pages paint a dark surface while using the light palette's content colours, so
the headline ("Meet Mia", "Make Mia your assistant", "All set") is **near-black on dark grey** and
the body copy is barely legible. The chat screen one tap later is correctly light-themed, which is
what makes it a theming bug in the pager rather than a palette problem.

⚠ Cosmetic, and it is the first thing a new user sees.

### 14.9 The fixes, and what each one is pinned by

| # | Fix | Guard |
|---|---|---|
| §14.1 | `placeJoin` returns `VERDICT_NO_MATCH` — the sibling refusals' verdict, not `ANSWERED` | the two `aPlaceTheUserHasNeverBeenIsSaidSoRatherThanAnsweredEmpty` tests, **rewritten**: they asserted `ANSWERED` and are how this shipped |
| §14.2 | the `NO_CONSENT` line names a *separate permission* and promises no screen | `theConsentRefusalDoesNotSendTheUserToMiasSettings` |
| §14.3 | `Modifier.imePadding()` on `ChatScreen`'s root column | none — layout; re-verified on the device |
| §14.4 | plurals + gerunds in `FITNESS_WORDS`, and `isLatestSignal` stops a consumed "last" from also firing the recall arm | 6 planner tests, incl. `aBareNounWithATimeWindow_isNotAPlaceQuestion` for the class rather than the five words |
| §14.5 | `CALENDAR_READ_WORDS` → an explicit no-match ahead of the file-name arm | `aCalendarQuestion_saysTheReadIsUnbuiltRatherThanSearchingFilenames` + a guard that the *write* intent still wins |
| §14.8 | onboarding wraps in a `Surface(color = background)` | none — theming; re-verified on the device |

⚠⚠ **Each new guard was run against the unfixed code and watched to fail** — 5 planner tests, 3
proxy tests. A guard that has never been red is a guess about what it covers.

⭐ **The two rewritten tests are the interesting ones.** Both pinned the *explanation* and let the
*verdict* say anything, so both passed while the surface rendered the sentence §12.2 forbids. The
assertion was on the half that does not decide what the user reads.

⚠ Re-verified on the device, all six: the suffix is gone, the answer is visible with the keyboard
open, "my runs last week" reaches workouts, the calendar question refuses honestly, the visual-search
line no longer points at an on switch, and onboarding is legible. Full gate green (1907 tasks),
`iosKotlinCheck` green, Android smoke **PASSED** with schema lockstep v20 across all nine apps.

⚠ **Still unfixed, because they are features rather than defects**: `QueryCalendar` itself (§14.5
now says "not yet" instead of searching filenames — built in §15), and the missing inference-consent
UI that §14.2 now describes honestly (IN-3b owns it).

---

## 15. As built — `QueryCalendar`: the last named read (2026-08-15)

**Scope:** `:mia-service` (a grammar, a plan, the routing), `:mia-service-proxy` (two chains and the
fold), `mia-shared-ui` (a glyph per source). No schema or wire change; `MiaService` stays at **v3**.

> "What is on my calendar tomorrow" — MQ-D4a's read half, designed 2026-08-05 and blocked ever
> since on a finding that turns out to have been about the wrong thing.

### 15.1 ⚠⚠ §8.4 was right about the field and wrong about the blocker

§8.4 recorded that `Event.startLocal` is a **string**, so `inRange` over it answers "nothing on
tomorrow" silently — and concluded that `QueryCalendar` was blocked until the field was typed. The
finding is true and the conclusion does not follow, because **the row it would filter does not
exist**:

> A weekly stand-up created in January 2024 has exactly one stored row, dated 2024. Asked what is on
> tomorrow, no predicate over any typing of any field of that row can match — and yet the honest
> answer is "Team standup, 10:00". **The rule is stored; the occurrence is computed.**

So the typing was never the load-bearing part. What the read needs is an **expansion**, which
`:calendar-service-common` has had since CA-1: `OccurrenceResolver.resolve(series, overrides, from,
to)`, pure, already the thing the Calendar app folds with. Mia depends on that module directly, so
the plan lowers to two unfiltered bounded reads and the window is applied in the proxy — the same
division as Track A's place join (§12.1), and the reason an agenda in Mia and an agenda in Calendar
cannot disagree about what recurs.

⚠⚠ **The scan is capped at 200 series, because `MiaChainValidator` refuses a larger bound**
(`maxRows`), and **no ordering makes truncation safe**: a series stores its *first* occurrence, so
"newest 200" would drop precisely the old weekly meeting that still happens tomorrow. The answer
therefore *says* when it saturates — "only your first 200 events — there may be more" — rather than
serving a short agenda in the voice of a complete one. ⚠ The real fix is §12.1's: a dedicated op
that bounds by *window* on the calendar's side, or a per-source row ceiling in the policy.

✅ **CLOSED 2026-08-24 (§17)**, by the first of the two options this paragraph names.
`queryOccurrences` runs the scan inside `:calendar-service`, where no chain bound applies, and caps
**occurrences in the window** — a quantity that *is* safely orderable. The reasoning above is kept
because it is what produced the fix, but the cap it describes no longer exists.

⚠ Inherited from the resolver, and worth knowing: an occurrence an override moves *into* the window
is missed (the expansion runs before the override applies). `OccurrenceResolver` documents it; Mia
does not make it better or worse.

### 15.2 ⚠⚠ A noun is not an instruction — "do I have a meeting tomorrow" was a write

`CREATE_EVENT_WORDS` held **`meeting` and `appointment`**. They are nouns, and a noun appears in the
question as readily as in the command, so *"do I have a meeting tomorrow"* routed to `CreateEvent`
— which, having no clock time to work with, asked the user **what time to put the new event at**.

That is §10.3's rule one source over: *"is the kitchen light on" must stay a question — the **verb**
is what makes an utterance an instruction*. It matters more here than it did there, because a device
command is confirmed and reversible while a misheard event persists, syncs, and fires a reminder
weeks later (MQ-D1's amendment).

⚠ The nouns moved to the *read* vocabulary and the write keeps only verbs. Accepted cost: a verbless
"meeting with anna tomorrow at 3" no longer creates anything.

### 15.3 The third time grammar, and why it could not be either of the first two

§11.2 established that a scheduling grammar looks FORWARD and a search grammar looks BACK. A
calendar *read* is a third thing:

| Grammar | Question | Resolves to |
|---|---|---|
| `TimeGrammar` | "what did I do **last** Saturday" | a past instant window |
| `EventTimeGrammar` | "lunch **Saturday at 1**" | one future `LocalDateTime`, **null without a clock time** |
| `AgendaTimeGrammar` | "what's on **Saturday**" | a future range of whole **days** |

Reusing the scheduling grammar would have refused every agenda question that named no hour — which
is nearly all of them. Reusing the search grammar would have answered about the Saturday that has
gone.

⚠ Three deliberate calls inside it: **"this week" starts today**, not Monday, because an agenda is
what is left to happen; **a weekday includes today** (unlike scheduling, where "see you Tuesday"
said on a Tuesday cannot mean five minutes ago); and **"yesterday" resolves**, because the calendar
keeps its past — it is the *default* that leans forward, not the vocabulary.

⚠ **The default is named, never silent**: an utterance with no time word answers for *today* and the
explanation says "Looked at your calendar for today". A default the user cannot see is
indistinguishable from a wrong answer.

### 15.4 ⭐ The first citation cell this track has ever rendered — and it drew a file icon

Every earlier slice shipped against an empty realm (§14.7), so `CitationsCell` had never displayed a
row. It turns out to have drawn **`InsertDriveFile` for every kind** — a place, a workout and a
device all cited as documents — while `Hit.kind`'s own KDoc says it exists "so the cell can pick a
glyph". Six glyphs now, one per source; an unknown kind falls back to the document icon rather than
an error, because a newer proxy citing a source this build does not know is not a broken row.

### 15.5 What is proven

- **8 end-to-end tests** through a real proxy (`AskMiaCalendarTest`), the load-bearing one being
  `aRecurringSeriesOccursTomorrowEvenThoughNoRowSaysSo` — a 2024 row answering a question about
  tomorrow. ⚠ A suite that only seeded one-off events would pass against an implementation that
  cannot answer the question at all.
- **8 planner tests** over the new grammar, including the forward weekday, the today-anchored week,
  "yesterday", and both halves of §15.2 (a question reads, a verb still writes).
- ⚠⚠ **Every guard was watched to fail first**, including a deliberate probe that removed the
  override chain and confirmed only `anOverrideMovesTheOccurrenceItNames` went red.
- ⭐ **Device-verified**, which for this track now means before the PR rather than after: two events
  created through Mia's own confirmation cell (itself rendered for the first time), then read back —
  "tomorrow" cites both in time order with their times, "today" answers empty, and *"do I have a
  meeting tomorrow"* is a read.

### 15.6 Still open

- **Recurrence in the write** ("every Tuesday at 9") — `SaveEvent` takes a rule, the grammar still
  produces none (§11.8).
- **The 200-series cap** (§15.1), and the dedicated op that would remove it.
- ✅ **The tap-through — DONE 2026-08-21** (`control-cross-app-open.md` TT-1c). `MiaHit.reference`
  and `kind` are gone; a hit carries a `DataId`, the calendar one addressing the **occurrence**
  rather than the series, and tapping it opens Calendar's editor on it. `MiaService` → v4.
  ⚠⚠ Only the occurrence type is routed — a file, place, moment, workout or device citation is
  tappable and answers `NO_HANDLER` honestly until TT-2.
- ⚠ Times are unlocalised (`09:00–10:00`), the same ADR-0016 debt as MQ-3b's confirmation cell.
- **Hidden and sensitive calendars are both included.** Visibility is per-device *display* state
  that the schema deliberately does not carry (`Calendar`'s "what a calendar *is* syncs; how *this
  device* displays it does not"), and `sensitive` is a **lock-screen** control — its subject is who
  can see the phone without unlocking, not who is asking a direct question inside an unlocked app.
  ⚠⚠ A voice or lock-screen surface would have to revisit exactly this, and that is where the flag
  starts to mean something for Mia.

---

## 16. As built — MQ-5a: a model may choose the question (2026-08-15)

**Scope:** `:inference-service` (a capability, an op, two composites), `:inference-service-proxy`
(the implementation and the fake's classifier), `:mia-service` (`MiaIntent`, a slot/route split),
`:mia-service-proxy` (the seam). **No model anywhere**, and no schema version moves — three new
types, none reshaped (§16.7).

⚠⚠ **This is MQ-5's *seam*, not its payoff.** The phase as written wants "a model as planner
(utterance → `QueryPlan`) and optionally answer phrasing", plus the optional *model → chain*
long tail. What is built is the first of those, narrowed to the one decision a model can make
without being able to say anything Mia does not already implement: **which question was asked.**
Answer phrasing is not built, chain generation is not built, and the production backend registry is
still empty — so on a real device nothing about this is visible, which §16.6 treats as the result
rather than as an excuse.

### 16.1 ⭐⭐ The model picks the question; the grammar still fills in the slots

The division that makes the rest of the design fall out:

| | Decided by | Why |
|---|---|---|
| *Which* question this is | a model, when consented and confident | judgement, and the thing a vocabulary is worst at — §14.4's "kayaking" is in no word list and never will be |
| *What* the question contains | the deterministic grammar, always | a time expression is code and localisation-bearing (MQ-D4), a place needs its anchor (§12.4), an agenda window leans forward (§15.3) |

⚠⚠ **Slot extraction was never a candidate for generation and the reason is not conservatism.** A
misread intent is visible — the answer's explanation says what was searched, and the user reads it
(MQ-D1). A misread *slot* is invisible: "last week" quietly resolved to the wrong week produces a
confident, plausible, wrong answer with a perfectly good explanation attached. The failure modes are
not comparable, so the two halves get different machinery.

⭐ It also means the model's output is one token from a closed set, which is what makes §5's
"constrained decoding into the composite shape, never free-text parsed into one" cheap enough to
be *true* rather than aspirational.

### 16.2 ⚠⚠ Writes are not on the menu

`MiaIntent` has eleven entries and they are all reads. `CreateEvent` and `ControlDevice` exist, work,
and stay exactly where §15.2 and §10.3 put them: behind a **verb**, in the grammar, auditable to the
word.

- A model is never *shown* a write (the candidate list is built from `MiaIntent.selectable`), and
  `ClassifyIntentImplementation` refuses any id outside the list it was handed — so "pick a write"
  is not a thing that can be expressed, rather than a thing that is checked afterwards.
- ⚠ The asymmetry is deliberate and worth restating: a misread read produces a wrong *answer*, seen
  and ignored; a misread write produces a row that syncs, or a light that changes in a room the user
  is not in. MQ-D7's confirmation cell would still stand in front of it — but a cell that appears
  because a model guessed is a different product decision from one that appears because the user
  said "add".

### 16.3 ⚠⚠ Only two plans may be overruled, and the catch-all is the interesting one

The model is consulted when the grammar returns `NoMatch` — and when it returns the **last-resort
file-name search**, which is the same admission wearing a different hat.

That arm is where this track's two measured wrong answers came from: *"my runs last week"* (§14.4)
and *"what is on my calendar tomorrow"* (§14.5) both ended in "Searched your files for names
containing …", because any leftover text becomes a filename query. `QueryPlan.FindFilesNamed` now
carries `viaCatchAll` so the two cases are distinguishable, and a name search reached through a
positive signal — a quoted phrase, an explicit "file" or "document" — is never second-guessed.

⚠ Every other plan stands whatever a model would have said. A photo word, a device noun, an anchored
place or a resolved time window is evidence; overturning it with a guess would make MQ-5 a
*regression risk* for the questions that already work, which is exactly what MQ-D9 forbids.

### 16.4 The fallback is the answer, so a refusal has nothing to say

`ClassifyIntent` returns an **empty list** for every failure — no consent, no backend, no opinion, an
unrecognised id — where `PhotosLike` throws a typed `SemanticSearchUnavailable` with four
actionable reasons (§13.3).

⭐ The rule that separates them, worth having: **throw when the user loses a capability, return empty
when only the implementation changes.** Semantic search has no fallback, so its refusal *is* the
answer and must name something to do. Intent classification has a perfect fallback — the planner
that has answered every question on this track since MQ-1 — so a sentence about models would be
about us, not about the user's question.

⚠ A wrong choice costs nothing either: `planForIntent` returns `null` when the utterance cannot fill
the chosen intent ("photos at a place", in a sentence naming no place), and the grammar's own answer
stands rather than being replaced by a `NoMatch`.

### 16.5 The boundary did not move

MQ-D10 promised that a model-generated plan would meet the same four checks as a grammar-generated
one. It does, and the wiring is unchanged: `planForIntent` applies the **same grant check** in the
same shape, so a model-chosen source the user revoked is refused **by name**; the plan then lowers to
the same callstack and meets the same validator. Nothing about the model path touches
`MiaChainValidator`.

⚠ **Consent is IN-D3's two switches, not a new Mia grant.** `MiaSource` grants govern *sources*;
this is a *capability* (`InferenceCapability.QUERY_PLANNING`), realm **and** device, absent = no.
Adding a twelfth Mia switch would have implied the planner was a source of data, which it is not.

⚠ **The call is a direct dispatch, not a chain** — and it has to be, because a chain is what a plan
*compiles to* and this call is what produces the plan. It goes through `dispatchOperationForResult`,
the result-keeping sibling of the helper MQ-3a/3b's direct calls already use for the reason §11.5
records twice over.

### 16.6 What is proven, and what a device shows

- **8 end-to-end tests** with a scripted backend: consent gates the call, either switch alone stops
  it, a working parse is never sent, the candidate set contains no write, an invented id is
  discarded, an unfillable choice falls back rather than refusing, and a revoked grant refuses by
  name.
- ⚠⚠ **Three guards were broken on purpose to confirm they are load-bearing.** Two went red
  (`aWorkingParseIsNeverSecondGuessed`, `theCandidateSetContainsNoWriteIntent`). The third —
  deleting Mia's *own* re-check of the returned id — left every test green, because the op-side
  allowlist catches it first. **That line is recorded in the code as untestable-while-redundant**
  rather than quietly kept as if it were covered.
- ⚠⚠ **On a device, nothing changed, and that is the requirement.** The production backend registry
  is empty (IN-3b), so "kayaking last week" still reaches the file-name search and the calendar read
  still works — MQ-D9's "every intent keeps working with it off", verified rather than assumed.
  Wiring a fake into production DI to make a demo would have broken the standing rule that fakes are
  opt-in and never a default.
- ⚠ **Every quality claim would be the fake's**, and the fake is a bag-of-words tie-break over the
  candidates' own descriptions. It proves plumbing. Whether a real model chooses well is
  unanswerable here, exactly as `models.md` §3.7 says of retrieval.

### 16.7 Format, and one trap on the way

Three new types (`IntentCandidate`, `IntentChoice`, the `ClassifyIntent` op), nothing reshaped, so
**no version moves** — the same call §12.7 made for `PhotosNear`. The baseline gains three lines.

⚠⚠ **`IntentCandidate` is a *parameter* type, and forgetting to register it failed 14 tests with
`Schema not found`.** A composite that only ever travels *into* an op is as much a wire shape as one
that comes back, and it is the easier one to miss precisely because no return type mentions it. The
module now registers both halves with a note saying so.

### 16.8 Still open — most of MQ-5, deliberately

- **A model.** The registry is empty; IN-3b owns it. Until then this path cannot run outside a test.
- **Answer phrasing** (§5's "optionally as answer phrasing") — not built. ⚠ It is the half that
  touches MQ-D1's citation property directly: generated prose must accompany real rows and never
  replace them, and there is no reason to take that risk before there is a model whose prose could
  be judged.
- **Model → chain directly** (§5's optional payoff for MQ-D3 and MQ-D10) — not built, and it should
  stay last. The intent seam gives a model *one token* of expressiveness; a generated chain gives it
  a tree, and the validator's honest cost (a callstack is inspectable but not legible) is a
  different proposition when a human is no longer the one who wrote it.
- **Slot extraction by model** — deliberately never, on §16.1's reasoning, unless someone
  demonstrates the invisible-failure problem is solved.
- ⚖️ **The threat-model amendment §4 names** — prompts as an in-use plaintext class. ⚠ Nothing here
  creates a new stored artifact: the only content crossing the call is the utterance, which is
  already device-local conversation history under MQ-D8, and the backend is in-process. A **remote**
  binding (IN-D1 allows one) would change that and owes the A5-family amendment before it ships.

---

## 17. As built — the series cap is gone: `queryOccurrences` (2026-08-24)

§15.1 shipped the calendar read with a ceiling it called *"the honest limit of this slice rather
than a considered number"*: the scan stopped at **200 event series**, because the chain validator
caps rows and the rows here were series. This closes it, along the line §15.1 itself pointed at —
*"a dedicated op, which can bound by window on the calendar's own side"*.

### 17.1 ⚠⚠ The bound moved from the input to the output, and that is the whole fix

The old shape had no safe truncation available to it. **A series stores its *first* occurrence**, so
every ordering a scan could pick drops the wrong thing: newest-first drops the 2024 weekly stand-up
that still happens tomorrow, store order drops whatever happened to be written last. The cap was on
a quantity that has nothing to do with the question.

`queryOccurrences(fromDate, toDate, limit)` runs the scan **inside `:calendar-service`**, where no
chain bound applies, and caps **occurrences in the window** instead. Those are naturally few, and
the resolver already sorts them by effective start — so truncation keeps the soonest and drops the
furthest away, which is what a window question means.

⚠ The expansion did not move house, it moved *down*: `OccurrenceResolver` still does it, still in
`:calendar-service-common`, still the same code the Calendar app and CA-5's reminder scheduler fold
with. An agenda in Mia and an agenda in Calendar still cannot disagree about what recurs. What went
away is Mia's copy of the plumbing around it — including the second chain for overrides, and the
"an override read that fails degrades to the un-overridden series" branch that existed only because
that chain could fail on its own.

### 17.2 ⭐ The regression test had to be built to fail, and it was

`anOldWeeklyMeetingSurvivesACalendarOfHundredsOfSeries` seeds a 2024 weekly stand-up and asks about
tomorrow — behind **250 decoy series, seeded first**.

⚠⚠ **The seeding order is load-bearing.** The old chain read `events.take(200)` in *store* order, so
a stand-up seeded first would have survived the cap by luck and the test would have passed against
the very code it exists to condemn. Verified the honest way: with a `take(200)` reinstated inside
the op it fails with `expected:<[Team standup]> but was:<[]>` — the defect's exact signature, an
event silently vanishing — and nothing else in the file moves.

### 17.3 ⭐ Saturation became a fact instead of a guess

The old answer compared `series >= cap` and therefore said *"there may be more"* at **exactly** the
cap, when there were not. The chain now asks for **one more than it will show**, so `size > limit`
is a real observation. Two tests separate the cases, and each was watched to fail against the
other's implementation.

⚠ It is also a much rarer sentence now: the cap counts occurrences *in the window*, not series in
the realm, so a normal agenda cannot reach it.

### 17.4 ⚠⚠ `resolvedEventOccurrence`, not `eventOccurrence` — the id was already taken

`OccurrenceId.TYPE` **is** `tenshu/calendar/eventOccurrence`: the `DataId` **type** a Mia citation
addresses and `HandlerTable` routes on (`control-cross-app-open.md` CO-D5). Registering a composite
schema under the same string would have put two unrelated things in one namespace, where a lookup
can resolve to the wrong one. The wire twin of `ResolvedOccurrence` is therefore
`ResolvedEventOccurrence` / `tenshu/calendar/resolvedEventOccurrence`.

⚠ It mirrors `ResolvedOccurrence` **field for field** rather than carrying only what Mia reads. This
is a *wire* shape, so narrowing now and widening later is a positional reshape under House Rule #9 —
a version bump and a migration to add a field the second consumer could have had for free. Extra
fields cost bytes per call and nothing else. ⚠ DTO only, no `Ro`: it is never stored and never
projected, the `CapturedPhoto` precedent.

### 17.5 ⚠ `CalendarService` → v5, following the module's own rule

`:calendar-service-common` already records the reasoning twice (CA-5's added types, CA-6's added
*operation*): nothing is reshaped, so there is nothing to migrate, but the bump turns *"an old
Control does not know `queryOccurrences`"* from a cryptic `Operation not found` on the user's first
agenda question into the announce gate's typed **update Control**. ⚠⚠ The empty
`schemaMigration(…, fromVersion = 4) {}` is still mandatory — a bumped service with no registered
chain sits below its own derived floor and the store-open gate **wipes** rather than migrates.

`SchemaShapeBaseline` was regenerated: two **new** types, and no field of any existing composite
moved.

### 17.6 What is proven, and what is not

Proven: the op (7 tests — expansion, the 250-series case, earliest-first truncation, overrides,
window exclusion, an inverted/unparseable window returning nothing rather than everything, a
non-positive limit doing the same) and Mia's read through it (11 tests, the 8 that predate this
change unmodified and passing). Full gate green — 108 test tasks, 1408 classes.

⚠⚠ **Not proven: nobody has asked a real calendar this question on a device.** §14.7's list is
unchanged. The number that would actually exercise this — a realm with hundreds of event series —
is exactly the realm nobody has tested against, which is how the cap survived §15 in the first
place.

⚠ Still open from §15.6, untouched: recurrence in the write, unlocalised times, and the
`OccurrenceResolver` limit this op inherits — an occurrence an override moves *into* the window is
missed, because the expansion runs before the override applies. That one is Calendar's, not Mia's.

## 18. As built — P0g: a cited photograph must still exist (2026-10-02)

`media-processing-pipeline-sweep.md` F12 / P0g, Mia's half. A `MediaLabel` row and a `photosLike`
vector are keyed by path and **outlive their file**: nothing deletes a label on delete, labels sync,
and trashing a photo *moves* it to `/Trash/<id>-<name>`. So "photos of a cat", and "photos of Anna"
over a name the user typed, cited deleted and trashed photographs on every device, and the tap
opened a dead target.

### 18.1 ⚠⚠ A filter on the read, never a delete

`AskMiaImplementation.liveCitations` runs for `FindPhotosOfLabel` and `FindPhotosLike` only. It
drops a hit unless a `File` row exists at its path **and** the path is not under `/Trash`
(`isTrashedPath`). Nothing is written and no row is removed, so Mia stays read-only (MQ-D10). A
restored photo is cited again on the next question, with no re-index. Deleting the rows themselves
belongs to P0f-b and P10 (D13), not here.

The existence read is one chain per 200 candidate paths (`QueryPlanLowering.lowerLiveFiles`:
`traverse · path oneOf chunk · take(chunk) · select { path }`). Its reply is bounded by the chunk,
not by the realm. ⚠ The walk is still O(realm), like Drive's own name search; `oneOf` narrows what
crosses the wire, not what is walked. Trashed paths are settled without a read.

⚠⚠ **It runs under its own fixed `EXISTENCE_POLICY`, not under the user's FILES grant.** The read
answers a yes/no about paths the label or visual-search grant already disclosed. Gating it on FILES
would make every label hit unverifiable for a user who revoked file names, and
`theLabelGrantIsSeparateFromTheFilesGrant` pins that those two grants are independent. The policy is
separate from `POLICY` so that `oneOf` is not added to what a *planned* chain may say.

### 18.2 ⚠⚠ "Could not look" is UNAVAILABLE

A failed or refused existence read, or a failed widening round (§18.3), answers
`VERDICT_UNAVAILABLE` with the generic "could not search" line, the same as a failed main chain.
Citing the unverified rows would put the dead targets back. An empty `ANSWERED` renders as "nothing
matched" (§14.1). ⚠ A partial list from an earlier round is **not** returned either: a short list in
the voice of a complete one is the failure §15.1 named.

### 18.3 ⚠⚠ A stale row must not hide a live one: the chain widens

The label chain is `filter { contains } · take(limit)`, and `photosLike` takes `limit`. Both count
**rows**, so 20 deleted cats ranked first used to fill every slot, and the live cat behind them was
never read. When the verified answer is short of `limit` **and** the chain came back full (so more
rows may exist), the chain re-runs with `take × 4`, capped at `MAX_ROWS` (20 → 80 → 200). Each round
verifies only paths it has not already settled, and the first `limit` live hits are kept. In the
common case the first chain comes back short of its `take`, so it already holds every match and no
extra round runs. ⚠ The `__indexed` marker's slot cost (§14.3) is recovered by the same loop, as a
side effect. ⚠ Past 200 rows of stale matches the answer can still come back short. That is the
validator's row ceiling, and the real fix is P0f-b / P10 deleting the rows.

### 18.4 What is proven

`AskMiaLiveCitationTest` (8): a deleted photo's label is not cited and its live neighbour still is;
a trashed photo's label is not cited until it is restored; a label keyed on a `/Trash/…` path is
not cited; a typed name on a deleted photo is not cited; the same deleted and trashed-then-restored
cases for `photosLike`; 20 stale rows ranked first do not hide the live one; a failed existence read
is UNAVAILABLE, never an empty ANSWERED. The fixtures are the real upload, move and delete ops, a
real `MediaIndexer` pass and the harness's real `EmbeddingStore`. `AskMiaLabelSearchTest` and
`AskMiaVisualSearchTest` now upload the photographs they label; before this change they labelled
paths that had no file.

⚠ Not proven on a device. `photosLike`'s own filter (People and the Photos *like this* surface)
is P0f-b's, through Control's DI, and is smoke-gated.

## 19. As built — Mia on iPhone: a route-1 runtime, read-only (2026-10-02)

MQ-D2 named the route (*"in-process on iOS route-1"*) and nothing built it: `mia-ios` ran over
`MockConversationRepository`, whose canned photo grid, place card and *"Turning on the sofa
lamp"* read as the user's own. The iOS proxy has served `:mia-service` since the same day
(§7.7), so what was missing was the app's half.

### 19.1 What runs

- `MiaLocalRuntime` (`tenshu-sdk-ios`) — the Calendar shape over `FirstPartyLocalRuntime`:
  client id `app.tenshu.mia.ios`, its own Keychain bucket, `install` / `rehydrateSharedReplica`
  / `deprovision`. ⚠ *(2026-11-05: `rehydrateSharedReplica` is deleted with the iOS file store,
  `ios-shared-store-sqlite.md` B.22.9.)* `askMia` is answered in Mia's process: planner, lowering, validator, reads.
- `MiaSurface` defaults to `NotConnectedConversationRepository` (commonMain, tested) and is
  swapped to `TenshuConversationRepository` on install. ⚠ No mock is reachable from it.
- `mia-ios`: `MiaShippingRoot` puts `ControlSetUpGate` at the root, **ahead of onboarding**
  (ADR 0022 §Decision 6). ⚠ The app had no entitlements at all while it was mock-backed; with
  none there is no Keychain and the gate could never have seen provisioning. The device file
  carries the shared App Group too: S0 makes a device build without it refuse to open a store.
- ⚠ Control needed no change. Route-1 provisioning is the one shared `RealmKeyMaterial` blob,
  not a per-app registration, and `BundledAppCatalogue` carries no iOS binding on purpose.
- ⚠ The SwiftUI chat had no citations cell — it rendered the mock's three shapes only — so
  every real answer would have drawn an empty bubble. Added, inert (no opener is wired).

### 19.2 ⚠⚠ No action is offered, and that is decided in planning

Maintainer decision: the first slice answers and does not act. The reason is not taste — an
iPhone never executes a routine (RE-D13), so *"Run Good morning"* was a cell whose tap is
guaranteed to fail, and `TenshuConversationRepository.confirm` rendered that failure as *"I
can't reach Control right now"*, which is false twice over. Device control and event creation
are withheld with it until each is decided for the platform.

`miaServiceProxyImplementationFactory` takes a **required** `actionOffer: MiaActionOffer`.
Android, desktop and `:system-test-utils` pass `OFFERED`; the iOS `PlatformProxyModule` passes
`WITHHELD`, and `MiaActionOfferWiringTest` pins all three by source text (the root build skips
the iOS test compile for `-proxy` modules, so nothing else can see that line).

Under `WITHHELD`, `QueryPlanner.plan` returns `QueryPlan.ActionWithheld` for the three action
intents, at the intent and before any slot is read:

- **Nothing is resolved, so no token exists.** The refusal is not a hidden button over a live
  confirmation; `MiaActionRegistry` stays empty. That is the difference from hiding the cell
  in Swift.
- **It does not depend on whether the thing exists.** *"turn off the kitchen light"* gets the
  same sentence with or without such a lamp — otherwise a typo would answer *"I couldn't find
  a device called …"* and invite another spelling of a request that cannot succeed.
- **The grammar's teaching refusals are withheld too**: *"I can add that once you tell me
  when"*, *"Which routine should I run?"*, *"I can turn something on or off, but not toggle
  it"*. Each is the offer in another form.
- **`ActionWithheld` is its own plan, not a `NoMatch`**, because a `NoMatch` may be overruled
  by a model (§16.3) and *"turn off the kitchen light"* answered with the light's state is
  the confidently-different answer this layer exists to prevent.
- **The verdict is `NO_MATCH`, never `ANSWERED`** — an empty `ANSWERED` renders as *"… —
  nothing matched."* (§14.1), which would say the lamp does not exist.
- ⚠ **Reads over the same nouns are untouched**: *"is the kitchen light on"* and *"what's on my
  calendar tomorrow"* plan exactly as they do on Android.

What a user is told, e.g.: *"I can't switch anything on or off from this device yet — here I
can only look things up. I can tell you what state something is in: “is the kitchen light
on”."* ⚠ *This device*, not *iPhone*: the planner is common code and the runtime is an iPad's too.

⚠ `mia.grant.*` is unchanged and still read per question. It is a preference Mia honours, not
containment of the Mia process — on iOS route 1 that process holds the realm keys (threat model
A39 / A4).

### 19.3 ⚠⚠ "I can't reach Control" is false in-process

`TenshuConversationRepository` said it for any turn that could not be asked. Over Binder or a
socket that names the cause the user can act on; in-process there is no Control channel to
lose. The sentence is now a constructor parameter (`couldNotLook`), defaulting to the Control
wording, and `MiaLocalRuntime` passes its own: *"Something went wrong on this device, so I
couldn't look that up. Nothing was searched."*

⚠ Mia has **no reachability engine and no banner** on iOS, unlike its siblings. The shared
banner's sentence has the same problem, and Mia has no screen that goes on showing cached rows
for a banner to sit above.

### 19.4 What is proven, and what is not

`QueryPlannerTest` (+3) and `AskMiaActionOfferTest` (4): each action utterance is refused on a
withholding runtime **and** confirmed on an offering one, over one fixture in which all three
actions resolve — so the refusal is the runtime's and not the fixture's; the registry holds no
token; the refusal does not depend on the device existing; the read over the same lamp still
answers. `NotConnectedConversationRepositoryTest` (4). `MiaActionOfferWiringTest`.
`MiaLocalRuntimeTest` (5, iOS simulator) runs the **real** iOS proxy graph: a read is answered
in-process, and the three action utterances are refused with nothing to tap.

On a device (iPhone, Control paired, 2026-10-02): the gate passed, onboarding showed, and the
chat shell mounted over the installed runtime without a crash. ⚠ That build predates the App
Group entitlement, so it ran on an app-local replica; a build carrying the group has not been
installed, because S0 forbids it until its four prerequisites exist. The set-up screen was seen on a simulator, where nothing is provisioned.

⚠⚠ **No question has been asked on a real device** — nothing here can type into one. Still
owed there: a read answers with citations; an action utterance gets the sentence above and no
button; backgrounding, adding a photo in Drive and returning shows it; and the set-up screen
itself on a phone whose Control is not set up.

### 19.5 Out of scope, deliberately

- The grants UI on iOS — `MiaSettingsView` is still "Coming soon", so on an iPhone the grants
  are whatever another device set.
- Citation taps — `MiaSurface` passes no opener; the rows are inert rather than failing.
- Durable history (MQ-D8), visual search (no model backend on iOS), any action.

## 20. As built — Mia on the web: the planner in a tab, read-only (2026-10-28)

`web-app-suite.md` Wave 4, Mia run A (§4.A there): the SDK boundary and the bridge. No app yet.
Decided by the maintainer (`web-app-suite.md` §8 item 8): **the deterministic planner only**, as
natively.

### 20.1 The iOS route-1 shape, again

The web runtime is in-process like iOS route 1, so it mirrors `MiaLocalRuntime`: `MiaPlugin`
(`tenshu-sdk-js`) maps the **native** `askMia` from `miaServiceProxyImplementationFactory`. So the
grammar, lowering, validator and reads all run in the tab. Every read goes through a plugin the web
already had (F7): files, timeline, health, place, calendar's `queryOccurrences`, and the synced
device and label rows that the one `RelationMaintainer` keeps.

- ⚠⚠ **`MiaActionOffer.WITHHELD`**, as on iOS (§19.2): an action utterance answers `noMatch` and
  resolves nothing. ⚠ In addition, `confirmMiaAction` is **not mapped**. It is the only path from
  Mia to `IssueDeviceCommand`, `SaveEvent` and `RunRoutineNow`. ⚠⚠ A test of this must assert
  the WITHHELD *sentence* over a seeded device and routine (`MiaWithheldFixture`): with no rows,
  "no such device" / "no such routine" is also `noMatch`, so a verdict-only check stays green with
  WITHHELD dropped.
- ⚠ **No model.** No inference op is registered in a tab, so `classifyIntent` finds no
  implementation and the grammar's plan stands (§16.4's "no opinion"). `photosLike` is absent too,
  so a visual-search question answers `unavailable`, never an empty `answered`.
- ⚠ The could-not-ask sentence is iOS's (§19.3), not "I can't reach Control": the proxy is the page.

### 20.2 The conversation is re-implemented, not compiled

`:mia-shared-logic` has no JS target (House Rule 5), so `MiaBook.kt` is a second keeper of what
`TenshuConversationRepository` keeps:

- in memory, one conversation per session, titled by its first question (MQ-D8, §7.6);
- signing out or locking clears it, the twin of `deprovision`. ⚠ The apex's lock keeps the
  runtime, so the clear also voids every ask made before it, queued or in flight
  (`error_mia_session_cleared`). Otherwise the pre-lock question would reappear after unlock.

A turn pairs the question with the answer, so a page never splits them. Turns page newest first over
a keyset cursor. The verdicts are native's three, unchanged. ⚠⚠ A refusal (a revoked grant, a
withheld action) is `noMatch`, the same as "not understood". The explanation tells them apart, and
the web adds no verdict of its own (decided for run B, `web-app-suite.md` §8 item 11). The grants are
computed exactly as `readMiaGrants` / `canWrite` compute them.

### 20.3 Run B: the app, and the switches (2026-10-28)

`web/mia` (`web-app-suite.md` §4.B). ⚠ The grants became **switchable** (§8 item 10):
`Mia.setGranted` / `setWritable` write native Settings' `mia.grant.*` rows through the same
`PreferenceService.set`, answering with the set read back. A revocation in a browser is a revocation
everywhere, and the next question needing the source is `noMatch` naming it — `MiaWebContractTest`
pins that, and that the grant row is the only write. The app renders `noMatch` as the explanation
alone, never as a result list (§14.1). Threat model: A39's web amendment.

Pinned by `MiaWebContractTest`; `NoBackgroundWorkInATabTest.askingMiaInstallsNothing`.
