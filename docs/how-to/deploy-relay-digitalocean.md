# Deploying the sync relay to DigitalOcean (Terraform)

> ⚠⚠ **RETIRED.** The relay runs on Scaleway ([`deploy/scaleway-relay/`](../../deploy/scaleway-relay/));
> this runbook describes the box it replaced. Never `terraform apply` `deploy/digitalocean/`: its state
> still owns the `relay` A record at the old reserved IP and would repoint production DNS at the dead
> droplet. The teardown order is at the top of [`deploy/digitalocean/README.md`](../../deploy/digitalocean/README.md).

The sync relay (`:sync-relay-server`) is the only server-side component today.
It's a single Ktor/Netty service plus a file-backed database, so the deployment
is deliberately small: **one droplet, one block-storage volume, one compose
stack** (relay + Caddy for TLS), all provisioned by Terraform. House Rule #1 is
unchanged throughout — the relay stores ciphertext only; nothing here decrypts
user data.

This is the DigitalOcean counterpart to [`deploy-relay-fly.md`](deploy-relay-fly.md).
Fly remains a valid deployment; DO suits the current single-writer SQLite phase
well because the workload *is* a single VM with a disk (see "Why DO / why not
Fly" below). The relay code is identical either way — config still comes from
`RelayConfig.fromEnv()`, so nothing in `:sync-relay-server` changes.

## What's in the repo

| File | Purpose |
|------|---------|
| [`deploy/digitalocean/`](../../deploy/digitalocean/) | Terraform: droplet, volume, firewall, reserved IP, DNS, cloud-init. |
| [`sync-relay-server/docker-compose.yml`](../../sync-relay-server/docker-compose.yml) | The run definition: relay container + Caddy (auto-HTTPS). |
| [`sync-relay-server/Caddyfile`](../../sync-relay-server/Caddyfile) | Reverse proxy + automatic Let's Encrypt. Replaces Fly's `force_https`. |
| [`sync-relay-server/Dockerfile`](../../sync-relay-server/Dockerfile) | Unchanged, provider-agnostic: Gradle assembles the fat jar, a slim JRE runs it. |
| [`sync-relay-server/relay.env.example`](../../sync-relay-server/relay.env.example) | The handful of env knobs (TF fills these on the droplet). |

## How automated is it?

Fully — one `terraform apply` stands up everything and the droplet boots itself
into a running, TLS-terminated relay. There are exactly two things you set by
hand first:

1. `terraform.tfvars` — your DO token, SSH key, `domain`, `acme_email`.
2. (Only off-DO DNS) an A record, if you set `manage_dns = false`.

The trade-off that makes "one command" possible: **the fat jar is built _on_ the
droplet** on first boot (cloud-init clones the public repo at `git_ref` and runs
`docker compose up --build`). That keeps the loop dependency-free — no registry,
no CI — at the cost of a slower first boot and a droplet sized to build (the
default `s-2vcpu-4gb`, plus a swapfile cloud-init adds defensively). For a leaner
runtime box or faster deploys, use the **prebuilt-image path** below instead.

## Prerequisites

- A DigitalOcean account + an API token with write scope.
- An SSH key uploaded to DO (`doctl compute ssh-key list` to get its fingerprint).
- Terraform >= 1.6.
- A domain you can point at the droplet (managed by DO, or anywhere if you set
  `manage_dns = false`).
- **If the repo is private:** a read-only GitHub token so the droplet can clone it
  on first boot (see "Private repo" below). Public repo => skip this.

## Private repo (the on-box clone needs credentials)

The on-box build clones the repo over HTTPS in cloud-init, which is non-interactive
— so a **private** repo fails with `could not read Username for 'https://github.com'`.
Supply a read-only token via the `git_token` variable: a fine-grained PAT scoped to
**only this repo**, **Contents: Read-only**. cloud-init splices it into the clone URL;
an empty `git_token` (the default) does an anonymous clone, which is what you want
once the repo is public — then just drop the variable.

Because it's baked into the clone URL, the token lands in Terraform state and the
droplet's cloud-init metadata + bootstrap script. The minimal single-repo read-only
scope is what keeps that acceptable; revoke it when you no longer need it. (The
prebuilt-image path below sidesteps repo access on the droplet entirely.)

## First deploy

```bash
cd deploy/digitalocean
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars: do_token, ssh_keys, domain, acme_email
terraform init
terraform apply
```

Terraform prints the `relay_ip` and `relay_url`. First boot then takes ~3–8 min
to install Docker, build the jar, and obtain a cert. Watch it:

```bash
ssh root@<relay_ip> 'tail -f /var/log/tenshu-bootstrap.log'
# or, once compose is up:
ssh root@<relay_ip> 'docker compose -f /opt/tenshu/repo/sync-relay-server/docker-compose.yml logs -f'
```

When healthy:

```bash
curl -fsS https://<domain>/      # => "Tenshu Sync Relay is running!"
```

If DNS is elsewhere (`manage_dns = false`), create an A record for `domain` → the
`relay_ip` output **before** the cert can be issued (Let's Encrypt validates over
HTTP-01 on port 80).

## DNS & TLS — what's automatic vs. what you do once

**TLS / Let's Encrypt: fully automatic — nothing to install or renew.** Caddy (in the
compose stack) requests a certificate for `RELAY_DOMAIN` over ACME HTTP-01 on first boot,
terminates TLS, and auto-renews before expiry. No certbot, no cron, no manual cert files.
The only input is `acme_email`. (To rehearse against the Let's Encrypt staging CA while
sorting out DNS — its rate limits are far looser — uncomment the `acme_ca` line in
[`sync-relay-server/Caddyfile`](../../sync-relay-server/Caddyfile).)

