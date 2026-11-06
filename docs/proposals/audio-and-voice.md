# Audio & Voice — Decisions Note (pre-hardware)

**Status:** Decisions only, settled 2026-07-25 in conversation with the maintainer.
**No phasing, no schemas, no code — deliberately.** The speaker hardware does not exist,
the voice pipeline is gated on the audio path being real, and the one standard that would
change the analysis (a Matter streaming-speaker device type) is still in draft. This note
exists so these decisions don't have to be re-derived when the speaker becomes real; the
proper proposal gets written then, starting from here.

**Scope:** how audio gets in and out of the house — the voice path for Mia, playing the
user's own audio, and playing third-party music. Answers "is there an audio standard we
should adopt?" (mostly no) and "what should a Tenshu speaker actually be?" (a microphone
we control).

---

## 1. The question that started it

Whether Matter — or any standard — gives us audio streaming to and from smart speakers,
the way Matter 1.5 gave us cameras. Short answer: **no**, with one draft exception, and
the reasons are different for output and input.

---

## 2. Decisions

### AV-D1 — Three problems, not one; only the third is blocked

"Audio control" conflates three things that have entirely different answers. Keeping them
separate is the whole of the analysis:

1. **The voice path** (mic → wake word → STT → Mia → TTS out). Not a streaming-standard
   problem at all: a latency-sensitive path between *our own* hardware, where a standard
   buys nothing and costs control. Ours to design (AV-D4/AV-D5).
2. **The user's own audio from their realm** (a podcast in Drive, a voice memo, their own
   library). Entirely ours already: content plane + the shipped D2b streaming path,
   structurally identical to camera clips (`camera-service.md` CD-D5). Solved.
3. **Third-party music** (Spotify, Apple Music, YouTube Music). The actual wall — and it
   is a **licensing** wall, not a technical one. No open protocol reaches those catalogues,
   by design.

### AV-D2 — For third-party content: be a *receiver*, not a source

The way past (3) is not to obtain a licence. If the user's phone pushes audio to our
speaker over AirPlay 2 or Cast, **the phone holds the licence and does the DRM**; we
render PCM. That is exactly how Sonos and Bose ship Spotify without being Spotify, and it
means we never need a partnership with any music service.

Cost: MFi licensing (AirPlay) or Chromecast built-in certification for a *commercial*
product — not for a self-hosted hub. Tension to decide consciously rather than by default:
Cast receivers in particular are cloud-entangled in ways that sit awkwardly with our
posture.

Corollary — **driving the user's existing speakers needs no new protocol from us**.
AirPlay 2, Cast, UPnP/DLNA push, Sonos's local API and plain Bluetooth A2DP all exist and
Home Assistant drives HomePods and Sonos through them today. That is integration work, not
protocol design. **Snapcast** is the open path for synchronised multi-room playback of the
user's *own* library — but note it is playback-only, one-directional, and requires
Snapclient *on the endpoint*, so it can never drive a HomePod or an Echo.

### AV-D3 — Audio does not ride Thread/802.15.4, and its absence there is correct

Recurring question, settled with numbers so it stops recurring: Thread runs on IEEE
802.15.4 — 250 kbit/s raw PHY, 127-byte frames — and after MAC overhead, CSMA contention,
6LoWPAN fragmentation and retries, usable application throughput over a mesh is in the
*tens* of kbit/s. Raw 16 kHz 16-bit mono PCM, the ordinary input to speech recognition, is
**256 kbit/s — more than the entire raw PHY rate before any overhead**. A single low-bitrate
Opus voice stream (~16–24 kbit/s) could technically be shoehorned through, but it would
consume most of the usable capacity of a mesh whose purpose is tiny infrequent control
messages, starving the sensors and actuators it exists for — and battery end-devices
duty-cycle their radios precisely to avoid continuous traffic. Music is not arguable.

Strongest evidence it is the wrong radio class: **Bluetooth LE Audio exists**. BLE has
several times Thread's bandwidth and the industry still had to invent a new codec (LC3)
and isochronous channels to carry audio properly. Nobody attempted it on 802.15.4.
Empirically, every smart speaker uses WiFi for audio; Echo and HomePod use Thread only as
*border routers* controlling other devices.

⇒ **Keep the planes orthogonal.** The 802.15.4 dongle is the control plane
(`home-device-service.md` HD-2). WiFi/Ethernet/IP is the media plane (voice, camera,
music). Unifying them would only degrade the control mesh. This is why voice satellites
are WiFi boards, not Thread nodes.

### AV-D4 — The speaker is a Tenshu client: control on our stack, media on WebRTC

The Wyoming protocol (the small open standard behind Home Assistant's local-voice
ecosystem) has the right *vocabulary* — audio-start, audio chunks, transcript, TTS
response — and the wrong *transport*: plain, unauthenticated, LAN-trusting TCP. Since
audio from inside the house is the most sensitive stream in the system, we replace the
transport and keep the semantics.

Split, mirroring the camera decision (`camera-service.md` CD-D4):

- **Control + identity → our stack.** The speaker is an **EPC client of the hub's proxy**
  — the External Proxy Connection role the hub proposal §1 already reserves for the hub as
  "brokering endpoint". This yields an authenticated, encrypted channel; pairing via the
  **same H2 printed-label flow** (a speaker has no display either, so the mechanism
  generalises for free — `hub-hardware-and-management-service.md` §11); revocation through
  ordinary device management; and the speaker appearing in Home like any other device.
