# Deploying the apex web frontend to DigitalOcean (Terraform)

The apex (`web/apex`) is the TypeScript + React control-plane web app, served as a
**static SPA**. Deploying it is the web counterpart to the relay deploy
([`deploy-relay-digitalocean.md`](deploy-relay-digitalocean.md)): **one droplet, one
compose stack** (just Caddy, serving the built site with automatic HTTPS), all
provisioned by Terraform. It's a sibling of the relay deploy, not a replacement —
the two are separate hosts, separate DNS records, separate Terraform state.

There's nothing zero-trust to preserve here: the box serves only the public,
open-source apex build. The encrypted sync traffic never touches it — the SPA
talks directly to the relay's own origin (`relay.tenshu.app`), which is compiled
into the bundle at **build time** (see [Relay URL](#relay-url-build-time-not-runtime)).

## What's in the repo

| File | Purpose |
|------|---------|
| [`deploy/digitalocean-apex/`](../../deploy/digitalocean-apex/) | Terraform: droplet, firewall, reserved IP, DNS, cloud-init. |
| [`web/apex/docker-compose.yml`](../../web/apex/docker-compose.yml) | The run definition: one Caddy container serving the SPA (auto-HTTPS). |
| [`web/apex/Caddyfile`](../../web/apex/Caddyfile) | Static file server + SPA fallback + automatic Let's Encrypt. |
| [`web/apex/Dockerfile`](../../web/apex/Dockerfile) | Gradle builds the SDK bundle → pnpm builds apex → Caddy serves `dist/`. The Caddy half splits into `base` + **`prebuilt`** (`dist/` supplied by the caller) + **`full`** (built in-image, the default). |
| [`web/apex/apex.env.example`](../../web/apex/apex.env.example) | The handful of env knobs (TF fills these on the droplet). |
| [`.github/workflows/apex-image.yml`](../../.github/workflows/apex-image.yml) | CI: build + push the image to GHCR (the prebuilt-image path). |
| [`deploy/build-apex-image.sh`](../../deploy/build-apex-image.sh) | **Build an amd64 image from a natively-built SPA** — the only path that works on an arm Mac. `--ship <host>` loads it over ssh, no registry. |
| [`deploy/build-and-push-apex.sh`](../../deploy/build-and-push-apex.sh) / [`redeploy-apex.sh`](../../deploy/redeploy-apex.sh) | Local build/push + fast redeploy helpers. |

## The build, and why it's heavier than a normal SPA

Apex's JS depends on the Kotlin→JS SDK bundle (`:tenshu-sdk-js`), which is
gitignored and Gradle-built. So a self-contained image build runs **two toolchains
in sequence** (the `Dockerfile`'s three stages):

1. **JDK 21 + Gradle** → `:tenshu-sdk-js:jsBrowserDistribution` builds the bundle.
   This is the RAM-hungry half (multi-module Kotlin/JS compile + webpack), same
   profile as the relay's fat-jar build.
2. **Node + pnpm** → `pnpm -F apex build` (`tsc -b && vite build`) produces `dist/`.
3. **Caddy** → serves `dist/` with SPA fallback.

Because step 1 is heavy, the **prebuilt-image path is the recommended default** for
anything past a first stand-up: build the image once on a CI runner (plenty of
RAM), and the droplet just pulls it — deploys drop to a pull, and the runtime box
can be a 1GB droplet (Caddy serving static files idles in well under 512MB).

> ⚠⚠ **With CI down, building the image on an arm Mac is not merely slow — it emulates BOTH
> toolchains** (Kotlin/JS *and* pnpm/vite) under QEMU. That is why the deployed apex image sat at
> `7fd3786f` — a **2026-07-02** build — for eight weeks while the box looked perfectly healthy.
> [`deploy/build-apex-image.sh`](../../deploy/build-apex-image.sh) builds stages 1 and 2 **natively**
> and then assembles an image whose only instructions are COPY and metadata: a built SPA is static
> text, so only the Caddy base layer needs to be amd64. It also drops the image from ~88 MB to
> ~25 MB, since the toolchain layers stop riding along.
>
> ⚠ On that path **Vite has already inlined the relay URL and construction code** by the time the
> image is assembled, so pass them to the native build (`RELAY_URL=…`, `CONSTRUCTION_CODE=…`) — the
> Dockerfile's `ARG`s never see them there.

## Prerequisites

- A DigitalOcean account + an API token with write scope.
- An SSH key uploaded to DO (`doctl compute ssh-key list` for its fingerprint).
- Terraform >= 1.6.
- A domain you can point at the droplet.
- **If the repo is private:** a read-only GitHub token so the droplet can clone it
  (on-box build) — see [`deploy-relay-digitalocean.md`](deploy-relay-digitalocean.md#private-repo-the-on-box-clone-needs-credentials).
  The prebuilt-image path sidesteps repo access on the droplet entirely.

## Relay URL: build-time, not runtime

Vite inlines `import.meta.env.VITE_TENSHU_RELAY_URL` at build time, so **the relay
origin is fixed when the image is built** — it is not a runtime env var. A
*production* build already defaults to `https://relay.tenshu.app`
(`web/apex/src/lib/relayUrl.ts` + `realOnboardingSdk.ts`); `localhost:8080` only
applies in Vite `DEV`. So:

- **Relay is at `relay.tenshu.app`:** nothing to set — the default is correct.
- **Relay is elsewhere:** pass the URL when you build the image —
  `--build-arg VITE_TENSHU_RELAY_URL=…` (Dockerfile), the `relay_url`
  `workflow_dispatch` input (CI), or `RELAY_URL=… deploy/build-and-push-apex.sh`.
  On the on-box build path, Terraform threads `var.relay_url` → `apex.env` →
  the compose `build.args`. A *prebuilt* image ignores `relay_url` (it's already
  baked in) — cut the image against the right relay instead.

## Hiding the site before launch (construction gate)

To keep the apex behind a "putting on the finishing touches" curtain before marketing,
set a 6-digit code: visitors enter it once to reveal the site (remembered per browser via
`localStorage`). It threads exactly like the relay URL — `VITE_CONSTRUCTION_CODE`, a
**build-time** value (`web/apex/src/components/ConstructionGate.tsx`):

- **On-box build:** set `construction_code` in `terraform.tfvars` (→ `apex.env` →
  compose `build.args`), or `--build-arg VITE_CONSTRUCTION_CODE=424242`.
- **Prebuilt image:** the code is baked at CI build time — pass it as the build arg when
  cutting the image; a pulled image carries whatever code it was built with.
- **Lift the curtain at launch:** clear the value (empty = no gate) and redeploy.

> ⚠ **Polish, not security.** The whole apex bundle still ships to the browser, so the
> code only discourages casual visitors — it does not keep the product unreachable, and
> since this repo is open source the code itself isn't secret. The gate also never applies
> to the cross-origin proxy iframe (`drive.tenshu.app`'s hidden gateway) or to local dev.
> If you need to genuinely keep the site unreadable, gate at the **edge** instead — add a
> `basicauth` block to `web/apex/Caddyfile` (Caddy serves nothing until the password is
> supplied). That's a real lock; the construction gate is a curtain.

## Keeping the site public but signup closed (signup gate)

The construction gate hides *everything*. When you want the opposite — the marketing site
public, but nobody able to create an account yet — close the signup gate instead. It
threads exactly like the construction code, as `VITE_SIGNUP_DISABLED`, a **build-time**
value (`web/apex/src/lib/signupGate.ts`):

- **On-box build:** set `signup_disabled` in `terraform.tfvars` (→ `apex.env` → compose
  `build.args`), or `--build-arg VITE_SIGNUP_DISABLED=1`.
- **Prebuilt image:** `SIGNUP_DISABLED=1 deploy/build-apex-image.sh`, or the equivalent
  build arg in CI. A pulled image carries whatever it was built with.
- **Open the door at launch:** clear the value (empty = open) and redeploy.

**Any non-empty value closes it.** With it closed, `/onboarding` renders a "Tenshu isn't
open yet" notice and the landing CTA reads *Coming soon*. All three chooser doors are shut
— sign up, pair an existing device, and try-locally — because the gate sits on the route
above them.

⚠ **It does not touch returning visitors.** `/lock`, `/recovery` and `/control/*` stay
live, so a realm already in that browser can still unlock and open the control plane.
Widening the gate onto those would read as data loss rather than as a pre-launch notice;
`landingCtaShape.test.ts` pins that boundary.

### Running the working site on a test server at the same time

The gate defaults to **open**, and that is the point: a test deployment sets nothing and
gets the fully working site, while only the public instance carries the knob. So:

```bash
# the public box — signup closed
SIGNUP_DISABLED=1 ./deploy/build-apex-image.sh --tag "$(git rev-parse --short=8 HEAD)-closed" --push

# the test box — nothing set, everything works
./deploy/build-apex-image.sh --tag "$(git rev-parse --short=8 HEAD)-open" --push
```

⚠⚠ **These are two different artifacts.** Vite inlines the flag, so the test box does not
prove the exact bytes of the public one — the divergence is one boolean's branch, and the
tests cover both sides of it. Tag them so which is which is legible from `docker images`;
an untagged pair is how the wrong one ends up on the public box.

> ⚠ **A curtain, not security**, in the same sense as the construction gate: the relay's
> endpoints are unchanged and still answer anyone who calls them directly. This only stops
> the site pointing at them. Genuine gating belongs at the relay.

## First deploy

```bash
cd deploy/digitalocean-apex
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars: do_token, ssh_keys, domain, acme_email
# (see the DNS note below before setting manage_dns)
terraform init
terraform apply
```

Terraform prints `apex_ip` and `apex_url`. First boot takes ~3–8 min on-box (Docker
install + SDK bundle + Vite build + cert) or ~1–2 min on the prebuilt path. Watch it:

```bash
ssh root@<apex_ip> 'tail -f /var/log/tenshu-bootstrap.log'
# or, once compose is up:
ssh root@<apex_ip> 'docker compose -f /opt/tenshu/repo/web/apex/docker-compose.yml logs -f'
```

When healthy:

```bash
curl -fsS https://<domain>/      # => the apex index.html
```

## DNS — the one wrinkle vs. the relay: a shared zone

TLS is fully automatic (Caddy + Let's Encrypt over HTTP-01, auto-renew — same as
the relay; only `acme_email` is needed). DNS is the one manual bit, and it has an
extra twist here: **the relay module almost certainly already manages the
`tenshu.app` DO zone**, and two Terraform states can't both own the same
`digitalocean_domain` resource.

So the apex `terraform.tfvars.example` ships with **`manage_dns = false`** by
default. Pick one:

- **`manage_dns = false` (default):** `terraform apply`, take the `apex_ip` output,
  and add an `A` record for `domain` → that IP **wherever the zone is owned** — the
  relay module's DO zone (via the DO console, or as an extra `digitalocean_record`
  in that module) or your external DNS (Cloudflare, registrar).
- **`manage_dns = true`:** only if *this* module is the sole owner of the zone. If
  the zone already exists in your DO account, import it first:
  `terraform import digitalocean_domain.zone[0] tenshu.app`.

Either way, the cert issues automatically once the hostname resolves to the droplet
(Caddy retries with backoff — no redeploy needed if DNS lands late). Watch it:

```bash
ssh root@<apex_ip> 'docker compose -f /opt/tenshu/repo/web/apex/docker-compose.yml logs -f apex'
```

## Prebuilt-image path (recommended)

**Build the image — two interchangeable ways (same result):**

- *CI (free + unlimited on public repos):* [`.github/workflows/apex-image.yml`](../../.github/workflows/apex-image.yml)
  builds on every push to `master` that touches the web/SDK build closure, pushing
  to `ghcr.io/b3nedikt/tenshu-apex` (`:latest`, `:<short-sha>`, and any
  `workflow_dispatch` tag). Trigger a one-off build (and target a non-default relay
  via the `relay_url` input) from the Actions tab.
- *Locally (no CI dependency):* [`deploy/build-and-push-apex.sh`](../../deploy/build-and-push-apex.sh)
  runs the identical build from your machine. One-time setup:
  `echo "$GHCR_PAT" | docker login ghcr.io -u b3nedikt --password-stdin` with a
  `write:packages` PAT.

> ⚠ **Cross-arch local build (Apple Silicon): the buildx builder needs working DNS.**
> The script targets `linux/amd64` (the droplet's arch) through a `docker-container`
> buildx builder — the plain docker driver can't push a non-native platform. On a
> fresh or stale `tenshu-builder`, the BuildKit **sandbox can come up with no
> resolver**, even though plain `docker run` resolves fine. Both build stages then
> stall — the Gradle SDK bundle (`:tenshu-sdk-js:jsBrowserDistribution`) and the
> pnpm/vite step retry fetches (`repo.maven.apache.org: Name or service not known`)
> and fail ~50 min in. Recreate the builder with host networking so the sandbox
> inherits the host resolver:
>
> ```bash
> docker buildx rm tenshu-builder
> docker buildx create --name tenshu-builder --driver docker-container \
>   --driver-opt network=host --bootstrap
> ```
>
> Sanity-check DNS *before* committing to a ~45 min emulated build:
>
> ```bash
> printf 'FROM alpine\nRUN wget -qO- https://repo.maven.apache.org/maven2/ >/dev/null && echo REACHABLE\n' \
>   | docker buildx build --builder tenshu-builder --platform linux/amd64 --no-cache -f - . 2>&1 | grep REACHABLE
> ```
>
> Under emulation the amd64 Gradle+pnpm build takes ~45 min — use the CI path when
> it's available (`workflow_dispatch`) for the native-speed build.

**Deploy it:** [`deploy/redeploy-apex.sh <apex_ip> [tag]`](../../deploy/redeploy-apex.sh)
SSHes in, `docker compose pull`s the image, and restarts the stack. Pin the short
sha rather than `:latest` so you know exactly what's running and can roll back.

**GHCR auth on the droplet:** if the package is private (GHCR's default), log the
droplet in once (`ssh root@<ip> 'echo "$GHCR_READ_PAT" | docker login ghcr.io -u b3nedikt --password-stdin'`,
a `read:packages` PAT), or make the package public.

**First boot can pull too (tiny box from the start).** Set `apex_image_tag` in
`terraform.tfvars` and cloud-init pulls on first boot instead of building — so you
can also set `droplet_size = "s-1vcpu-1gb"`. Push the image to GHCR *before*
`terraform apply`. Set `ghcr_token` if the package is private. Leaving
`apex_image_tag` empty keeps the on-box build path (the zero-infra default before
any image exists).

## Why a separate droplet from the relay?

Different content, different lifecycle, and clean isolation. The relay holds
ciphertext on a stateful SQLite volume that must stay single-writer; the apex is
stateless public static files. Keeping them on separate hosts means an apex
redeploy can't touch the relay's data plane (or its Terraform state), the
zero-trust boundary stays crisp, and you can resize/rebuild either independently.
Per-app subdomains (`drive.tenshu.app`, …) from `docs/web/architecture.md` Phase 8
follow this same shape — copy this module, change the `domain` and the image.

The stack is vendor-neutral (a generic Dockerfile + plain Caddy + env config), so
the same compose stack runs on Hetzner, a static host, or your own box. Pick on
DX/price, not lock-in.
