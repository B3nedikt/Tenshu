# Ops secret custody — where deploy-time secrets live, and which ones must never be automated

**Status: Draft v0.1 — design pass, no code (2026-07-21).** Opened off the tiles publish, which
surfaced that the tiles signing key was living in `~/.tenshu/` — a directory
`scripts/reset-local-device-state.sh` deliberately wipes. It survived only because nobody ran a
desktop reset that week. That is the wrong reason for a client-pinned trust anchor to still exist.

This note covers **operator-held deploy-time secrets** — the credentials a maintainer needs on a
laptop to stand up or update infrastructure. It does **not** cover user data keys (A1–A4, realm
keys), which the threat model owns and which never leave a provisioned device.

---

## 1. What exists today

⚠ **The inventory as of 2026-07-21.** The relay and commerce have since moved to `deploy/scaleway-relay`
and `deploy/scaleway-commerce`, which carry a Scaleway `access_key`/`secret_key` in place of `do_token`,
plus the same git, GHCR and (commerce) issuer, database, SMTP, admin and Stripe secrets.
The content-plane and backup S3 keys live only in each box's `deploy.env` (`deploy/scaleway-relay/README.md`).
The DO modules below are retired but still hold their secrets on disk until torn down.

Every one of these is plaintext on the maintainer's disk. All are gitignored; none has leaked to
git. Values are never reproduced here — only names.

| Module | Sensitive variables |
|---|---|
| `deploy/digitalocean` (relay) | `do_token`, `git_token`, `ghcr_token` |
| `deploy/digitalocean-apex` | `do_token`, `git_token`, `ghcr_token` |
| `deploy/digitalocean-commerce` | `do_token`, `git_token`, `ghcr_token`, **`issuer_seed`**, `database_password`, `database_url`, `admin_token`, `stripe_secret_key`, `stripe_webhook_secret` |
| `deploy/tiles` | `do_token`, `spaces_access_id`, `spaces_secret_key` |
| (no module) | **`TILES_SIGNING_KEY`** — the tiles catalog signing key |

Each Terraform module stores these **twice**: once in `terraform.tfvars` (the input) and again in
`terraform.tfstate` (the output — Terraform records every resource attribute, including secrets).

## 2. The load-bearing distinction: rotation cost, not sensitivity

The instinct is to sort secrets by "how bad is a leak." That produces a flat list where everything
is Very Important and nothing gets treated differently. The useful axis is **what it costs to
recover from a compromise**, because that is what determines how much friction is worth paying.

| Secret | Compromise means | Recovery cost | Class |
|---|---|---|---|
| `TILES_SIGNING_KEY` | attacker signs a tile catalog every client accepts and MapLibre parses | **new pin in `ProxyModule.kt` + a shipped client release**; until then no catalog verifies at all | **Trust anchor** |
| `issuer_seed` (A29) | attacker mints unlimited "paid" entitlements | relay reconfig (`ENTITLEMENT_ISSUER_PUBKEY`) + redeploy + reissue outstanding entitlements | **Trust anchor (cheaper)** |
| `stripe_secret_key` | money movement, customer data | rotate in Stripe dashboard + redeploy | Operational (high impact) |
| `do_token` | full control of all infrastructure | regenerate in DO console + update 4 tfvars | Operational |
| `spaces_*` | overwrite a bucket of public open data | regenerate, scoped per bucket | Operational (low) |
| `database_password`, `admin_token` | commerce DB / admin console access | rotate + redeploy | Operational |
| `git_token`, `ghcr_token` | read the repo / push images | regenerate in GitHub | Operational |

