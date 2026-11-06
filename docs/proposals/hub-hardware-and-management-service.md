    # Tenshu Hub — Hardware Basis, Prototyping Plan & Hub Management Service Stub

**Status:** Prototype track **H1–H4 all implemented** (2026-07-24; see the as-built notes in §13) — the `:hub` module, `:hub-management-service{,-common,-client,-proxy}`, and the systemd deploy unit all exist. ⚠ §8's `HubManagementService` stub is superseded by the platform-native as-built shape (§13 H4 / the note at §8): synced `HubStatus` / `HubCommand` rows, not remote operations. The hardware basis below remains intent, not a committed design.
The Hub is a vision-doc feature (`whitepaper.adoc` §Mia; `milestones.md` §"Tenshu Hub").
This proposal records *intent* — a hardware basis, a prototyping sequence, and a first-cut
service interface — so the next person picking this up has a starting shape. It is **not**
a committed hardware design, and the `HubManagementService` interface in §8 is a stub, not
a frozen contract.

**2026-07-23 update (maintainer discussion):** prototype hardware is in hand — a
Raspberry Pi 5 with a Thread/Zigbee radio and a 1 TB NVMe. Three decisions landed and are
specified in the new sections below: **§11** headless pairing (resolves the §10 bootstrap
open question: *no* local bootstrap surface, *no* internet-facing listener — a printed
static-key variant of the offer flow), **§12** relay backup = a full-replica realm device
(riding the offline keeper; bounded-store-cache readiness assessed), **§13** the H1–H4
prototype phasing that sequences §7 steps 1–3 into shippable slices.

**Scope:** the always-on home node that runs a headless Tenshu proxy. Concretely this
proposal argues for (a) prototyping on commodity hardware before any custom board, (b)
keeping the server on KMP so it shares code with Control, (c) a new module set
`:hub-management-service` + `-common` / `-client` / `-proxy` / `-test-utils` symmetric with
the existing services, and (d) the `HubManagementService` interface so the node is
administered from any realm device rather than from a local UI it doesn't have.

This proposal does **not** design the smart-speaker audio pipeline, the IoT bridge, or the
expansion-module electrical interface — those are listed as dependencies in
`milestones.md` and get their own docs when their software roles are real.

---

## 1. What the Hub is (recap)

From `milestones.md` §"Tenshu Hub": a small always-on home node — a *minimal home server* —
that runs a Tenshu proxy and is the centre other home devices connect to. It is **not** a
smart speaker; it is the hub a speaker (or several) plugs into, alongside IoT devices, the
user's phone, and the user's other realm devices. The shape is modular and **stackable**:
a small base unit grown with expansion boxes (disk, AI/ML) to fit the household.

It earns its keep across four roles, and which ones a given user wants varies:

1. **Home trust root / always-on proxy** — a resident proxy that is always reachable and
   never someone else's hardware; the natural brokering endpoint for the External Proxy
   Connection pattern (the iOS/web "proxy on another device" can be *this* device).
2. **Ambient compute hub** — speakers capture audio, the Voice App Service runs on the Hub.
   Inference is **tiered** (§5): a small always-on NPU in the base unit handles the light,
   constant tier (wake-word, VAD), and an optional AI co-computer expansion box handles the
   heavy, bursty tier (STT, LLM) on-device per Minimal-Information.
3. **Smart-home hub** — bridges IoT devices into the realm so Routines automate locally.
4. **Storage + relay resilience** — owned, growable disk; and a store-and-forward buffer
   for the realm's ciphertext `SyncBlock`s, riding the same storage SPI the
   relay-persistence work introduced ("a relay you own a slice of," not a parallel
   implementation).

The through-line is *"one modular box you grow,"* not a single fixed appliance.

---

## 2. Hardware basis (for prototyping)

The riskiest assumptions are all **software**: does a headless KMP proxy run reliably
always-on, pair as a realm device, and serve the store-and-forward buffer on the existing
storage SPI. None of that needs custom hardware. So prototype on commodity boards and only
commit to a board design once the software roles that justify it exist.

Two good prototype bases, serving different validation goals:

- **Raspberry Pi 5 (8/16 GB) + NVMe HAT** — validates the *form-factor and low-power
  always-on* thesis that justifies the product. ARM64 Linux, the JVM runs fine, GbE, USB3,
  PCIe for NVMe. Cheap enough to leave running for weeks. Weak for ML inference.
- **x86 mini PC (Intel N100/N305, fanless, Mac-Mini-ish)** — headroom for the relay-buffer
  + a local Postgres/blob store + light ML experiments. 2.5GbE, multiple USB-C, M.2 slots.

Recommendation: **start on the Pi 5** because it stress-tests the "tiny, cheap, always-on"
claim, and keep an N100 box around for the heavier relay/ML experiments. A literal Mac Mini
is a fine dev stand-in but a dead end as a *product* base — you can't ship Macs as the
appliance.

---

## 3. Connections & the expansion / daisy-chain question

The target port set — **2.5 GbE** (wired is right for an always-on node), a **dedicated
USB-C PD power-in** port, and **a few USB-C** for devices — is the right instinct. Keep
WiFi 6 + Bluetooth as well: BT is in the pairing path (whitepaper), and smart-home bridging
wants a Thread/Zigbee/Matter radio. Do **not** overload one port to be both PD-in and a
data daisy-chain; keep power-in separate for reliability and clarity.

On daisy-chaining expansion boxes — the honest engineering reality, because it dictates the
mechanical design:

> **Plain USB-C does not daisy-chain peripherals.** USB is a tiered star; a disk is an
> endpoint, not a pass-through. "Smart-home module → backup disk plugged into the module"
> only works under one of three models.

1. **Hub-in-every-module.** Each expansion box contains a USB hub and re-exposes a
   downstream USB-C. Cheap, works with ordinary USB. Bandwidth is shared down the chain and
   you cannot push real power through it. Good enough for *early prototyping*.
2. **USB4 / Thunderbolt daisy-chain.** A true daisy chain (integrated switch + two ports
   per device, ~6 deep) — exactly the mental model of "stack and chain." Pricier, and
   controllers are awkward on ARM.
3. **Stacked backplane connector** (pogo pins / edge connector, boxes physically stack).
   Mechanically the nicest "grow the stack" story, no cable spaghetti, and PCIe + a
   dedicated power rail can ride the connector. **Best fit for the milestone doc's
   "stackable" wording** and the long-term product.