**Domain / DNS: one manual step Terraform can't fully remove**, depending on where your DNS
is hosted:

- **DNS managed by DigitalOcean** (`manage_dns = true`, the default): Terraform creates the
  A record. **But** DO can only manage the zone if your domain's nameservers at the
  registrar already point at DO (`ns1.digitalocean.com`, `ns2…`, `ns3…`). That delegation is
  a one-time action at your registrar — the DO API can't do it for you (unless the domain is
  registered through DO). Until the nameservers are delegated, the `digitalocean_record`
  apply will fail because the zone isn't DO-controlled.
- **DNS anywhere else** (Cloudflare, your registrar's own DNS): set `manage_dns = false`,
  `terraform apply`, take the `relay_ip` output, and create an A record for `domain` → that
  IP yourself.

Either way it's a small one-time DNS action — nameserver delegation, or a single A record.

**Ordering — the cert issues *after* the hostname resolves to the droplet.** HTTP-01
validation reaches your domain on port 80, and the reserved IP isn't known until `apply`
runs, so the natural sequence is: `apply` → read `relay_ip` → ensure DNS points at it →
Caddy obtains the cert. **You don't have to time this precisely**: if DNS isn't ready when
the droplet first boots, Caddy keeps retrying with backoff and succeeds on its own once the
record propagates — no redeploy needed. Watch it land with:

```bash
ssh root@<relay_ip> 'docker compose -f /opt/tenshu/repo/sync-relay-server/docker-compose.yml logs -f caddy'
```

A `certificate obtained successfully` line means HTTPS is live; repeated ACME challenge
failures almost always mean DNS isn't pointing at the droplet yet, or port 80 is blocked
(check the firewall's `ssh_allowed_cidrs` didn't accidentally narrow 80/443 — those stay
open to the world by design).

## Configuration contract

All runtime config still comes from the environment via `RelayConfig.fromEnv()`,
exactly as on Fly. `docker-compose.yml` pins the safe prod defaults so they can't
be left wrong:

| Var | Set by | Value | Notes |
|-----|--------|-------|-------|
| `PORT` / `BIND_HOST` | compose | `8080` / `0.0.0.0` | Caddy proxies to it on the internal network; the relay port is never published to the host. |
| `DEV_MODE` | compose | **`false`** | Hardcoded — the `X-Dev-Owner` auth bypass must be off on any public bind. (Unset would also mean `false` now — `fromEnv` fails closed — but the explicit value stays.) |
| `DATABASE_URL` | compose | `jdbc:sqlite:/data/relay.db` | `/data` is the mounted block-storage volume. Durable across restart/rebuild. |
| `BLOB_DIR` | compose | `/data/relay-blobs` | Content + share chunk storage, same volume. |
| `CORS_ALLOWED_ORIGINS` | `deploy.env` (TF) | _(apex default)_ | Set only if the web app is served from a non-apex origin. A non-empty value **replaces** the default. |
| `RELAY_DOMAIN` / `ACME_EMAIL` | `deploy.env` (TF) | your values | Drive Caddy's automatic TLS. |
| `ENTITLEMENT_ISSUER_PUBKEY` / `ENTITLEMENT_ENFORCE` | `deploy.env` | _(unset / `false`)_ | Gate writes on a commerce entitlement token. Off by default (existing relays unaffected). To turn on: see [Enforcing entitlement on writes](#enforcing-entitlement-on-writes) below. |
| `GENESIS_RATE_LIMIT_PER_HOUR` | `deploy.env` (TF) | `20` | Per-IP hourly cap on the unauth `POST /sync/realm` bootstrap — **on by default**. Caddy forwards `X-Forwarded-For`, so the cap keys on the real client IP. Raise behind a shared NAT; `0` disables. See [DDoS & abuse](#ddos--abuse-resistance). |
| `PAIRING_REQUEST_RATE_LIMIT_PER_HOUR` | compose default | `120` | Per-IP hourly cap on the unauth `POST /sync/pairing/request` — **on by default**, same keying as the genesis cap. A joiner posts once per attempt. `0` disables. Not a Terraform variable; export it for `docker compose` to change it. |
| `RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE` | compose default | `6` | Per-IP (IPv6: per /64) **per-minute** cap on the unauth `GET /sync/recovery-challenge` — **on by default**, same forwarded-IP keying. One challenge per recovery attempt, so a burst of 6 then one per 10 s; past it `429` + `Retry-After`. `0` disables. Not a Terraform variable. Threat model T-RC-08 (a). |

The only infra secret is the DO token (and later, DB/S3 creds when the
multi-node backend lands). JWT verification keys are public.

## Enforcing entitlement on writes

By default the relay **verifies** a presented entitlement token but **gates nothing** —
anyone with a valid device JWT can write. Enforcement (rejecting writes that lack a live,
owner-bound entitlement) is a deliberate, reversible env flip. The code is already wired
(`RelayConfig.entitlementEnforce` / `RelayEntitlementVerifier`); there is nothing to build,
only to switch on once the preconditions hold.

**What it gates.** Writes only — `/sync/push`, content-blob `PUT`, share + grant drops.
Reads/downloads of already-stored data stay ungated, so an expired token (commerce outage,
past-grace cancellation) never locks a user out of their own data; it only pauses *new*
writes. It does **not** cover `POST /sync/realm` (genesis): a brand-new user has no
entitlement and no realm/device to bind a token to, so bootstrap is necessarily open — that
endpoint is bounded by the per-IP rate limit instead (see below), not by entitlement.

**Preconditions — flip only when ALL hold, or you 402 every write:**

1. The commerce issuer is deployed and reachable
   ([deploy-commerce-digitalocean.md](deploy-commerce-digitalocean.md)). It prints its public
   key on boot: `Commerce issuer public key (set relay ENTITLEMENT_ISSUER_PUBKEY to this): …`.
2. Your shipped clients actually **fetch and present** `X-Entitlement` on writes (the
   `EntitlementTokenClient` path). If a deployed client doesn't present a valid, live,
   owner-bound token, enforcement rejects *all* its writes — there is no grace fallback.
3. `ENTITLEMENT_ISSUER_PUBKEY` matches the issuer that minted those clients' tokens (and
   `ENTITLEMENT_ISSUER_KEY_ID` matches if commerce sets `SIGNING_KEY_ID`). The flag is a
   **no-op without the pubkey** — the relay logs a warning and gates nothing.

**Flip:**

```sh
# in deploy.env (or the Terraform vars that template it)
ENTITLEMENT_ISSUER_PUBKEY=<base64 from the commerce boot log>
ENTITLEMENT_ISSUER_KEY_ID=          # only if commerce sets SIGNING_KEY_ID
ENTITLEMENT_ENFORCE=true
```

then recreate the relay container (`docker compose --env-file deploy.env up -d`, or
`terraform apply` if you set it via the TF vars). On boot the log states the active posture:
`Entitlement ENFORCED on writes …` vs `… gates nothing …`. **Roll back instantly** by setting
`ENTITLEMENT_ENFORCE=false` (or clearing the pubkey) and recreating — verify-only resumes with
no data migration.

**Staged rollout (recommended):** deploy commerce → confirm clients present `X-Entitlement` on
real writes (watch the relay's request logs) → *then* flip `ENTITLEMENT_ENFORCE=true`. Flipping
before clients carry tokens is the one footgun here.

## Checking it is alive: `/health`, not `/`

⚠⚠ **`GET /` is a static string** (`"Tenshu Sync Relay is running!"`) and cannot fail while the
process is up. That is not a theoretical gap: for the whole 2026-08-14 outage it answered **200**
while the blob volume was 100 % full and *every* `POST /sync/push` returned 500. Reads were perfect,
so from the outside a dead relay and a healthy one looked identical.

```bash
curl -s --max-time 10 https://relay.tenshu.app/health
# {"status":"ok","durable":true}        200 — the blob volume accepted a write
# {"status":"degraded","durable":true}  503 — it refused; check `df -h /mnt/relay_data` first
# {"status":"ok","durable":false}       200 — ⚠⚠ NO DATABASE_URL. In production this is itself
#                                       the alarm: an in-memory relay wipes on restart.
```

`/health` writes a few bytes to the blob volume and deletes them — the same call that throws when
the disk is full. ⚠ It reports the **binary** question only; "the disk is at 85 %" is monitoring's
job (ST-0), because a check that goes red early is a check people learn to ignore.

⚠⚠ **It deliberately does not touch the database.** `RelayDatabase` serialises every caller on one
lock over a single SQLite connection, so even a `SELECT 1` would queue behind whatever sync request
holds it — reporting a *busy* relay as *down*. A full volume breaks the SQLite write too, so probing
the disk covers both anyway.

⚠ Give any monitor an explicit timeout. Like commerce's, this check can be slow rather than failing,
and a stall without `--max-time` reads as "no answer yet" rather than "down".

## Disk: there are two of them, and only one is obvious

⚠⚠ **The droplet has two independent disks, and each has filled in production for a
different reason.** Confusing them costs time during an incident, because the symptom —
`no space left on device` — is identical.

| | Mount | Size | Holds | Fills because |
|---|---|---|---|---|
| **Data volume** | `/mnt/relay_data` (`/dev/sda`) | `data_volume_size_gb`, currently 50 G | `relay.db` + `relay-blobs/` | users upload content and **nothing reclaims it** (`drive-upload-stop-and-reclamation.md` §4) |
| **Root disk** | `/` (`/dev/vda1`) | 24 G, fixed by droplet size | OS, Docker images, **container logs** | old images accumulate, and json-file logs used to grow unbounded |

```bash
ssh root@<ip> 'df -h / /mnt/relay_data; docker system df'
```

**Growing the data volume** — a parameter, not a redeploy, and no downtime:

```bash
# 1. In terraform.tfvars: data_volume_size_gb = <new size>
# ⚠⚠ -target is NOT optional. A plain apply currently wants to REPLACE the droplet
#    (user_data drift forces replacement); see the track doc §4.5 / ST-5.
terraform apply -target=digitalocean_volume.relay_data

# 2. DO grows the block device; the filesystem does not follow on its own.
ssh root@<ip> 'resize2fs /dev/sda && df -h /mnt/relay_data'
```

⚠ DO block volumes **only ever grow** — there is no shrink, so oversizing is a
permanent bill. ⚠ `terraform.tfvars` and `*.tfstate` are gitignored, so the current size
lives only on the operator's machine and in DO; a fresh clone reads the `variables.tf`
default.

**Reclaiming the root disk:**

```bash
ssh root@<ip> 'docker image prune -a -f'   # stale relay images, ~600 MB each
```

Caddy's access log is not among them since 2026-10-23: it is a 7-day file in the `caddy_logs` volume
([`server-logging.md`](server-logging.md)).

⚠⚠ **Container logs are capped at 250 MB each (`50m` × 5) by the `x-logging` anchor in
`docker-compose.yml` — but only for containers created *after* that landed.** Docker
applies the logging driver at container creation, so a long-running container keeps
whatever policy it was born with, and truncating its log file by hand does not change
that. To adopt the cap, recreate:

```bash
ssh root@<ip> 'cd /opt/tenshu/repo && docker compose -f sync-relay-server/docker-compose.yml \
  --env-file /opt/tenshu/deploy.env up -d --force-recreate'
```

⚠⚠ **The two disks are coupled, which is what made 2026-08-15 confusing:** the data volume
filled → the relay answered 500 to every write → the error storm inflated the container logs
→ the *root* disk filled → `docker load` failed mid-deploy, while the relay itself was still
happily serving reads. Treat a full root disk as a possible *symptom* of the data volume,
not only as its own problem.

## Scaling caveat (read before scaling out)

Persistence is **SQLite on a single volume** — a single writer. **Do not run
more than one droplet / replica** until the relay grows a Postgres backend
(`sync-relay-persistence.md` §8 Phase 4, designed but not built). This Terraform
provisions exactly one droplet by design. Vertical scaling (resize the droplet)
is fine; horizontal scaling would split state across volumes and corrupt the
registry. Going multi-node is a *different* infra module that pairs with the
Postgres + S3 backend — and that's the natural moment to move to managed
Postgres + Spaces, or DOKS, not before.

## Prebuilt-image path (faster / smaller runtime box)

Building on-box is convenient but heavy — the multi-module Gradle build is
RAM-hungry and the default 4GB droplet only survives it because cloud-init adds
swap (and even then it's slow; see the heap caps in `sync-relay-server/Dockerfile`).
The better path once you're past first-boot is to build the image **off the box**
and have the droplet just pull it: deploys drop to seconds, and the runtime box no
longer needs to be sized to build (a 1GB droplet idles the relay happily).

This path is now wired up — `docker-compose.yml`'s `relay` service already carries
both `image:` and `build:`, so the same file supports pulling *or* on-box building
with no edit.

**Build the image — three ways:**

- *CI (free + unlimited on public repos):* [`.github/workflows/relay-image.yml`](../../.github/workflows/relay-image.yml)
  builds on every push to `master` and on `v*` tags, pushing to
  `ghcr.io/b3nedikt/tenshu-relay` (`:latest`, `:<short-sha>`, and the tag/semver).
  Trigger a one-off build by hand from the Actions tab (`workflow_dispatch`).
- *Locally (no CI dependency):* [`deploy/build-and-push.sh`](../../deploy/build-and-push.sh)
  runs the identical `docker build`/`docker push` from your machine. One-time setup:
  `echo "$GHCR_PAT" | docker login ghcr.io -u b3nedikt --password-stdin` with a
  `write:packages` PAT.
- *Locally, natively, no registry (**the one that works when CI is down**):*
  [`deploy/build-relay-image.sh`](../../deploy/build-relay-image.sh) builds the fat jar on your
  machine (seconds) and assembles the amd64 image with **COPY and metadata only** — a jar is
  architecture-independent bytecode, so nothing about it needs amd64 *execution* and there is no
  emulation to sit through. `--ship <host>` sends it over with `docker save | ssh docker load`,
  which also sidesteps GHCR credentials entirely. Its twins are
  [`build-commerce-image.sh`](../../deploy/build-commerce-image.sh) and
  [`build-apex-image.sh`](../../deploy/build-apex-image.sh).

> ⚠ **Cross-arch local build (Apple Silicon): the buildx builder needs working DNS.**
> The script targets `linux/amd64` (the droplet's arch) through a `docker-container`
> buildx builder — the plain docker driver can't push a non-native platform. On a
> fresh or stale `tenshu-builder`, the BuildKit **sandbox can come up with no
> resolver**, even though plain `docker run` resolves fine. Gradle then spends the
> whole build retrying Maven fetches (`Could not GET 'https://dl.google.com/...'`,
> `repo.maven.apache.org: Name or service not known`) and fails ~50 min in. Recreate
> the builder with host networking so the sandbox inherits the host resolver:
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
> Under emulation the amd64 Gradle build takes ~45 min — use the CI path when it's
> available (`workflow_dispatch`) for the native-speed build, or
> [`build-relay-image.sh`](../../deploy/build-relay-image.sh) when it is not, which skips the
> emulated build altogether rather than making it faster.

**Deploy it:** [`deploy/redeploy.sh <relay_ip> [tag]`](../../deploy/redeploy.sh)
SSHes in, `docker compose pull`s the image, and restarts the stack (with the
`--env-file` so Caddy keeps its domain). Pin a tag (the short sha) rather than
`:latest` so you know exactly what's running and can roll back by re-running with
the previous tag.

**GHCR auth on the droplet:** if the package is **private** (GHCR's default), the
droplet must log in once so it can pull:

```bash
ssh root@<relay_ip> 'echo "$GHCR_READ_PAT" | docker login ghcr.io -u b3nedikt --password-stdin'
```

with a `read:packages` PAT. Or make the package **public** (GitHub → your profile →
Packages → `tenshu-relay` → Package settings → Change visibility) and no login is
needed — fine here, since the image is just the open-source relay jar.

**First boot can pull too (tiny box from the start).** Set `relay_image_tag` in
`terraform.tfvars` (to `latest` or a short commit sha) and cloud-init pulls the
image on first boot instead of building it — so you can also set
`droplet_size = "s-1vcpu-1gb"` in the same file. Push the image to GHCR *before*
`terraform apply` (CI or `build-and-push.sh`), since the droplet pulls it on boot.
If the package is private, set `ghcr_token` (a `read:packages` PAT) so the droplet
can authenticate; make the package public and you can omit it. Leaving
`relay_image_tag` empty keeps the original on-box build path — that's still the
zero-infra default for a first stand-up before any image exists.

After the box is up, `deploy/redeploy.sh <ip> <tag>` takes over for subsequent
deploys regardless of which path first boot used.

## Geography: one region, and what that costs distant users

The droplet is pinned to one datacenter (`fra1` by default). DigitalOcean does **nothing**
automatic about this — no anycast, no geo-routing, no replication; the reserved IP is itself
region-scoped. Typical RTTs to `fra1`: ~10–40 ms Western Europe, ~90 ms US-East, ~150 ms
US-West, ~170–280 ms Asia/Oceania.

That is mostly fine. Sync deltas are small and batched, so a push/pull is a couple of round
trips and even 250 ms is invisible. **File content is where it shows**, and the fix was a client
change rather than a second region: chunk transfers now overlap
([`content-plane-transfer-concurrency.md`](../proposals/content-plane-transfer-concurrency.md)),
because a serial `PUT` loop makes a large upload round-trip-bound instead of bandwidth-bound.
The next lever is moving blobs to Spaces + CDN (that doc's Phase 2) — additive infra that leaves
the single-writer constraint below untouched. A second *relay* region is blocked on Postgres,
for the reason the next section gives.

## Why DO (and why not Fly) for now

Fly is optimised for many stateless instances run close to users (edge,
fast global deploys — real-time/low-latency apps are its sweet spot). The relay
is the opposite shape today: one stateful authority holding ciphertext, a single
SQLite writer that **must not** be replicated yet. A droplet is just "a VM with a
disk," which fits that constraint directly — no `min_machines_running` pinning,
no single-attach-volume gymnastics. Because every backend is vendor-neutral
(plain JDBC, S3-via-endpoint, env config, a generic Dockerfile), the provider
choice is reversible: Hetzner (cheapest raw VMs), AWS/GCP, or Oracle's free tier
would all run this same compose stack. Pick on DX/price, not lock-in.

## DDoS & abuse resistance

> Status: **partially built.** Two things ship and are **on by default**: the per-IP
> rate limit on `POST /sync/realm` (`GENESIS_RATE_LIMIT_PER_HOUR`, default 20) and a
> per-IP budget for **failed authentications** (`AUTH_FAILURE_LIMIT_PER_HOUR`,
> default 600 — see "Failed authentication is the other unauth surface" below). The
> rest of this section — pairing/recovery limits, per-owner quotas, the CDN decision
> — records the shape of the problem and the intended layering so the next person
> doesn't have to rederive it. Rate limiting is also named in
> `sync-relay-persistence.md` §8 Phase 5 and `sync-backend-relay-plan.md` §6.2.

**The current "indirect protection" is friction, not a control.** Registration
costing a bit of work raises the per-request price slightly, but it doesn't
*bound* aggregate load — a botnet still floods. So: not enough on its own for
deliberate abuse. Defence wants to be layered, cheapest-and-broadest first.

**1. Network / volumetric (L3-L4).** A bare droplet has no scrubbing. Options,
in order of effort:

- DO Cloud Firewall (already provisioned) drops everything except 80/443/22 —
  removes the trivial surface, not volumetric floods.
- A CDN/proxy in front (Cloudflare et al.) absorbs L3/L4 and adds L7 rate limiting
  + WAF cheaply. **Decision required, and it's a real one:** such a proxy
  terminates TLS, so it sees request bytes + metadata (owner/device IDs, sizes,
  timing) and your device JWTs. That's the *same* metadata the relay already sees
  — but it hands it to a third party, which is a meaningful step away from the
  zero-trust posture. Mitigations: Cloudflare L4 Spectrum (no HTTP termination),
  or accept it as availability-over-metadata-minimisation and document it in the
  threat model. Worth a threat-model amendment either way (House Rule #2).

**2. Application L7 — the soft spots are the pre-auth endpoints.** Authenticated
routes (`/sync/*`, `/content/*`) already require a device-key JWT, so abuse there
binds to a registered device and is rate-limitable per-owner. The exposed surface
is the routes that *can't* be pre-authenticated:

- **Realm bootstrap (`POST /realm`)** — anyone can claim an `ownerId` with a
  master public key. Mass creation bloats the registry. **Defence (built):** a
  per-IP hourly rate limit via Ktor's `RateLimit` plugin, keyed on the
  `X-Forwarded-For` client IP (Caddy sets it; `XForwardedHeaders` consumes it),
  configurable via `GENESIS_RATE_LIMIT_PER_HOUR` (default 20, `0` disables) —
  `RelayConfig.genesisRateLimitPerHour`. Still open as future hardening: a
  hashcash-style proof-of-work so mass creation is expensive *without* needing
  accounts (fits zero-trust — no identity required).
- **Pairing (`POST /sync/pairing/request`, etc.)** — an attacker who doesn't know
  the pairing secret can't target a real pairing, but can spam random addresses to
  grow mailboxes. Defence: keep pairing entries ephemeral + short TTL (already the
  design — they stay in-memory), cap pending entries per address, per-IP rate
  limit, size cap.
- **Recovery (`GET /recovery-challenge`, `POST /recovery-claim`)** — challenge
  issuance is cheap to spam, and since F8 it is issued to anyone. **Defence (built):**
  the store is ephemeral (5-min TTL) and capped at 10 000 outstanding (503 past it),
  and a per-IP (IPv6: per /64) limit of `RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE`
  (default 6, `0` disables) answers `429` + `Retry-After` — `ClientRateLimiter`, a
  size-bounded bucket map rather than Ktor's plugin, so rotating sources cannot grow
  it. Filling the store now takes ~280 sources (threat model T-RC-08).

**Failed authentication is the other unauth surface, and the per-owner design
cannot cover it (built 2026-08-18).** The reasoning above — *"authenticated routes
already require a device-key JWT, so abuse there binds to a registered device and is
rate-limitable per-owner"* — has a hole: a request whose auth **fails** binds to no
owner, so there is no per-owner bucket to put it in. `POST /sync/log` with a junk JWT
is answerable by anyone, and answering it costs an Ed25519 verification, which is
orders of magnitude more expensive than reading a header. That asymmetry is the lever.

Found by measurement, not audit: on 2026-08-18 a desktop that had lost its
registration sent **41 `POST /sync/log` per minute for 31 hours** (~30,000 requests),
every one a 401. That was a client bug (fixed since: the 401 marker was missing on the two busiest routes) — but ⚠⚠ **client-side backoff
is not a control**: an attacker does not run our client and will not back off, so the
relay cannot rely on it for anything.

`AuthFailureLimiter` gives each client IP an hourly budget of failed authentications
(`AUTH_FAILURE_LIMIT_PER_HOUR`, default 600, `0` disables). Past it the relay answers
**without verifying**, so the crypto cost disappears.

- ⚠⚠ **The response stays `401`, deliberately — do not "improve" this to a `429`.**
  The client keys its backoff, its "device not recognised" state and its self-repair
  off seeing a 401. A 429 would silence all three, so throttling the storm would
  *re-create* it, and the device would report a network outage instead of an identity
  problem. `Retry-After` carries the advisory signal instead. Pinned by
  `AuthFailureLimiterTest.pastTheBudgetTheAnswerIsStill401_notA429`.
- ⚠⚠ **Unauthenticated routes are untouched**, which is load-bearing rather than
  incidental: a throttled device is exactly a device that needs to re-pair, so gating
  pairing on the same budget would make the state unrecoverable. The limiter lives
  inside `JwtAuthenticator` for that reason.
- ⚠ **NAT is the known weakness.** One household shares a public IP, so a broken
  device can spend its neighbours' budget. Only *failures* consume it (a healthy
  device never touches the bucket) and the default is sized well above the worst real
  fleet state measured (~370 failures/hour), but the sharper key — the token's claimed
  `(iss, sub)`, readable without verifying — is unbuilt, and only means anything
  stacked *under* an IP bucket since a forger mints claims freely.
- ⚠ It does **not** bound volumetric floods. A rejected request still costs a
  connection, a TLS session and a Caddy round trip; only item 1 above addresses that.

Mechanism: Ktor's `RateLimit` plugin with per-route buckets — strictest on the
three unauth routes above, looser per-owner buckets on the authed ones. Pair with
**per-owner quotas** (max devices, max mailbox bytes, max content bytes) so a
single owner can't exhaust storage; the GC sweeps already exist, quotas are the
missing cap. Some caps already exist in this spirit — e.g.
`maxOpenInvitesPerOwner` (default 32) bounds live contact invites per owner.

**3. The publisher service (future, different trust model).** When app developers
register apps, that's *not* zero-trust the same way — it's a developer-facing
account surface. It should sit behind real accounts (email/OAuth), per-account
rate limits, and possibly review/payment, rather than the anonymous proof-of-work
model the relay uses. General principle to carry over: **anything that mints
persistent server-side state from an unauthenticated caller needs either an
account gate or proof-of-work + a quota.** Design it when the service is specced.

**Cheap wins to do early** (before any of the above is fully built): the firewall
(done — in the TF), Caddy's request-body cap (done — in the Caddyfile), per-IP
rate limiting on the unauth routes (done for `POST /realm` — extend the same
pattern to pairing + recovery), and per-owner storage quotas. The
CDN/zero-trust decision can wait until there's real traffic to protect, but it's
the biggest single lever against volumetric attacks when you get there.
