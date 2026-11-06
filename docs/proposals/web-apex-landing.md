# Tenshu Web Apex Landing — Routing & Composition

**Status:** Draft v0.1
**Scope:** The unauthenticated landing page at `tenshu.app/` and how it composes with the
onboarding flows in [`web-onboarding-ux.md`](web-onboarding-ux.md) and the public narrative
in [`web-about-tenshu-public.md`](web-about-tenshu-public.md).

This document resolves a small but blocking question: **what does a first-time visitor to
`tenshu.app/` see?** Today the apex's `/` route mounts `OnboardingRouter`, which branches on
IndexedDB state and either drops the user into the new-user flow (`web-onboarding-ux.md`
§4b) or the existing-realm flow (§4a). That's correct behaviour for someone who just closed
the tab and reopened it, but it's wrong as a first impression for someone who clicked a
link to `tenshu.app` cold — they get a state-machine prompt instead of any sense of what
the platform is.

This doc commits to a routing model that gives both audiences what they need, defines the
landing page's composition (hero, About blocks, CTA), and specifies the smart-CTA logic that
adapts to local state without making the page feel mode-switched.

It does *not* respec the eight content blocks in `web-about-tenshu-public.md` — that doc is
the source of truth for copy and mockups. This doc owns *where* those blocks render and
*what sits around them.*

---

## 1. Decision: `/` Is Always the Landing

The apex root route always renders the public landing. Authentication state never causes an
automatic redirect away from `/`.

**Rationale**:

- A landing page is shareable. `tenshu.app/` should always show the brand and value prop, so
  a tweet, blog post, or word-of-mouth referral lands somewhere coherent. Auto-redirecting
  returning users away from `/` would mean *"this URL doesn't render the same thing for the
  person sharing it as for the person clicking it"* — confusing, especially in a privacy
  context where users are trying to evaluate the product.
- It's a clean surface for marketing. As Tenshu's audience grows, `/` is where landing-page
  copy iterates fastest. Keeping its render uncoupled from auth state simplifies that loop.
- The cost — a returning, signed-in user has to click once to get to their control plane —
  is small, and we make that click obvious via the smart CTA (§4).

**Counter-considered**: redirect returning users with valid sessions straight to the control
plane home. Rejected because it makes the landing URL behave inconsistently and makes "go
look at the apex's marketing page again" annoying. A separate `/about` route was also
considered as the marketing page (with `/` redirecting to onboarding); rejected for the same
shareability reason — `/` is the canonical entry point for an unfamiliar visitor.

---

## 2. Route Map

The apex's React Router config grows to:

| Route         | What renders                                                                              |
|---------------|-------------------------------------------------------------------------------------------|
| `/`           | **Landing** (this doc) — hero + About blocks + sign-up/launch CTA                         |
| `/vision`     | **Vision** page — the one-sentence vision, four problems, three technologies (`VisionPage.tsx`) |
| `/start`      | `OnboardingRouter` — the "are you new or returning?" branch from onboarding-ux §4         |
| `/start/new`  | `OnboardingFlow` (web-onboarding-ux.md §4b new-user)                                      |
| `/start/pair` | `OnboardingFlow` (web-onboarding-ux.md §4a existing-realm pairing)                        |
| `/lock`       | `LockedFlow` — returning-user unlock (web-onboarding-ux.md §3)                            |
| `/recovery`   | `RecoveryFlow` — recovery-phrase unlock (web-onboarding-ux.md §6)                         |
| `/control`    | Control plane home (specced in [`web-control-plane.md`](web-control-plane.md))            |
| `/files/*`    | Drive UI — embedded today; will move to `drive.tenshu.app` per `web/architecture.md` §3.1 |
| `/dev`        | Development surface — kept until the SDK runtime is fully wired                           |

Today's `OnboardingRouter` mounted at `/` becomes a small dispatcher behind the landing's
CTA: when the user clicks Get Started, the apex inspects IndexedDB state and pushes them
to the right sub-route under `/start` (or `/lock`, or `/control` if they already have an
active session). The landing itself never mounts the router — it's static content with a
smart button.

---

## 3. Landing Composition

The landing renders **all eight blocks** from `web-about-tenshu-public.md` as a single
scrollable page. The blocks aren't optional or progressively disclosed; the page is meant
to be scrollable end-to-end without intermediate clicks.