The constraint that kills a naive cable chain regardless of model is **power**: a spinning
backup disk spikes 5–12 W, which you cannot reliably feed down a thin USB-C chain. Any
module with a disk or an ML accelerator needs its own power input (data over the chain,
power local) **or** a backplane carrying a real power rail. Net: prototype with model 1,
design the product toward model 3.

---

## 4. How much goes in the base unit

Argue **against** an anemic base. In 2026 the marginal cost of a sane baseline is low, and a
base unit that needs an expansion box for *table-stakes* features hurts the out-of-box
experience and leans on the fragile chain. The base unit should already include:

- An **NVMe slot + modest SSD** — the store-and-forward buffer, OS, and "relay you own a
  slice of" role all need local disk anyway.
- **8–16 GB RAM** for the JVM proxy + a local Postgres/blob store.
- **Radios** (WiFi 6, BT for pairing, a Thread/Zigbee path) — smart-home bridging is a core
  role, not an add-on.
- A small **always-on NPU** sized for the *light* inference tier only (wake-word, VAD) — see
  §5 for why this is not redundant with the AI expansion box, and why it is sized minimally
  rather than as a baby LLM accelerator.

Reserve expansion for the genuinely variable/expensive things: bulk/RAID storage, a serious
ML accelerator (the AI co-computer of §5), more audio channels. This still honours the doc's
"one speaker or five, with or without local inference" — *capacity and heavy compute* grow,
*basic function* does not. A backup disk as the *first* expansion someone adds is fine; the
base should still hold its own buffer/OS disk independent of it.

---

## 5. AI/ML — tiered inference (base NPU + co-computer)

The expansion box that does AI should be a **co-computer**, not a bare accelerator, and it
pairs with a small always-on NPU in the base unit. The two are complementary tiers, not
redundant. This is the recommended shape.

### 5.1 Accelerator vs. co-computer

There are two ways an "AI box" can attach, and they load the base unit very differently:

- **Bare accelerator** (Coral/Hailo/GPU over PCIe or USB) — dumb silicon. The base unit
  still runs the inference runtime, loads the model, does tokenization / audio feature
  extraction / sampling, and shuttles tensors across the bus; the accelerator only does the
  matrix math. The base CPU stays meaningfully busy, and the USB variant makes the bus the
  bottleneck.
- **Co-computer** — its own node (CPU + RAM + accelerator) running an inference server. The
  base unit sends "transcribe this" / "answer this" over the link and gets a result back —
  it is an RPC client, near-zero load. This is the Alexa-cloud architecture with the cloud
  replaced by a box in the user's house, and a well-trodden self-hosting pattern.

**Recommendation: co-computer.** Beyond offloading CPU, it is a separate thermal/power
domain (no GPU to cool inside the base unit), it maps cleanly onto "grow the stack," and it
keeps the base unit's real-time roles (always-on proxy, smart-home event routing) from being
starved while a large inference runs. With this model the "is the base box overloaded?"
question largely dissolves: its own jobs — proxy, IoT bridge, store-and-forward buffer,
sync/crypto — are light and mostly I/O in steady state, and the heavy FLOPs live elsewhere.
The accelerator model only gets tight in the "five speakers at once" case — which is exactly
where a co-computer wins, because you size *it* for the household and the base unit just
routes.

### 5.2 Why the base NPU is still worth having

Given a co-computer, the base NPU does **not** become pointless — it drops to a different
tier and should be sized for it. The split mirrors a phone's always-on DSP for "Hey Siri"
vs. its main NPU for real dictation:

- **Base NPU — light, constant, latency-sensitive, 24/7 at low power:** wake-word, voice
  activity detection, maybe tiny classification. You do not want to wake a power-hungry AI
  box just to listen for "Hey Mia."
- **AI co-computer — heavy, bursty, on-demand:** Whisper-class STT, the LLM reasoning for
  Mia. Runs when asked, then idles/sleeps; the base NPU wakes it.

This yields a clean upsell ladder that honours the modular thesis: **no AI box** → wake-word
on the base NPU + the whitepaper's decentralization fallback (a speaker uses any realm proxy
on the LAN, so light inference lands on a paired phone/desktop); **add the AI box** → full
on-device inference. There is a legitimate minimalist fork worth recording: push wake-word
onto the *speaker's* own DSP (most smart-speaker mics already have one) and ship the base
unit with **no** NPU at all, leaning entirely on the fallback until the AI box is added. The
base NPU is therefore "nice for a good standalone experience," not architecturally required —
decide this when the audio pipeline (§7 step 4) is real.

### 5.3 Trust boundary — keep keys on the base, ship only FLOPs

