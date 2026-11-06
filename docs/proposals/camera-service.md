# `:camera-service` — Cameras as Realm Media

**Status:** Draft v0.1 — design settled 2026-07-24/25 (session with the maintainer,
following `home-device-service.md` HD-1). No code yet. Sequenced explicitly **after** the
HD-2 Zigbee bridge: light bulbs prove the device spine end-to-end on cheap, forgiving
hardware; cameras are the technically hard case and should land on a spine that already
works.

**Scope:** how a camera becomes realm data — snapshots, event clips, detection events,
live view — and which bridge protocol we speak. Companion to `home-device-service.md`,
which keeps camera *identity and control*; this doc owns the media/event plane.

**Not in scope:** NVR-grade continuous-recording UI, cloud-camera (Ring/Nest/Arlo)
integration, the smart-speaker audio path, face *recognition* (see CD-D7 — deliberately
excluded, not merely deferred).

---

## 1. Why cameras need their own doc

Everything in `home-device-service.md` assumes a device's entire observable state fits in
a small row that syncs cheaply. A camera breaks exactly that assumption and nothing else:
its *state* is as small as a lamp's (online, battery, last event), but it continuously
produces **media** — snapshots, clips, live streams — which is orders of magnitude larger,
has retention and quota consequences, and (for live view) has a latency budget sync cannot
meet.

The pleasant surprise is that Tenshu already has most of the machinery: content-addressed
CEK-sealed blobs, a seekable streaming read path, a retention/quota model, and an activity
feed. A camera clip is structurally a video file we already know how to store, sync and
play. The genuinely new pieces are **live view** (CD-D4) and **on-hub detection** (CD-D6).

---

## 2. Decisions

### CD-D1 — Split: identity in `:home-device-service`, media in `:camera-service`

The maintainer's instinct — "a camera is a very different type of device, so a second
service" — is right about the *media*, but a camera is still a device: it has a name, a
room, an online state, and commands (PTZ, privacy mode, reboot). If a wholly separate
service owned all of that, Home's Devices tab would have to join two inventories, and
`name`/`room` would have two owners — the classic duplicated-inventory mistake.

So the line is drawn by *data shape*, not device category:

- **`:home-device-service` keeps the camera as a `HomeDevice`** — identity, room, online
  state, capability ids (`ptz`, `privacyMode`, `snapshot`), and `DeviceCommand` control.
  One inventory, one naming model, one rooms model, and — importantly — **one automation
  vocabulary**, so routines treat "camera" like any other device on both the trigger and
  action side.
- **`:camera-service` owns the media/event plane** — `CameraClip`, `CameraEvent`,
  live-session establishment, retention policy. It *references* the device id; it does not
  re-describe the device.

This is the same shape as the rest of the platform: services are facets over shared realm
data, not silos per device class (cf. apps as lenses over one realm). Video doorbells fold
in naturally — the button and chime are `HomeDevice` capabilities, the video is
`:camera-service`.

Naming note: `:camera-service` without the `home-` prefix, because a camera is not
exclusively a smart-home concern (doorbells, and later Mia's "show me the front door"),
matching `:file-service`/`:notification-service` rather than `:home-device-service`.

### CD-D2 — ONVIF/RTSP first, Matter second

Confirmed with the maintainer. Matter 1.5 (Nov 2025) finally added cameras — a `Camera`
device type over *Camera AV Stream Management*, live view via mandatory *WebRTC Transport
Provider/Requestor* clusters using ordinary STUN/TURN, a *Push AV Stream Transport*
cluster for event-triggered clips, plus PTZ, detection/privacy zones and multi-stream
configs. It is unambiguously the right *destination*: an open standard whose live-view
architecture is the same one we would design anyway (CD-D4).

But it is the wrong *starting point* for us, on three counts:

1. **Installed base.** As of mid-2026 Matter cameras are months old: SmartThings was the
   first controller to ship support, with the Aqara Camera Hub G350 (March 2026) the first
   certified camera and Eve/XThings following; Apple, Google and Alexa support was still
   pending as of June. ONVIF/RTSP works with essentially every prosumer and PoE camera on
   the market today, including €60 ones.
2. **The controller lift.** Consuming Matter cameras means becoming a full Matter
   **controller** — commissioning flow, fabric credentials, certification if we ever want
   to claim "Works with Matter". That is a strategic undertaking with its own doc, not a
   slice inside a camera track.
3. **Nothing is wasted.** ONVIF and Matter converge on the same shapes at our seam
   (snapshot, event, clip, WebRTC live view), so the `CameraBridge` abstraction (CD-D3) is
   written once and Matter becomes a second backend.

The proprietary cloud cameras (Ring, Nest, Arlo, Blink, Wyze) are explicitly **not a
target**: no local protocol exists by design, since the subscription is the business
model. Integrating them would mean cloud APIs under vendor permission or reverse
engineering that breaks on firmware updates — and would route the user's video through
someone else's servers, which is the entire thing we are arguing against.

### CD-D3 — `CameraBridge` seam, parallel to `DeviceBridge`

Hub-side, a `CameraBridge` interface beside HD-D3's `DeviceBridge`: discover cameras,
fetch a snapshot, subscribe to camera-reported events, open a media source for
recording/relaying, execute PTZ. Backend #1 `OnvifCameraBridge` (WS-Discovery + ONVIF
profiles for control/events, RTSP/RTP for media); backend #2 `MatterCameraBridge` later.