**Above the fold**: Block 1 (Hero) only. Headline, subhead, primary CTA, hero visual (M1).
The user can immediately decide whether to scroll or click.

**Below the fold, in order**:

- Block 2 — The Problem
- Block 3 — Tenshu's Answer
- Block 4 — Your Realm
- Block 5 — Honest About the Limits
- Block 6 — What You Can Do Today
- Block 7 — What's Coming
- Block 8 — Sign-up + FAQ

Block 8's primary CTA mirrors Block 1's so a user who scrolls all the way through doesn't
have to scroll back up. The FAQ collapses by default, per the source doc.

**Scroll-down affordance from the hero**: the secondary CTA `Learn more` (per
`web-about-tenshu-public.md` §3) is an anchor link that scrolls smoothly to Block 2. On
viewports tall enough that Block 2 isn't naturally cued, a small chevron-down indicator at
the bottom of Block 1 reinforces that there's more below. Honour `prefers-reduced-motion`
for the smooth-scroll behaviour.

---

## 4. Smart CTA Logic

The Get Started CTA in Block 1 (and again in Block 8) adapts to local IndexedDB state. The
apex inspects state synchronously on landing mount — the same probe `OnboardingRouter`
already does today.

| Local state                | Block 1 CTA               | Block 8 CTA                           | Click destination |
|----------------------------|---------------------------|---------------------------------------|-------------------|
| Empty IDB                  | `[Get started]`           | `[Sign up]` + `I already have Tenshu` | `/start`          |
| Has state, session valid   | `[Open Tenshu]`           | `[Open Tenshu]`                       | `/control`        |
| Has state, session expired | `[Welcome back — unlock]` | `[Welcome back — unlock]`             | `/lock`           |

The button's *position* on the page never changes — only its label and destination. This
is important: the visual hierarchy of the landing must be the same for every visitor. A
returning user shouldn't experience Block 1 as a structurally different page than a new
visitor would.

The CTA's secondary slot (the "I already have Tenshu" link from Block 8) is hidden when
the user already has state — they obviously have Tenshu, they're just on the landing page.
It only shows for empty-IDB visitors.

A returning user with active session **also** sees a small persistent banner at the very
top of the landing: *"You're signed in as {device_name}. Open your control plane →"*. This
gives them an unmissable shortcut without having to scroll through all eight blocks. The
banner dismisses on click and stays dismissed for the tab session (via `sessionStorage`).

---

## 5. Brand Chrome

The landing is rendered without the post-auth navigation chrome (the `Layout` component
with NavLinks). It uses a marketing-page chrome instead:

- **Header**: Tenshu wordmark on the left; on the right, two links — `Docs` (links to the
  GitHub docs) and the smart CTA mirrored from Block 1.
- **Footer**: copyright, links to whitepaper / threat-model / repo / contact.
- ⚠ **Shared with the static support pages** (2026-10-15): the header gained a `Support` link
  beside `Docs`, and both header and footer are prerendered into `/apps/<app>/support/…` and never
  hydrated there — so they must keep working with JavaScript off.
  `store-presence-and-support-pages.md` §4.6.
- **Vision link** (2026-10-22): the header's centre reads `Vision · Docs · Support`. `/vision` is a
  public SPA route in `MarketingLayout`, one URL for every language (detected, like `/`). ⚖️ Its
  problem paragraphs argue from market structure and never state a company's intent as fact or
  name one; keep that when editing the copy (`vision.*` in `landing.json`).

The post-auth `Layout` chrome (the wrapper for `/control`, `/files`, `/dev`) is reserved
for routes inside the app. It does not render on `/`. This split is what visually
communicates *"you're now inside Tenshu"* once the user clicks the CTA.

---

## 6. Edge Cases

**Deep links to apps without auth.** A user visiting `tenshu.app/files/folder/x` without a
valid session is redirected to `/lock` (or `/start` if no IDB state), with the original
URL preserved in `?next=`. After unlock or onboarding completes, they land at the original
destination, not the control plane home.

**Session expires while reading the landing.** Doesn't matter — the landing is
unauthenticated content. The smart CTA re-evaluates state at click time, so a session
expiring mid-scroll is handled by the click flowing into `/lock`.

**User reaches the landing from inside the app.** Clicking the wordmark in the post-auth
chrome routes to `/` but does *not* sign the user out. They see the landing in its
returning-user state with the persistent banner. Clicking the wordmark a second time has
no new effect.