Wherever inference runs, the inputs (audio, prompts, the realm data Mia reasons over) are
**plaintext** at that moment, so the AI co-computer is another in-use location for decrypted
user data — inside the realm trust domain (the user's hardware, in their home), but a new
surface. Two consequences:

1. The link between base unit and AI box should be a private bus or an encrypted channel,
   and the box treated as a realm-trust-domain device (it is *not* a relay; House-rule 1
   still holds — the relay sees ciphertext only).
2. Keep the **orchestration** — semantic-permissions checks, realm-key access, the Voice App
   Service's policy layer — on the base proxy (which holds the keys), and ship only the dumb
   FLOPs to the co-computer. Trust logic stays on the box with the keys; the accelerator-node
   never needs them.

This is the AI-specific instance of the §9 threat-model note: the slice that lands on-device
inference adds a new in-use-plaintext location and must be reflected in
`docs/sync/threat-model.md` per House-rule 2.

---

## 6. Server software — stay on KMP

The Hub runs "a Tenshu proxy," and Control's proxy is already KMP/JVM and runs on Desktop.
The JVM runs on ARM64 and x86_64 Linux, so the Hub is essentially `control-desktop` running
**headless** on the node. It reuses:

- `:core-proxy` and every `*-proxy` service module, the schema, sync, and identity.
- The **relay-persistence storage SPI** — the store-and-forward buffer rides the durable
  block + blob stores that work introduced, so the Hub is not a parallel relay.

New work on the JVM side is a **headless entry-point module** (call it `:hub` /
`hub-desktop`-shaped, like `control-desktop` but with no Compose UI, started as a service /
systemd unit) plus the `HubManagementService` of §8.

**Do not fork the proxy into another language.** The protocol and threat-model code sharing
with Control is the entire point; a fork there is a maintenance and security hazard
(House-rule spirit + `docs/sync/threat-model.md`).

What KMP does **not** give you, and what needs native Linux (via JNA/JNI or a small
Rust/C sidecar the JVM talks to over local IPC):

- Smart-home radio stacks (Zigbee/Thread/Matter).
- The audio capture/playback pipeline for Mia.
- Expansion-bus / GPIO enumeration.

The clean pattern is a thin native sidecar per hardware concern, JVM proxy as the brain.
One later optimisation worth noting (not for the prototype): **GraalVM native-image** for
the headless proxy to cut idle RAM / startup on a tiny node. That is tuning, not a rewrite.

---

## 7. Prototyping sequence

Software-first on commodity hardware; hardware specials second; the expansion connector
dead last. Each step de-risks one role and most need no new hardware.

1. **Headless proxy, no custom hardware.** Build a headless Linux proxy from
   `control-desktop` / `:core-proxy`, run it on a Pi 5. Prove it pairs as a realm device
   (the pairing doc already names "a Tenshu Hub" as a valid proxy), syncs, and survives
   reboots. Validates the *always-on proxy* role.
2. **Hub Management Service stub** (§8) so it is administered from phone/desktop — minimum
   `getHubStatus` + `restartHub`. It has no local UI; this *is* its UI.
3. **Store-and-forward buffer** on the node's NVMe against the existing storage SPI. Test
   absorbing writes while the relay is down, forwarding on recovery. Pure software.
4. **Hardware specials, one USB port at a time.** A Zigbee/Thread stick
   (SkyConnect/Sonoff) for the bridge; a USB mic + speaker for the Mia capture pipeline.
   Each role over a plain port — which also answers "do we even need a fancy expansion bus
   yet."
5. **Expansion bus last.** Prototype the cheapest model (USB hub in a dummy box), measure
   bandwidth/power, graduate to USB4 or a stacked backplane only once a software role
   demands it.

---

## 8. Hub Management Service — stub interface

> **SUPERSEDED IN PART by the H4 as-built shape (2026-07-24, §13 H4).** The service,
> module set, and "administered from any realm device, no local UI" posture hold. What
> did **not** survive contact with the platform is the *remote-operation* shape sketched
> below: §7.2/§7.4 modelled `getHubStatus` as an on-demand op returning a live snapshot
> ("deliberately not Syncable"), and §7.5's `restartHub` as an op invoked *on the hub*.
> Both presume **cross-device operation routing, which does not exist** — an operation
> executes on the caller's own proxy, and the only cross-DEVICE channel is synced rows.
> As built, v1 is the platform-native inversion:
>
> - **`HubStatus`** is a `Syncable(Eager)` composite **keyed by the hub's device id**,
>   authored and refreshed by the hub itself (startup, after each backup pass, after
>   each command, bounded hourly heartbeat; change-gated so routine passes emit no
>   blocks). A `freshAsOf` field keeps the snapshot semantics honest. Fields carry the
>   H3 hooks (replica file/byte counts, `lastBackupPassAtEpochMs`, `startedAtEpochMs` —
>   uptime derives client-side) plus command outcomes (`lastCommandId`/`lastCommandOutcome`
>   — outcomes ride the status row, no second ack type).
> - **Commands** (`RestartHub`, `RunBackupNow`) ride an idempotent `Syncable(Eager)`
>   **`HubCommand`** row (keyed by a caller-minted `commandId`) authored via the
>   `IssueHubCommand` op *on the manager's own proxy*; the hub observes it via ordinary
>   sync and executes **at most once** (durable consumed-id journal in its data dir,
>   written *before* execution; commands older than a 15-min sanity window ignored, so a
>   backlog restore cannot replay a restart storm).
> - The **client surface** (`:hub-management-service-client`) keeps the §7.4/§7.5
>   ergonomics: `getHubStatus()` is a *local read* of the freshest hub-authored row;
>   `restartHub(mode)`/`runBackupNow()` author command rows. Ops stay non-suspend at the
>   interface per ADR 0012.
> - `RestartHub(mode="proxy")` = clean shutdown + `exitProcess(10)`; systemd re-spawns
>   (`Restart=always`, documented in `hub/deploy/tenshu-hub.service`). `mode="system"`
>   is v1-unimplemented → `unsupported` outcome. The `ExpansionModule` relation below
>   stays stub-level (not built) — it lands with the hardware-detection slice.
>
> The stub below is retained for the original field sketches; read it with the above.

A new service so the Hub is administered from any realm device. It follows the standard
Service & Operation pattern (`docs/agent.md` §2) and lives in a module set symmetric with
`:notification-service`. Read surface = a `serviceDataRelation` (the detected expansion
stack); actions = `IoOperation` + `TerminalOperation` operations.

These are **stubs** — IDs, fields, and the operation set will move as the roles in §7 land.
They exist to make step 2 of the prototyping sequence buildable.

### 7.1 `HubManagementService` (`:hub-management-service`)

```kotlin
package org.tenshu.hub.management

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

interface HubManagementService : Service {

    override val schema: ServiceSchema
        get() = Schema

    /** Expansion modules currently detected on the stack (disk, ML, …). */
    val expansionModules: TList<ExpansionModule>

    companion object {
        const val ID = "tenshu/hub/management/service"

        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.EXPANSION_MODULES,
                    type = TList.typeOf(ExpansionModule.Schema)
                )
            )
        )
    }

    object Relations {
        const val EXPANSION_MODULES = "$ID/expansionModules"
    }
}
```

### 7.2 `HubStatus` composite

A snapshot returned by `getHubStatus`. `Syncable(Eager)` is deliberately **not** set: status
is a live read of *this* node, not realm state to propagate — it is fetched on demand via
the operation, mirroring how a query op returns a fresh value rather than a synced record.

```kotlin
package org.tenshu.hub.management

import org.tenshu.core.Composite
import org.tenshu.core.TLong
import org.tenshu.core.TString
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.long
import org.tenshu.core.schema.string

interface HubStatus : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Human-readable node name shown in the managing UI. */
    val name: TString
        get() = getField(Fields.NAME)

    /** Running proxy build / version string. */
    val version: TString
        get() = getField(Fields.VERSION)

    /** Seconds since the proxy process last started. */
    val uptimeSeconds: TLong
        get() = getField(Fields.UPTIME_SECONDS)

    /** SyncBlocks currently buffered store-and-forward, awaiting relay flush. */
    val bufferedBlockCount: TLong
        get() = getField(Fields.BUFFERED_BLOCK_COUNT)

    companion object {
        const val ID = "tenshu/hub/management/status"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.NAME)
                string(Fields.VERSION)
                long(Fields.UPTIME_SECONDS)
                long(Fields.BUFFERED_BLOCK_COUNT)
            }
        )
    }

    object Fields {
        const val NAME = "$ID/name"
        const val VERSION = "$ID/version"
        const val UPTIME_SECONDS = "$ID/uptimeSeconds"
        const val BUFFERED_BLOCK_COUNT = "$ID/bufferedBlockCount"
    }
}
```

### 7.3 `ExpansionModule` composite

One entry per detected box on the stack. `Syncable(Eager)` so the managing device sees the
stack without holding a live connection to the Hub; keyed by a stable `moduleId`.

```kotlin
package org.tenshu.hub.management

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

interface ExpansionModule : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable identifier for this module (serial / connector slot). */
    val moduleId: TString
        get() = getField(Fields.MODULE_ID)

    /** "disk" | "ml" | "audio" | … — stringly-typed at stub stage. */
    val kind: TString
        get() = getField(Fields.KIND)

    /** Position in the stack/chain, "0" = base-adjacent. */
    val stackPosition: TString
        get() = getField(Fields.STACK_POSITION)

    companion object {
        const val ID = "tenshu/hub/management/expansionModule"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.MODULE_ID)
                string(Fields.KIND)
                string(Fields.STACK_POSITION)
            }
        )
    }

    object Fields {
        const val MODULE_ID = "$ID/moduleId"
        const val KIND = "$ID/kind"
        const val STACK_POSITION = "$ID/stackPosition"
    }
}
```

### 7.4 `GetHubStatus` operation

```kotlin
package org.tenshu.hub.management

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun HubManagementService.getHubStatus(): TResult<HubStatus?> =
    findOperation<GetHubStatus>(GetHubStatus.ID)
        .getHubStatus()
        .asTaskInteractor<HubStatus?>()
        .execute()

interface GetHubStatus : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getHubStatus(): RequestObject

    companion object {
        const val ID = "tenshu/hub/management/service/getHubStatus"

        val Schema = operation(
            id = ID,
            receiver = HubManagementService.Schema.toType(),
            params = { /* none */ },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
```

### 7.5 `RestartHub` operation

```kotlin
package org.tenshu.hub.management

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/** [mode] = "proxy" (restart the proxy process) | "system" (reboot the node). */
suspend fun HubManagementService.restartHub(mode: String): TResult<Data?> =
    findOperation<RestartHub>(RestartHub.ID)
        .restartHub(mode)
        .asTaskInteractor<Data?>()
        .execute()

interface RestartHub : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun restartHub(mode: String): RequestObject

    companion object {
        const val ID = "tenshu/hub/management/service/restartHub"

        val Schema = operation(
            id = ID,
            receiver = HubManagementService.Schema.toType(),
            params = {
                string(id = Params.MODE)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val MODE = "$ID/mode"
    }
}
```

Operation implementations (`OperationImplementation`, marked `internal`, registered via an
`OperationImplementationFactory` — never implementing the domain `Operation` directly, per
`docs/agent.md` §2) and the `ModuleConfiguration` (`tModule { }`) wiring live in
`:hub-management-service-proxy` / `-common` and are out of scope for this stub.

---

## 9. Threat-model note

A home node that holds ciphertext at rest **and** acts as an always-reachable proxy / trust
root is a new at-rest location and a new proxy surface. It is within the user's trust domain
(it is their hardware, in their home), so it does not violate the Zero-Trust *relay*
principle — the relay still sees ciphertext only, and the Hub is a *device of the realm*,
not the relay. But per House-rule 2, the slice that lands the store-and-forward buffer and
the always-on proxy surface must amend `docs/sync/threat-model.md` in the same PR (new
at-rest asset; always-on proxy reachability). This proposal flags it; it does not write the
amendment.

Separately, the AI co-computer (§5.3) adds a new **in-use plaintext** location — inference
inputs are decrypted at the moment they are processed. It stays within the user's trust
domain, but the slice that lands on-device inference must reflect this in the threat model
too (private/encrypted base↔box link; orchestration and keys stay on the base proxy, only
FLOPs ship to the box).

---

## 10. Dependencies & open questions

Tracked in `milestones.md` §"Tenshu Hub" — repeated here with the prototyping framing:

- **Hub Management Service interface** — §8 here is the first cut; firms up as roles land.
- **Hardware reference design + expansion-module interface** — §3; defer the connector
  choice (USB4 vs. backplane) until a software role needs the bandwidth/power.
- **Smart-speaker audio pipeline** — capture on speaker → Voice App Service → response.
  **Decisions pre-recorded 2026-07-25 in [`audio-and-voice.md`](audio-and-voice.md)**
  (speaker = EPC client + WebRTC media; audio never rides Thread; receiver-not-source for
  third-party music; two satellite trust tiers). That note is decisions-only — the real
  proposal is written when the hardware is.
- **AI co-computer interface (§5)** — the base↔box inference RPC, the wake/sleep handshake
  from the base NPU, and the "ship FLOPs not keys" split. Gated on the audio pipeline being
  real; the bare-accelerator alternative is the fallback if a co-computer proves overkill.
- **IoT-device bridge** — smart bulbs, thermostats, etc.
- **Local SyncBlock store / store-and-forward buffer** on the relay-persistence storage SPI,
  with the threat-model note in §9.

~~Open question worth settling early: **does the headless node want its own minimal local
web admin, or is `HubManagementService`-from-a-realm-device the only management surface?**~~
**RESOLVED 2026-07-23 → the latter, definitively.** No local bootstrap surface and no local
web admin: the "up but not yet paired" recovery path is covered by the printed-payload
offer variant in §11 (the relay rendezvous *is* the bootstrap channel, and the arming
button re-opens it after a factory reset). `HubManagementService` from a realm device is
the only management surface, as §8 intended.

---

## 11. Headless pairing (H2) — the printed-static-key offer variant

### 11.1 Decision: outbound-only, no local server

The Hub is a **client of the relay, exactly like `control-desktop`** — it runs no
internet-facing listener and no LAN bootstrap server. The strongest architectural property
the platform already has is that every device is outbound-only: pairing is relay-mediated
with both sides *polling* (`docs/sync/pairing-flow.md` §3/§4), and sync is client-initiated
against the relay. Nothing requires inbound connectivity to a device, so nothing should
start requiring it now.

This makes "reachable over the internet" a non-problem by construction: no port
forwarding, no DynDNS, no TLS cert provisioning for home IPs, and no fight with CGNAT
(behind which inbound is simply impossible without a tunnel — on its own a product-killer
for consumer hardware). It is also the right security posture: an always-on box holding
realm keys with a public listener is exactly the surface we do not want to owe the threat
model. Commercial precedent is uniform — Hue, Blink, HomeKit hubs, Echo are all
outbound-only. A minimal Ktor server on the node was considered and **rejected**; Ktor
stays in the picture only as what the relay already runs.

### 11.2 What actually breaks for headless

The offer flow (`pairing-flow.md` §4) is already 90 % of headless pairing — the *new*
device publishes, an existing device scans and approves. The Hub is an offerer. Exactly
two things break: the Hub cannot **display a dynamic QR**, and it cannot **display an
SAS**. The fix: make the optical channel *static*, and let it *authenticate* a dynamic
ephemeral key delivered over the relay.

### 11.3 Design

- **Printed payload** (QR label on the device, Blink/Matter-style):

  ```
  tenshu://hub?d=<deviceId>&g=<staticVerifyKey>&s=<secret>
  ```

  `staticVerifyKey` = a long-term Ed25519 verify key; `secret` = a 128-bit pairing secret.
  Base32 fields via the `PairingUri` helper family (`:core-common`), a third role host
  beside `tenshu://join` / `tenshu://offer`. For the Pi prototype "manufacture" is flashing
  the image: a **provisioning script** generates the device identity, the static signing
  keypair, and the secret, bakes them into the image, and emits a printable QR PDF (tape it
  to the Pi). Print the secret as short base32 text under the QR (Matter does this) so a
  damaged code still has a manual-entry path.

- **Hub side.** While unpaired — ideally gated by a physical **arming button** (GPIO on
  the Pi; Hue-button model) opening a bounded window — the Hub generates a **fresh
  ephemeral X25519 keypair**, signs `{ephemeralPub, deviceId, deviceName}` with the static
  key, POSTs the signed offer to a relay mailbox keyed by `SHA-256(secret)` (as built since
  2026-10-28: domain-separated, `PairingRendezvous.hubOfferHash`, threat model T-P-17), and polls for
  the response. From there it is *identical* to the §4.2 offerer role: receive the
  offer-response, decrypt the `PairingPayload`, install keys, self-push
  `Device{isAuthorized=true}`. A status LED signals armed / paired.

- **Phone side.** The role-aware scanner routes the `tenshu://hub` host → GET the Hub's
  offer from the relay → **verify the signature against the printed verify key** → then
  behave exactly like today's §4 responder: approval prompt (showing the device
  name/serial from the *signed* offer), seal the payload, POST the offer-response.