- **Media → WebRTC/SRTP**, negotiated over that authenticated channel. *Not* the op
  channel: our transport is reliable-ordered and callstack-framed, which is right for rows
  and bulk content and wrong for 50 frames/second where a lost packet must be **skipped,
  not retransmitted** — reliable delivery turns loss into head-of-line stalls, the exact
  failure that makes an assistant feel broken. On-LAN, ICE resolves to a direct host
  candidate, so **TURN never enters the picture** and DTLS-SRTP keys are negotiated
  peer-to-peer: no LAN-trust assumption at all.

Deliberately **not** a full realm device: as an EPC client the speaker holds no realm
keys, which is least privilege for a device sitting exposed in a living room while the hub
sits in a cupboard.

### AV-D5 — Two satellite tiers, stated rather than hidden

Our client stack is KMP (JVM/Android/iOS/JS). It **does not run on an $8 ESP32-S3**, so
AV-D4 would silently foreclose the cheap-satellite path — $8–15 per room, or a ~$59
off-the-shelf voice puck — that makes whole-home coverage affordable. Rather than let that
happen by accident:

- **Tenshu speaker (our hardware, Pi-class):** full client per AV-D4 — authenticated,
  encrypted, revocable, no LAN-trust assumption.
- **Third-party Wyoming satellites (ESP32 and friends):** supported as an explicitly
  **lower-trust tier** — opt-in, LAN-scoped, and *labelled as such in the UI*. The honest
  statement is "this device is as trusted as your network", which is also simply true: a
  microcontroller cannot do meaningful key custody, so wrapping it in our protocol would be
  security theatre. The weak link is the device, not the wire.

⚠ AV-D4/AV-D5 imply a **LAN listener** somewhere for satellite sessions, which
`home-device-service.md` HD-D2 explicitly rejected for the command fast-path. The
reasoning may well come out differently here (a voice satellite is on the LAN by
construction and has no relay alternative), but it must be an **argued exception**, not an
accident — settle it in the real proposal.

### AV-D6 — Where STT runs decides what crosses the wire

An $8 satellite cannot run Whisper, so realistically **audio crosses the LAN** from
satellite to hub and recognition happens on the hub / AI co-computer
(`hub-hardware-and-management-service.md` §5 tiered inference: wake-word on a tiny
always-on tier, STT and reasoning bursting to the co-computer). The honest boundary is
therefore: *audio stays on your own network, text and intent stay on the hub, nothing
reaches the internet* — which makes the satellite→hub hop the one link that needs
securing, exactly the gap AV-D4 closes for our own hardware and AV-D5 concedes for cheap
ones.

### AV-D7 — The competitive premise has expired, and the incumbent just vacated it

Recording the motivation, because it is unusually clear-cut. Alexa detects its wake word
locally on a low-power DSP and then **streams actual audio to Amazon** (plus a pre-roll
buffer); recognition, intent and response all happen server-side. A narrow "Do Not Send
Voice Recordings" on-device option existed from 2021 — on three Echo models, US English
only — and **Amazon removed it in March 2025**, stating that Alexa+'s generative features
"rely on the processing power of Amazon's secure cloud".

The *technical* premise for cloud speech recognition has expired: Whisper-class models run
locally with sub-second responses on modest hardware, which the Home Assistant local-voice
stack demonstrates daily. What persists is the business reason. So "your voice never leaves
your house" has gone from a hobbyist claim to an **uncontested** position in the mainstream
market — and it is precisely what §5's tiered inference is built to deliver.

---

## 3. Standards status (as of 2026-07, re-check before building)

- **Matter has no audio transport.** It carries media *control* (Media Playback, Audio
  Output, Level Control) inherited from the TV device types; the audio bits never travel
  over Matter. Matter 1.5 added real AV transport but **scoped to the camera device type**
  (WebRTC, including two-way talk for doorbells); 1.5.1 refined multi-stream video/audio
  for cameras. **Matter 1.6 (June 2026) added no new device categories** — Joint Fabric,
  NFC commissioning, thermostat behaviour, security infrastructure.
- **A streaming-speaker device type is in development** at the CSA, explicitly positioned
  as an open alternative to AirPlay and Cast with group/multi-room playback, spec work led
  by Legato (ex-Apple/ex-Sonos engineers). No announced timeline. Calibration: cameras were
  "coming" for roughly three years before the 1.5 spec, with certified devices four months
  after. **Plan for it to matter eventually, and not soon** — and note it would address
  AV-D1 case (3) only, never the microphone.
- **No standard exists, or is proposed, for getting a microphone stream *out* of a
  commercial smart speaker** — Echo→Alexa, HomePod→Siri, Nest→Google, no third-party API at
  any price. This is not a licensing gap that could be paid to close; the mic *is* the
  business model. **Hence AV-D4's conclusion: the Tenshu speaker exists because it is the
  only way to have a microphone we control.** Playback is table stakes the user can get
  from any AirPlay speaker they already own.

## 4. Pointers

- [`hub-hardware-and-management-service.md`](hub-hardware-and-management-service.md) §5
  (tiered inference — where STT/LLM live), §11 (the printed-label pairing AV-D4 reuses),
  §1 role 1 (the hub as EPC broker).
- [`camera-service.md`](camera-service.md) CD-D4 — the WebRTC live-media decision AV-D4
  mirrors; CD-D5 — the content-plane path AV-D1 case (2) already rides.
- [`home-device-service.md`](home-device-service.md) HD-D2 — the LAN-listener rejection
  AV-D5 must argue an exception to; HD-D3 — the sidecar posture (zigbee2mqtt, go2rtc,
  and here Snapcast/Wyoming) this note extends.
- [`home-and-mia-apps.md`](home-and-mia-apps.md) D4 (voice behind a capability flag),
  D7 (a phone can't do screen-off far-field hotword — the gap the speaker fills).