**Pre-fetching across the apex/subdomain boundary.** Once `drive.tenshu.app` exists as a
real subdomain (web roadmap Phase 8), the landing's Block 6 mockups should not pre-fetch
the Drive subdomain — that would warm a service worker for a user who hasn't onboarded.
Use plain images / SVGs for those mockups; cross-origin pre-fetch happens after onboarding.

**Reduced motion.** The hero animation (M1) and the E2EE animation (M4) honour
`prefers-reduced-motion: reduce` and degrade to static frames. The page doesn't animate
the scroll-down chevron when reduced motion is requested.

**Print / reader-mode.** The landing should print and reader-mode cleanly. No critical
content lives only inside animated mockups; static fallback frames carry the message.

---

## 7. Tracking and Telemetry

**Default position**: the landing collects nothing. No analytics SDK, no first-party
telemetry beyond the standard server-side request log. This is consistent with the apex's
zero-trust-backend invariant from `web/architecture.md` §3.3 — a landing page that pings a
third-party analytics endpoint contradicts the privacy story we're selling.

If, later, a privacy-respecting metric (e.g. self-hosted, IP-anonymised, no cookies) is
added, it should be specced as an amendment here with explicit threat-model implications.

---

## 8. Implementation Notes

- The eight blocks are React components under `web/apex/src/components/landing/`, one per
  block. Each block accepts no props except optionally a `forwardedRef` for scroll-targeting.
  Reuse across the future investor / developer decks (per `web-about-tenshu-public.md` §13)
  is via importing the same components into other pages.
- The smart CTA is a single component (`<LandingCta />`) that reads IDB state via the SDK's
  session probe and renders the right label + destination. Block 1 and Block 8 both render
  this component; they share state via React context, not separate probes.
- The persistent "you're signed in" banner is a separate component that sits above the
  marketing header. It uses `sessionStorage` to remember dismissal for the tab session.
- IDB state probing happens once per route mount (not per block), so visiting `/` doesn't
  N-times-probe for an N-block page.
- Routing change in `App.tsx`: replace `<Route index element={<OnboardingRouter />} />`
  with `<Route index element={<LandingPage />} />` and move `OnboardingRouter` under
  `<Route path="start" element={<OnboardingRouter />} />`. The router itself is unchanged;
  only its mount point moves.

---

## 9. Open Items

- **Visual brand.** The landing's typography, palette, illustration style — all inherit
  from the design round called for at the platform level. Until that lands, the landing
  ships with the existing M3 tokens from `web/apex/src/index.css` and placeholder visuals
  for the mockups. This doc is content-and-routing-only.
- **Mockup illustrations (M1–M7).** *Resolved:* Blocks 1–4 ship code-drawn line art
  ([`docs/design/landing-illustrations.md`](../design/landing-illustrations.md)) and the Apps
  section real screenshots in the carousel; the rest are text-only
  (`web-about-tenshu-public.md` §12).
- **`/about` redirect.** The current `web-about-tenshu-public.md` references "embedded in
  `web/apex/`'s home route" — this doc commits to that. If a separate `/about` URL is
  desired (e.g. for direct linking to specific blocks), it 302-redirects to `/#block-N`
  rather than rendering separately. Decide before launch.
- **Footer content beyond the basics.** Once Tenshu has a community / contact strategy
  (open item in `web-about-tenshu-public.md` §14), the footer grows.
- **A11y of the smart-CTA banner.** The persistent "you're signed in" banner needs careful
  ARIA semantics so screen readers don't announce it on every block. Live-region politeness
  level: `polite`; mounted once at the top of the page, not per-block.

---

## 10. References

- [`docs/web/architecture.md`](../web/architecture.md) — the architecture this landing
  reflects. §3 (apex/subdomain), §4 (auth states), §8 (onboarding flow).
- [`docs/proposals/web-about-tenshu-public.md`](web-about-tenshu-public.md) — content for
  the eight blocks. Source of truth for copy, mockups, and tone.
- [`docs/proposals/web-onboarding-ux.md`](web-onboarding-ux.md) — the flow the smart CTA
  routes into. §4 covers the new-vs-returning branch this doc invokes via `/start`.
- [`docs/proposals/web-control-plane.md`](web-control-plane.md) — the control plane the
  smart CTA routes into for signed-in users.
- [`docs/web/roadmap.md`](../web/roadmap.md) — Phase 7.1 (now sized concretely by this doc).