Per hub proposal §6, heavy media handling is native-Linux territory, not JVM: the hub
orchestrates and a sidecar does the pixels. The reference implementations to lean on
rather than reinvent are **go2rtc** (restreaming/transcoding across RTSP/WebRTC/HLS) and
**Frigate** (local NVR + detection). As with zigbee2mqtt in HD-D3, deploying a proven
upstream service as a systemd unit beats embedding a media stack in our tree.

### CD-D4 — Live view: WebRTC, relay does signaling + TURN only

This is the one place cameras add genuinely new platform surface, and it collides with the
hub's **no-listener** decision (hub proposal §11). Three options:

1. **App → camera RTSP directly.** LAN-only, exposes camera credentials to every client,
   no story off-network. Rejected as the primary path (fine as a debug affordance).
2. **Hub serves the stream.** Requires an inbound listener on the hub — reopens a settled
   decision, adds discovery plus a second auth surface. Rejected.
3. **WebRTC between app and hub.** ✅ Chosen. ICE is precisely the mechanism for
   establishing a peer-to-peer media path **without either side running a listener** —
   both make outbound connections and hole-punch, falling back to TURN when they can't.

House Rule #1 survives structurally, not by policy: DTLS-SRTP keys are negotiated
peer-to-peer between app and hub, so a TURN server we operate forwards **opaque packets it
cannot decrypt**. The relay's new role is a short-lived **signaling rendezvous** — and we
already have exactly that shape in the pairing offer mailbox (`POST`/`GET` keyed by a
hash, short TTL, public material only). Signaling must *not* ride ordinary sync: at HD-D2's
3–5 s poll cadence a call setup would take 10–15 s.

New relay surface ⇒ threat-model amendment lands with CM-3, and TURN is an operational
cost item (bandwidth) worth pricing before it ships.

### CD-D5 — Clips ride the content plane; continuous recording stays local

A `CameraClip` is a `Syncable(Eager)` metadata row (camera device id, start/end, trigger,
duration, thumbnail ref) pointing at a **content-addressed, CEK-sealed blob** — the same
plane Drive files use. Consequences, all free:

- The hub encrypts before anything leaves it; the relay sees ciphertext (HR #1).
- Playback in any app is the **existing D2b streaming path**
  (`OpenContentStream`/`ReadContentRange`) — seekable video over the ordinary op channel,
  already built and shipped.
- Retention and quota ride `retention-quota-model.md` unchanged.

**Continuous 24/7 recording stays hub-local and is never synced** — it would obliterate any
storage tier, and its value (scrub back through yesterday) is served from the hub's own
disk. **Event clips sync** by default (they are what you want on your phone, and what
should survive the hub being stolen — which is precisely the failure mode a burglary
camera exists for). Per-camera retention windows, defaults conservative.

### CD-D6 — Detection on the hub, never in a cloud

v1 (CM-2) uses the camera's own motion events over ONVIF — no ML, works everywhere.
CM-4 adds **on-hub object detection** (person/vehicle/animal), which is where the AI
co-computer of hub proposal §5 stops being abstract: local person-detection instead of a
cloud subscription is the single most legible privacy differentiator against Ring and
Nest, and Frigate is the existence proof that it runs on modest hardware. Detection
results become `CameraEvent` rows → the Home Activity feed → routine triggers ("when a
person is detected at the front door after sunset").

### CD-D7 — Face *recognition* is excluded, not deferred

Person **detection** ("a person is present") is ordinary computer vision. Person
**identification** ("that is Alice") is **biometric data under GDPR Art. 9**, a special
category with a materially different legal regime — and this project is already an EU
seller of record with a public privacy posture to defend. It is therefore **out of scope
as a matter of policy**, not sequencing; if it is ever revisited it starts with counsel,
an explicit-consent design, and a threat-model chapter of its own. Recording the exclusion
here so a future contributor doesn't treat it as an obvious next feature.

⚠ **Scope clarification (2026-07-27), so this does not read as contradicted by Drive.**
[`inference-service.md`](inference-service.md) IN-D3 permits **opt-in face clustering in the
user's own photo library**. That is not a reversal of this decision — the two situations
differ on every axis that matters. CD-D7's subject is a camera classifying **non-consenting
third parties** (visitors, delivery drivers, passers-by) **continuously**, in a security
context, where nothing supplies a lawful basis for the bystanders. Drive's is a user
labelling photographs they chose to keep, on their own device, off by default and offered
only in Settings, where the household exemption (Art. 2(2)(c)) is in play and — under E2E —
we never see the images or the templates (HR#1), so we are structurally not the controller.
IN-D5 further keeps the *synced* artifact a per-photo **name the user typed**, never a
biometric template. **Camera-side recognition of people who walk past stays excluded**;
⚖️ the library case is a counsel-review item before release, not an open question of design.

### CD-D8 — Threat model: the most sensitive data class in the house

Amendments land per slice; the shape:

- **In-use plaintext on the hub** (CM-1+) — decoded frames while recording/detecting.
  Inside the realm trust domain (the user's hardware), same class as the §5.3 inference
  note, but continuous rather than bursty.
- **At rest** — clips are CEK-sealed on the content plane (encrypted); the hub's local
  continuous buffer is plaintext on disk, covered by the H3 **LUKS** posture.
- **Network ingest** (CM-1) — an ONVIF camera on the LAN is untrusted input, exactly like
  HD-2's RF ingest: validate and bound names/attributes before they become realm rows
  rendered in every app. Camera credentials live on the hub (LUKS again).
- **New relay surface** (CM-3) — signaling rendezvous + TURN; media opaque by
  construction (CD-D4).
- **Who may watch** — today every realm device is a fully trusted peer, so *any* paired
  device can view *any* camera. That is acceptable for a single-household v1 and
  unacceptable the moment shared realms or household members with different rights are
  real; per-camera viewing rights need the **granular per-app/per-file capability track**
  already flagged as future work in the iOS route-1 migration (§P0). Say so plainly in the
  UI rather than implying finer control than exists.
- **Consent and the bedroom-camera problem** — a recording indicator that is not
  suppressible from the app, a hardware-honest privacy mode (prefer cameras with a
  physical shutter), and explicit surfacing of "this camera is recording and everyone in
  your household can see it".

---

## 3. Phasing

Each slice is independently useful; the ladder is deliberately cheapest-first.

- **CM-1 — camera appears, with a picture.** `:camera-service` module set; ONVIF discovery
  + snapshot via `OnvifCameraBridge`; the camera registers as a `HomeDevice`
  (`:home-device-service`) and Home's Devices tab shows a still that refreshes on demand.
  Reuses the existing image/thumbnail path end-to-end — no new media machinery at all.
  Threat model: ingest + in-use plaintext.
- **CM-2 — event clips.** Camera-reported motion → `CameraEvent` rows in the Activity
  feed; clip recorded, sealed onto the content plane, played back through the existing
  streaming path. Retention window per camera.
- **CM-3 — live view.** WebRTC per CD-D4: relay signaling mailbox + TURN, hub-side session
  management via go2rtc, app-side player. The slice with the new relay surface and the
  biggest threat-model delta. Two-way audio is a stretch here.
- **CM-4 — on-hub detection.** Person/vehicle classification on the hub (AI co-computer
  path), richer `CameraEvent`s, detection/privacy zones, routine triggers.
- **CM-5 — Matter cameras.** `MatterCameraBridge` behind the same seam; gated on the
  separate decision to become a Matter controller.

**Dependency:** CM-1 starts after **HD-2** (Zigbee on the Pi). The bulbs prove the spine —
inventory, commands, rooms, Home rendering, the Pi bridge story — on hardware that fails
cheaply. Cameras then only have to prove the media plane.

## 4. Pointers

- [`home-device-service.md`](home-device-service.md) — the device spine; camera identity
  and control live there (CD-D1), and the `DeviceBridge` seam that `CameraBridge` parallels.
- [`hub-hardware-and-management-service.md`](hub-hardware-and-management-service.md) §5
  (tiered inference / AI co-computer — CM-4's home), §6 (sidecar posture), §11 (the
  no-listener decision CD-D4 must respect), §12.1 (LUKS).
- [`drive-streaming-preview.md`](drive-streaming-preview.md) — the D2b pull range-source
  that clip playback reuses unchanged.
- [`file-content-addressing.md`](file-content-addressing.md) — CEK-sealed content plane
  (CD-D5).
- [`retention-quota-model.md`](retention-quota-model.md) — the three-tier storage model
  clips must respect.
- Matter 1.5 camera spec (CSA, Nov 2025) — CM-5's target; its WebRTC live-view
  architecture independently matches CD-D4.