**Two classes, and they should not share a mechanism.** A trust anchor's defining property is that
recovery requires shipping something to users — a client release, a relay reconfiguration. An
operational credential is recovered by clicking "regenerate." Treating them identically means either
over-protecting the cheap ones (friction nobody sustains) or under-protecting the expensive ones
(which is today's state).

⚠️ The two anchors differ sharply in recovery cost and that is worth keeping straight:
`TILES_SIGNING_KEY` compromise is the expensive one because the public half is **compiled into the
client** (`ProxyModule.kt`), so recovery waits on an app release. `issuer_seed`'s public half is
*configured* on the relay, so recovery is a redeploy — bad, not existential.

## 3. Decisions

### D1. Two custody classes, two mechanisms, deliberately.

**Operational credentials** may live in the macOS Keychain and flow through tooling and CI.
**Trust anchors** (`TILES_SIGNING_KEY`, `issuer_seed`) never enter automation, per
[`maps-region-delivery.md`](maps-region-delivery.md) D11. A single "put all secrets in the vault"
tool would quietly erase this distinction, which is the main thing this note exists to prevent.

### D2. Operational credentials move to the macOS Keychain behind a thin wrapper.

Naming convention `tenshu.<scope>.<name>` (e.g. `tenshu.tiles.spaces_secret_key`). Two scripts, kept
deliberately small — no custom crypto, no bespoke vault:

- `scripts/secret.sh set|get|list <scope> <name>` — a wrapper over `security add-generic-password` /
  `find-generic-password`.
- `scripts/with-secrets.sh <scope> -- <command>` — materialises a scope into the *child process's
  environment* and runs it, so the values exist only for that process's lifetime.

The win is honest but bounded, and worth stating plainly: **Keychain does not stop a process running
as you from reading the item.** CLI-added items get no meaningful ACL, and FileVault already covers
the stolen-disk case. What this actually buys is **hygiene** — no plaintext files to accidentally
sync to iCloud, commit, grep, or leave on a rented box; no `export SECRET=…` in `~/.zsh_history`.
That is a real and sufficient reason to do it; overselling it as a security boundary is not.

### D3. Terraform state is secret-bearing. Say so, and pick a posture.

This is the decision that shapes the others, and the reason D2 alone is not enough: even with every
input in Keychain, `terraform apply` writes the resolved values into `terraform.tfstate` in
plaintext. Keychain-ing the inputs fixes roughly half the surface. Options:

- **(a) Accept and protect.** Treat `deploy/*/terraform.tfstate` as secret-bearing files: keep them
  gitignored (already true), out of any synced directory, and note it in each module's README. Zero
  work, honest, no new failure modes.
- **(b) Encrypted remote state.** Move state to a DO Spaces backend with encryption. Removes the
  local plaintext copy, adds a bootstrap dependency (the backend needs credentials to read state)
  and a new way to lock yourself out.

**Recommendation: (a) for now, revisit at the first sign of a second operator.** Remote state earns
its complexity when state must be *shared*; with one maintainer it mostly relocates the problem.
What (a) does require is that the note be written down rather than assumed — which is this section.

### D4. `issuer_seed` is a trust anchor that currently rides Terraform. Named, not solved.

D1 says trust anchors stay out of automation, but `issuer_seed` must reach the commerce droplet to
sign entitlements, and today it does so as a Terraform variable — landing in tfstate *and* the
droplet's `deploy.env`. Its own variable description already admits this.

The options are all trade-offs, none free:

- **Keep it in Terraform** (today). Simple, reproducible, but the anchor sits in two plaintext places.
- **Generate on the droplet, publish only the public half.** The seed never exists off-box. But a
  droplet rebuild mints a new key, invalidating every outstanding entitlement and requiring a relay
  reconfiguration — turning routine infrastructure work into a user-visible event.
- **Encrypted remote state** (D3b) narrows the local exposure but not the `deploy.env` copy.

**Not decided here.** It needs the commerce track's input on how often that droplet is rebuilt and
how tolerable entitlement reissuance is. Flagged so it stops being invisible.

### D5. Trust anchors live outside any directory that tooling wipes or cloud sync touches.

The concrete trigger: `TILES_SIGNING_KEY` was in `~/.tenshu/`, which
`scripts/reset-local-device-state.sh:60` adds to its wipe roots. Moved to `~/Documents/tenshu-keys/`
(2026-07-21) with `0600`.

⚠️ On this machine `~/Documents` is **iCloud-synced** (`~/Library/Mobile Documents/…/Documents` is a
symlink to it), so that placement is a deliberate trade: it buys off-machine backup against the
realistic failure — *losing* the key costs a client release — at the price of the anchor existing in
consumer cloud storage. Accepted knowingly; revisit under Advanced Data Protection or a hardware
token.

### D6. The long-term home for trust anchors is a hardware token, not the Keychain.

A PIV/OpenPGP token (YubiKey and similar) holds Ed25519 and signs without the seed ever being
readable by software — which is the property Keychain cannot give. Signing is quarterly (D8 cadence),
so the friction is negligible. **Post-launch; not a v1 blocker.** Recorded so "move it to Keychain"
is understood as a waypoint rather than the destination.

## 4. Phasing

Each independently useful; none blocks another.

- **P1 — inventory + placement (this doc).** Trust anchors out of wipe paths (done), tfstate posture
  stated, `issuer_seed` flagged.
- **P2 — `secret.sh` + `with-secrets.sh`** over Keychain for operational credentials; migrate the
  four `terraform.tfvars` to be generated from Keychain at apply time rather than stored.
- **P3 — decide D4** with the commerce track.
- **P4 — hardware token** for `TILES_SIGNING_KEY` (and `issuer_seed` if D4 lands that way).

## 5. Open questions

- **Q1.** Does a second maintainer change D3? (Yes — shared state is the case remote backends exist
  for.) Revisit on the first co-maintainer.
- **Q2.** Should `with-secrets.sh` support a CI path, or stay laptop-only? Leaning laptop-only:
  GitHub Actions has its own encrypted secrets, and a shared abstraction over both invites the D1
  collapse this note is trying to prevent.
- **Q3.** Is there value in a `scripts/secret-audit.sh` that greps the working tree for
  credential-shaped strings pre-commit? Cheap, and it would have caught nothing so far — the
  gitignores have held. Deferred as speculative.

## 6. Threat-model relationship

No amendment lands with this doc: it changes no code and no data flow (House Rule #2 — amendments
ride the implementing change). The commerce group **A28/A29/A30** already covers the entitlement
issuer; D4 is a custody question *within* A29's existing scope, not a new asset. If P2 lands, the
`with-secrets` seam is worth a line in the deploy how-tos rather than the threat model — operator
credentials are outside the relay's zero-trust boundary by construction (House Rule #1: the relay
never sees them, and none of these secrets can decrypt user data).

## 7. Version

**v0.1 · 2026-07-21.** Opened after the tiles publish found `TILES_SIGNING_KEY` inside the
desktop-reset wipe path. Establishes the rotation-cost custody classes (D1), the Keychain-for-
operational-credentials shape with an honest account of what it does and does not buy (D2), the
tfstate posture (D3, recommend accept-and-protect at n=1), the `issuer_seed`-rides-Terraform
contradiction (D4, named not solved), the trust-anchor placement rule and its iCloud trade (D5), and
hardware tokens as the real destination (D6). No code.
