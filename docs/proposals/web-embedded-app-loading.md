# Embedded-app load smoothing — fade-in + a host↔app ready handshake

**Status:** Draft v0.1 (2026-06-26). **DEFERRED — no code.** Documents a
smoothing solution for the cold-load pop-in of iframe-hosted apps so it can be
built the moment production (or a larger/third-party app) makes it worth it.
Build trigger in §5.

**Owner:** Benedikt

**Relationship:** extends [ADR 0020](../adr/0020-web-app-structure.md) (apex hosts
apps in an iframe) and the `@tenshu/app-kit` layer sketched there + in
[web-design-system.md](web-design-system.md).

---

## 1. The observation

When you open Drive from the apex launcher, the embedded app **pops in**: the
iframe shows an empty `surface`-coloured panel for a beat, then the whole Drive
UI appears at once. Measured (headless, dark mode, real pointer):

- **Not a white flash** — the iframe area is `surface`-dark from the first frame
  (the document-canvas `color-scheme` +
  `html { background }` fix holds).
- **Not a double-load** — the iframe loads **exactly once** (StrictMode or not).
- The sequence is: `~0–100ms` blank dark surface → `~180ms` full Drive UI snaps
  in. The gap is the iframe fetching Drive's JS + mounting React.

So it's a **cold-load pop-in**, inherent to hosting a separate app on its own
origin in an iframe. Two amplifiers:

- **Dev mode** makes it worse: Vite serves the embedded app's modules unbundled,
  so mount is slower and the blank window longer. A production bundle pops in
  faster — which is exactly why we're deferring (see how it feels in prod first).
- **App size + third-party** make it worse the other way: a heavier first-party
  app, or an arbitrary third-party app (post-Rei, architecture.md §7), has a
  bigger bundle and a longer blank window. This is the real reason to have a
  solution ready rather than ignore it.

## 2. Why not "just fix it now"

The pop-in is a one-time-per-open cosmetic on the **dev** build; production is
faster and may make it a non-issue. Building the smoothing now risks polishing a
problem that barely exists in prod, and the *robust* version (a ready handshake,
§4) is most valuable for **third-party** apps that don't exist yet. So: document
the design, ship nothing, revisit on the §5 trigger.

## 3. Solution A — host-side fade-in (the cheap 80%)

Entirely in the host (`DriveEmbed`), no cooperation from the embedded app:

```tsx
const [loaded, setLoaded] = useState(false)
return (
  <div className="relative -mx-6 -my-8 md:-mb-8">
    {!loaded && <EmbedLoading /> /* centred spinner / "Loading Drive…" on bg-surface */}
    <iframe
      title="Drive"
      src={driveOrigin()}
      onLoad={() => setLoaded(true)}
      className={`block h-[calc(100vh-3.5rem)] w-full border-0 bg-surface
                  transition-opacity duration-200 ${loaded ? 'opacity-100' : 'opacity-0'}`}
    />
  </div>
)
```

Turns "blank → snap" into "subtle loading → fade-in." **Caveat:** `onLoad` fires
when the embedded **document** loads — a hair *before* its React tree mounts and
paints — so a bare fade can still reveal a momentarily-empty app. Mask it with a
slightly longer fade or a small post-`onLoad` delay. Good enough for first-party;
imperfect because the host is guessing when the app is *actually* ready.

## 4. Solution B — host↔app "app-ready" handshake (the robust version)

The host shouldn't guess. The embedded app tells it when it's truly ready:

- The embedded app posts `{ type: 'tenshu:app-ready' }` to its parent once its
  React tree has mounted **and** first meaningful content has painted (e.g. after
  the first route's initial query settles, or a `requestAnimationFrame` after
  mount for an instant shell).
- The host keeps the iframe at `opacity-0` + loading state until it receives that
  message (with a timeout fallback to `onLoad` so a silent app still appears).
- The `postMessage` is **origin-checked** both ways (host validates the message
  origin against the embedded app's; app validates the parent is apex).

This is the right primitive for **arbitrary apps**, including third-party: the
host can't know an unknown app's internal mount timing, so a declared ready
signal is the only reliable cue. It composes with the existing apex↔app bridge
(`@tenshu/sdk/bridge`) — same channel discipline, one more message type.

**Where the pieces live (per ADR 0020):**
- `@tenshu/app-kit` provides a one-liner the app calls — `signalReady()` — so
  every Tenshu app emits the signal uniformly (and third-party SDKs expose it).
- The host (apex `DriveEmbed` → a generic `AppEmbed`) owns the loading state,
  the fade, the timeout fallback, and the origin check.

**Threat-model note (when built):** a new cross-origin message type into apex is
a (tiny) surface — a malicious embedded origin could spam `app-ready`. It only
toggles a local opacity flag (no privilege), but the origin check + the fact that
the host already trusts what it chose to embed keep it benign. Amend the
threat-model in the implementing PR per House Rule #2 if the handshake rides the
bridge gateway.

## 5. Build trigger (when to un-defer)

Build **Solution A** when either:
- a production build still shows a *noticeable* pop-in on open, or
- a first-party app heavier than Drive ships (longer blank window).

Escalate to **Solution B** when:
- third-party app hosting becomes real (post-Rei) — at that point the host must
  not guess an unknown app's readiness, and `app-kit`'s `signalReady()` becomes
  part of the per-app contract.

Until then: ship nothing, watch production.

## 6. References

- [ADR 0020 — Web app structure](../adr/0020-web-app-structure.md) (iframe host, app-kit)
- [web-design-system.md](web-design-system.md) (`@tenshu/app-kit` / `web/shared`)
- [architecture.md §3 (apex/subdomain), §7 (third-party, Rei)](../web/architecture.md)