- **Relay surface.** One new mailbox pair under the existing route, shaped like
  `PairingOfferStore`: `POST /sync/pairing/hub-offer` (the Hub's signed ephemeral offer,
  keyed by `pairingSecretHash`, short TTL) and `GET /sync/pairing/hub-offer?hash=…` (since
  2026-10-28 `POST /sync/pairing/hub-offer/fetch`, key in the body — threat model T-P-17) (the
  phone's fetch). Both carry only public material + signatures — House Rule #1 holds
  trivially. The existing `POST`/`GET /sync/pairing/offer-response` endpoints are reused
  unchanged for the return leg.

### 11.4 Security argument

- In the standard offer flow the QR-carried ephemeral key is the anti-MITM channel. Here
  the **printed verify key plays that role**: the relay cannot substitute an ephemeral key
  that verifies against what the user physically scanned.
- Signing the *ephemeral* key (rather than sealing to a static X25519 key) **preserves
  forward secrecy**: a recorded relay exchange plus a later-extracted static private key
  still cannot decrypt the master-key handoff. (The contact handshake's static-A13 seal
  targets are precedent for static keys in handshakes; this design is strictly stronger
  for this use because the payload is the realm master identity.)
- **SAS is replaced, not dropped**: signature verification + the phone-side approval
  prompt naming the device serial + the Hub's LED state confirmation together replace the
  simultaneous-comparison ceremony the Hub cannot render.

Threat-model deltas to land **with the code** (House Rule #2), sketched: the printed
secret survives factory reset → replay window, mitigated by the arming button + a
single-use-per-window consumed-hash store (the `ConsumedPairingSecretsStore` pattern);
label photographed by a visitor → mitigated by the approval prompt + physical-button
arming (possession, not sight, is required); relay substitution → closed by the printed
verify key.

### 11.5 NFC / BLE — carriers, not protocols

Treat NFC as an **alternate carrier of the same payload**, not a different protocol. A
passive NTAG sticker (cents; written once by the provisioning script; needs no Pi
hardware at all) holds the same `tenshu://hub` URI — Android and iOS both do background
NFC-tag URL reading natively, so tap-to-pair falls out for free. For the final Hub, an
I²C "dynamic NFC" tag chip would let firmware rewrite the tag (even carry the live
ephemeral key, removing one relay round-trip) — a nice product touch nothing depends on.
BLE advertising (the Matter approach; the whitepaper already reserves BT for pairing) is
the other dynamic option but needs a BlueZ sidecar and buys little over printed-QR +
relay — **defer**. Design the payload once; QR print, NTAG, and BLE are transports.

Estimated size: 2–3 PRs (URI + provisioning script; relay mailbox + hub offer loop;
phone scanner branch + threat-model amendment). No change to `PairingPayload`, master
delegation, `KnownDevicesStore`, or JWT registration.

---

## 12. Relay backup = a full-replica realm device (H3)

### 12.1 Reframe: the backup *is* a replica

"Back up our data from the relay onto the node" needs no new backup machinery: **a realm
device is already a replica.** Once the Hub pairs (§11), ordinary sync gives it all
metadata/state. The only gap is content bytes, which are lazy-on-open — and the machinery
to close that gap exists too: the **offline keeper** + `SyncPolicy`/`SyncRuleSet`
(longest-prefix rules, per-device via the preference service's `DeviceSetting`). Set the
Hub's device policy to *keep everything offline* at the root and the NVMe fills with a
complete second copy — keys included, on hardware the user owns. That is §1 role 4
("storage + relay resilience") falling out of existing pieces, and it doubles as the real
disaster story: relay dies → data intact at home.

This is deliberately **not** the §7-step-3 store-and-forward buffer. That slice mirrors
the relay's *ciphertext block log* over the storage SPI (an availability buffer for relay
outages); the backup role wants the *durable state + content*, which per the
retention-model taxonomy is the tier that matters (the block log is the ephemeral
delivery tier). The two remain orthogonal slices.

At-rest caveat for the threat-model amendment (§9): the device-local content cache is
**plaintext** (retention-model tier 3), and a full replica on an always-on living-room box
is a much larger plaintext surface than a phone. Prototype answer: **LUKS on the NVMe**
(ops-level, no code). Product answer worth one line now, not a build: a
"retain ciphertext, decrypt on demand" mode for the Hub's copy.

### 12.2 Bounded-store-cache readiness — assessed, sufficient

The keep-everything Hub is the **maximal-residency device** in the fleet, so the
`SqliteObjectStore` resident-set ceiling (`bounded-store-cache.md`) is the store question
for this role. The kernel slice **shipped 2026-07-23** and covers
exactly the two failure modes the Hub would have hit:

1. **Initial replication is bulk ingest.** First sync of a whole realm hammers the write
   path — previously an unbounded mailbox (the Fit-import OOM shape). Now: bounded
   mailbox (512) + `trySendBlocking` backpressure + dedupe-by-hash. The write-side OOM is
   closed.
2. **Keep-everything residency.** The cache-aside store held every row resident
   (thumbnail ceiling ~8k–12k files; 56 KiB `Workout.Streams` rows). Now: C-lite tiers
   rows > 8 KiB out of residency — pinned only until commit, then point-SELECT
   read-through on a dedicated WAL connection. Large rows (thumbnails, workout streams)
   no longer accumulate in RAM, so `Workout.Streams`' pending option-B move to the
   content plane is an efficiency improvement, **not** a Hub blocker.

**Verdict: no new store work gates H3.** The JS-twin fork (`IndexedDbObjectStore` still
bulk-loads) is irrelevant here — the Hub is JVM.

Watch items, in priority order:

- **Small-row metadata residency is still unbounded** (rows ≤ 8 KiB stay resident by
  design). The Hub is the likeliest device to reach the C-full trigger
  (~1M+ entities) first — treat the H3 soak (weeks-long run on the Pi's 8–16 GB) as the
  benchmark that decides whether C-full/no-app-cache graduates from end-state option to
  scheduled work.
- **Task-scoped sync lane** (spec home `core-background-processing.md`) would turn
  initial replication from block-per-save into checkpointed bulk lanes — an improvement
  for H3's first-sync duration, not a prerequisite.

---

## 13. Prototype phasing (H1–H4)

Sequences §7 steps 1–3 into shippable slices against the Pi 5 + NVMe in hand. Thread/
Zigbee radios stay parked until `:home-device-service` exists (§7 step 4) — they interact
with none of the below.

- **H1 — headless runtime.** The `:hub` entry point (shaped like `control-desktop` minus
  Compose; §6): assemble `:core-proxy` + the `*-proxy` service modules, run under a
  systemd unit on the Pi (ARM64 JVM), survive reboots. To keep H1 unblocked by H2, pair
  it *manually* via a temporary CLI that runs the standard offerer role and prints the
  `tenshu://offer` QR as ASCII/PNG to the terminal — throwaway glue, standard protocol.

  **H1 IMPLEMENTED (2026-07-24).** As-built: a plain-JVM `:hub` module whose `HubRuntime`
  assembles the *production* `buildProxyModule()` + desktop `buildPlatformProxyModule()`
  (fresh instances, the route-1 builder pattern — the desktop builder was added for this)
  with a thin last-wins override module: own `platformPackageName`, sqlite object store +
  secret bucket rooted at a configurable data dir (`--data-dir` / `TENSHU_HUB_DATA_DIR`,
  default `~/.local/share/tenshu-hub`). Two deviations worth knowing: (1) Linux has no OS
  credential store, so durable realm keys ride a new `FileWrappingKeyStore` (owner-only
  `wrap.key` beside `secrets.enc`; threat-model T-C-17 hub note; LUKS per §12.1 is the disk
  answer); (2) the desktop init hook gained an optional `syncPollingIntervalMs` named
  binding (default unchanged 30 s) so the hub can tune cadence and its system test can use
  the production hook fast. Pairing CLI = `HubPairingCli` (`OfferPairing` → ASCII QR via
  nayuki qrcodegen → 2 s `GetOfferStatus` poll; armed/approved/denied/timeout lines;
  re-arms on timeout/deny). Deploy: `hub/deploy/tenshu-hub.service` +
  `docs/how-to/hub-pi-deploy.md` (Gradle `application` distTar). Tests:
  `HubRuntimeAssemblyTest` (DI constructs, starts/stops headless, device identity durable
  across re-assembly) + `HubPairingSystemTest` (in-process relay; hub CLI as offerer, a
  `TestClient` phone approves; post-pairing folder row converges into the hub's store).
  ⚠ H1 inherited the desktop in-memory `FileDatabase` — content bytes were not durable
  (metadata/state was); durable content is H3's concern. (Desktop itself stopped being
  in-memory on 2026-08-03; the H1-era statement is kept for history.) On-Pi pass pending.
- **H2 — headless pairing** (§11): printed-URI format + provisioning script (QR PDF,
  optional NTAG write), `/sync/pairing/hub-offer` mailbox, hub offer loop + arming
  button/LED, phone scanner branch, threat-model amendment.

  **H2 IMPLEMENTED (2026-07-24, one PR).** As-built, per §11.3 with one design
  refinement and a few prototype-scope calls:

  - *Printed label*: `tenshu://hub?d=…&g=…&s=…` as a third `PairingUri` role host
    (`:core-common`); the printed `g` **is the hub's A4 device signing key** and `d` its
    device id — deliberate, so the responder's registration delegation works off the
    printed fields exactly like the offer QR's `g`/`d` (no new registration path).
    `tenshu-hub provision` (subcommand, not a separate script) mints/re-prints
    idempotently into the data dir — `pairing-secret` (base32, owner-only),
    `pairing-uri.txt`, `pairing-qr.png` (PNG rather than PDF; print it) — plus ASCII QR
    and a Matter-style `XXXX-XXXX-…` manual-entry line on the terminal. `--rotate`
    regenerates the **secret only**; identity/signing key never rotate silently.
  - *Refinement — session-scoped return leg*: §11.3 leaves the offer-response address
    implicit. Keying it directly by `SHA-256(secret)` would collide with that mailbox's
    single-use/24-h-consumed-marker semantics on every re-arm (the printed secret is
    long-lived). As built, both sides derive `SHA-256(secret ‖ ephemeralPub)` — fresh
    per arm, computable by both ends, offer-response endpoints untouched.
  - *Relay*: `POST /sync/pairing/hub-offer` + `POST …/hub-offer/fetch` (was `GET ?hash=`), both unauthenticated (§11.4 posture);
    **re-post replaces** (re-arm), **fetch consumes** (single-fetch closes the window),
    7-min TTL + hourly sweep. In-memory store only (a relay restart mid-window = re-arm;
    a `SqlitePairingOfferStore`-shaped durable sibling is a follow-up if ever needed).
    The empty-mailbox 404 carries a `hub-offer:none` marker body so clients distinguish
    "hub not armed" from an old relay's unmatched-route 404 → surfaced as "relay does
    not support hub pairing yet".
  - *Hub side*: `OfferHubPairing` op validates the label against the runtime's own
    identity, signs `{ephemeralPub, deviceId, deviceName}` (domain-separated,
    length-prefixed `hubOfferMessage`) with the static key, posts, then the STANDARD
    `GetOfferStatus` poll completes pairing unchanged. `run`/`pair` auto-select the
    printed-label arm when provisioned; `--legacy-offer-qr` keeps the H1 terminal-QR
    fallback until H2 is proven on-device. v1 arming = process start / `pair` (GPIO
    button + LED = later Pi concern, marked in code).
  - *Phone side*: `RespondToHubPairing` op + a `tenshu://hub` branch in the role-aware
    scanner (`control-shared-logic`/`control-shared` only; no `androidMain` touch).
    Signature verification against the printed key is a hard error on mismatch; the
    approval sheet shows **no SAS** and says the printed-code verification replaced it
    (§11.4). Approve/deny reuse the offer-flow ops untouched.
  - *Threat model*: header sub-amendment + **T-P-09..T-P-12** (§8.1 hub variant).
  - *Tests*: `HubOfferRoutingTest` (relay semantics), `PairingUriTest` hub vectors,
    `HubOfferSignatureTest` (message canon), `HubProvisioningTest` (idempotence/rotate),
    and `HubPairingSystemTest` H2 end-to-end (provision → arm → verified respond →
    approve → keys installed → row converges) + negatives (wrong printed key refuses;
    un-armed hub errors). NTAG/NFC write, GPIO arming, and the on-Pi pass remain open.
- **H3 — full-replica backup** (§12): Hub device policy = keep-everything via the
  existing keeper; LUKS on the NVMe; verify the relay-wipe → hub-intact scenario; start
  the residency soak.

  **H3 IMPLEMENTED (2026-07-24).** As-built, per §12 — the backup *is* a replica; no new
  fetch machinery, no wire/schema change, no `TENSHU_STORAGE_FORMAT_VERSION` bump
  (device-local storage only):

  - *Durable content store*: `JvmFileDatabase` — a disk-backed `FileDatabase` under
    `<data-dir>/content/`, bound in the hub's override module. ⚠ Landed as
    `HubFileDatabase` in `:hub`; **promoted to `core-common`'s shared `jvmMain`
    2026-08-03** when desktop Control closed the same gap, exactly as its own KDoc
    said should happen — so the hub's override now supplies only the *root*, not a
    different implementation. Closes the H1 as-built gap ("content bytes are not
    durable"). Real-filesystem semantics mirror `AndroidFileDatabaseImpl`
    (`RandomAccessFile` positional range IO so emit/reassembly stay `O(CHUNK)`; atomic
    rename promote), plus a content-root confinement check. Two keeper-load-bearing
    contract points are pinned by tests: a **0-byte file is present-and-empty, never
    absent** (`fileSize` → `0L` non-null, so the keeper's has-local-bytes probe never
    re-selects it), and a zero-length `writeBytesRange` still *creates* the file (the
    manifest applier's single-empty-chunk reassembly depends on it). **Deliberately
    hub-scoped**: the desktop apps and iOS keep their in-memory bindings (same known
    gap, `docs/tech-debt.md`); this class is the reuse candidate when that is closed.
  - *Keep-everything policy + drive*: `HubRuntime.startBackupLoop` (default 15 min,
    `--backup-interval-ms`) runs `runBackupPassNow`: (a) pins this device's
    `file.sync.defaultPolicy` `DeviceSetting` to `KEEP_OFFLINE` via the standard
    `setFileSyncDefaultPolicy` facade — device-scoped, so no other realm device's policy
    is touched; written only when the explicitly-set row differs (a routine boot writes
    nothing); (b) dispatches the existing `HeadlessSafe` `OfflineKeeper` op by id on the
    hub's session `ExecutionContext` — exactly the `ScheduleDrainLoop` dispatch shape.
    The loop exists because desktop wires `NoOpPlatformScheduler` and the inherited
    in-process keeper item ticks at a fixed 6 h — too coarse for the replica role.
    **Eviction never runs on the hub**: the keeper is download-only by design,
    `EvictFileBytes` is a client-invoked op nothing on the hub calls, and the
    retention-model local-ceiling/LRU slice (P2) is not wired here.
  - *Verification* (`HubBackupSystemTest`, the H1/H2 in-process-relay harness):
    `hubHoldsEveryByte_thenRelayDies_dataIsIntactAtHome` — phone uploads content
    (incl. a 0-byte file) → hub pairs, converges, backup pass lands the bytes → relay
    shut down + hub restarted fresh over the same data dir → bytes readable byte-for-byte
    with no relay; plus the catch-up path (file uploaded while the hub was stopped is
    fetched after restart) and the device-scope leak check (the phone's policy row stays
    untouched).
  - *Threat model*: T-C-17 hub H3 sub-amendment (maximal plaintext-content-at-rest
    surface; LUKS on the NVMe as the ops mitigation — now a section in
    `docs/how-to/hub-pi-deploy.md` — and the "retain ciphertext, decrypt on demand" hub
    mode noted as a future option, not built).
  - *Open*: the on-Pi pass (LUKS + weeks-long residency soak, §12.2's C-full benchmark);
    the store-and-forward relay-outage buffer stays its own orthogonal slice (§7 step 3).
- **H4 — `HubManagementService` v1** (§8 stub: `getHubStatus` + `restartHub`) so Home's
  storage tile and Hub settings surface go live against real data
  (`home-and-mia-apps.md` §4 D6: Home guides, Control's machinery admits).

  **H4 IMPLEMENTED (2026-07-24).** As-built — **synced state instead of remote ops**
  (the §8 header note carries the full deviation rationale; §8's remote-op stub is
  superseded in part):

  - *Module set*: `:hub-management-service` + `-common`/`-client`/`-proxy`
    (`org.tenshu.hub.management`), symmetric with `:preference-service`; no
    `-test-utils` (nothing needed one). Registered in `buildProxyModule()`'s
    `coreSchemaConfigs` (all platforms know the schemas ⇒ sync ingest works
    everywhere) and in `:system-test-utils`. Both composites are additive NEW types
    (`MovementTrace` precedent) — **no `TENSHU_STORAGE_FORMAT_VERSION` bump, no relay
    change**; serialize→parse pinned by `HubStatusSerializationTest` /
    `HubCommandSerializationTest` (the wire-registration-gap guard).
  - *Schemas*: `HubStatus` (`Syncable(Eager)`, keyed by `deviceId`; name, version,
    `startedAtEpochMs`, `replicaFileCount`/`replicaByteCount` (H3 content dir),
    `lastBackupPassAtEpochMs`, `paired`, `freshAsOf`, `lastCommandId`/
    `lastCommandOutcome` — outcomes ride the status row, no ack type) and
    `HubCommand` (`Syncable(Eager)`, keyed by caller-minted `commandId`; target,
    kind `restart`/`runBackupNow`, mode, `issuedAtEpochMs`, `issuedByDeviceId`).
    `IssueHubCommand` is the only op — it executes on the *manager's* proxy (stamps
    issuedAt + issuer) and saves the row; sync is the transport.
  - *Client surface*: `client.hubManagementService` + `getHubStatus()` (freshest
    hub-authored row, local read) / `getHubStatuses()` / `hubStatusesFlow()` /
    `runBackupNow(target)` / `restartHub(target, mode)` (both accept a pinned
    `commandId`, default fresh UUID).
  - *Hub side* (`:hub`): `HubManagementAgent` = status publisher (startup + after
    every backup pass + after each command + hourly heartbeat; **change-gated** —
    the always-moving `freshAsOf`/`lastBackupPassAt` never cause a write by
    themselves, so routine passes emit no blocks) + command executor
    (`hubCommands.flow()` → pure `HubCommandGate` decision table → durable
    `ConsumedCommandJournal` (`consumed-commands.log`, written **before**
    execution = at-MOST-once) → execute → force-publish outcome).
    `RunBackupNow` → `HubRuntime.runBackupPassNow()`; `RestartHub(mode="proxy")`
    → publish `restarting` + clean shutdown + `exitProcess(10)` (`EXIT_RESTART`;
    unit switched `Restart=on-failure`→`always` with the code documented);
    `mode="system"` v1-unimplemented → `unsupported`. Stale window 15 min
    (restart-storm guard). CLI enum renamed `HubCommand`→`HubCliCommand` to free
    the schema name.
  - *Registration*: relation maintainer (`initHubManagementService`) + op factory
    wired in desktop + iOS `PlatformProxyModule` and `:system-test-utils`;
    Android's `PlatformProxyModule` was deliberately untouched at H4 — Android
    proxies ingested/decoded the rows (schema is commonMain-registered) but
    maintained no service relations and served no `IssueHubCommand`.
    **Closed by HD-3** (`home-device-service.md` §3): Android now mirrors the
    desktop wiring for hub-management *and* the device service.
  - *Tests*: `HubManagementSystemTest` (hub publishes → manager reads via client
    surface → `RunBackupNow` executes exactly once with re-delivery pinned as
    no-op → refreshed status incl. live replica figures syncs back; restart test
    with stubbed exit hook + re-delivery + `mode="system"` unsupported) +
    `HubCommandGateTest`/`ConsumedCommandJournalTest` (decision table, durability).
    Full `:hub:test` (H1–H4) green.
  - *Threat model*: no delta owed (no A1–A4/A9/relay-shape/protocol touch). Posture
    note: a realm device can restart the hub — realm devices are already fully
    trusted peers (§9), and the command channel is realm-key-sealed sync rows, so
    the surface adds no new principal.
  - *Not in this slice*: `ExpansionModule` detection (stub-level), `mode="system"`
    restart, ~~Home's storage tile consuming the client surface (Home is mock-only
    with no Tenshu client stack yet — the tile goes live when Home gains one)~~
    **(landed 2026-07-24, see below)**, and the on-Pi pass.

  **H4 follow-up — Home storage tile live (2026-07-24, one PR):** `HubStatus`
  gained `diskFreeBytes`/`diskTotalBytes` (the M1 tile is "n GB free / total";
  H4's row had replica figures but no disk figures), populated in
  `HubManagementAgent` from the data-dir filesystem (`freeSpace`/`totalSpace`
  on the content root, falling back to the data root) and included in the
  change gate — free space **coarsely** (100 MB quantum,
  `DISK_FREE_CHANGE_QUANTUM_BYTES`), else every refresh tick would publish a
  sync block as unrelated processes nibble at the filesystem. ⚠ Positional
  composite ⇒ the inserted fields reshaped the row →
  `TENSHU_STORAGE_FORMAT_VERSION` **v11 → v12** in the same PR (HR #9; folds
  into the relay redeploy already pending for v11). `0/0` disk figures mean
  "unknown" and consumers must not render them — Home falls back to its mock
  tile. Home gained its first Tenshu client stack to consume this
  (desktop-proven at the time; **Android runtime landed with HD-3**) — details in
  `home-and-mia-apps.md` §8.1.
  End-to-end pinned by `HomeStorageTileSystemTest` (`:hub`): hub publishes →
  Home's repository over a real client maps the row into the tile model →
  agent silenced → staleness flips.

**Beyond H4 — the device spine (2026-07-25):** the H4 status/command pattern is now
generalised into `:home-device-service` (**HD-1**, [`home-device-service.md`](home-device-service.md)):
`HomeDevice`/`DeviceState`/`DeviceCommand` rows over a pluggable hub-side `DeviceBridge`
seam (`DeviceBridgeHost` reuses `HubCommandGate` + `ConsumedCommandJournal` with a tight
2-min staleness window; threat-model §8.19), proven end to end on `SimulatedDeviceBridge`
(`tenshu-hub --simulated-devices`). §7 step 4's "IoT-device bridge" starts there; the
Zigbee radio (zigbee2mqtt sidecar, §6 posture) is that doc's HD-2.

**HD-2 landed the same day** (`tenshu-hub --zigbee2mqtt`): the sidecar posture of §6 is now
real — mosquitto + zigbee2mqtt as two more systemd units beside `tenshu-hub`
(`hub/deploy/`, `hub-pi-deploy.md` §7), with the hub as an MQTT **client on loopback**, so
§11's no-listener decision holds unchanged and House Rule #1 is untouched (the relay never
sees any of it). Permit-join rides the same `DeviceCommand` channel as everything else,
which is how it inherits the journal and the 2-minute window; the RF threat-model half is
§8.20 (T-HD-04..07). The parked Zigbee note in §7 step 4 is answered by that slice.
