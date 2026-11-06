# Apex on Scaleway

The twin of [`deploy/digitalocean-apex/`](../digitalocean-apex/). See
[`../scaleway-relay/README.md`](../scaleway-relay/README.md) for the five DigitalOcean→Scaleway
differences — they apply here identically, and four of them fail silently.

## ⚠ Stateless on purpose

No block volume, matching the DO module. So unlike the relay there is no "healthy but writing to the
wrong disk" failure to check for — `cloud-init status` and a `/health` curl are enough.

If a future apex needs durable state, take the volume plus the provider-neutral mount from
`../scaleway-relay/`, rather than letting it write to the root disk where a rebuild destroys it.

## ⭐ Point it at the relay you actually want

`relay_url` defaults to the production relay (`relay.tenshu.app`, on Scaleway since 2026-09-13).
Pointing a new apex at the existing relay changes one moving part at a time, so a failure is
unambiguous.

## ⚠ Swap, disk and monitoring

The swapfile is on the **root** disk and sized by `swapfile_size_gb` (1 GB with a prebuilt image, 4 GB
for an on-box build). A fixed 4 GB one filled commerce's 8.9 GB root disk in October 2026 and silently
stopped its backups for 23 days. Disk and backups report to a dead man's switch, installed by hand
after every (re)build: [`docs/how-to/box-health.md`](../../docs/how-to/box-health.md).

## ⚠ `:22` is open to the whole internet, and there is no CA

`ssh_allowed_cidrs` defaults to `["0.0.0.0/0"]` in this module and in its two siblings, nothing here
touches `sshd_config`, and there is no SSH certificate authority anywhere in `deploy/`. Access is
long-lived Project keys reachable from any address.

This is **not** something to change casually from a runbook step — Scaleway injects Project SSH keys
at **first boot only**, so a mistake here is recovered by rebuild or console VNC, not by another
`apply`. It is written up, with the options and their lock-out risks, in
[`docs/tech-debt.md`](../../docs/tech-debt.md) → *"Every production box exposes `:22` to the whole
internet"*.

⭐ The pattern worth copying when this is addressed is the dev lab's (`deploy/scaleway-dev/`,
`docs/how-to/deploy-dev-lab-scaleway.md` §3b): an optional
`ssh_ca_public_key` that cloud-init installs as `TrustedUserCAKeys`, so short-lived certificates
replace long-lived keys without an overlay network or a third party.
