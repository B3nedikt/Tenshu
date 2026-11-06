# DigitalOcean commerce-issuer deploy (Terraform)

> ⚠⚠ **RETIRED.** Commerce runs on [`deploy/scaleway-commerce/`](../scaleway-commerce/) since
> 2026-09-14 ([`move-commerce-to-scaleway.md`](../../docs/how-to/move-commerce-to-scaleway.md)). This
> droplet's volume holds the **pre-cutover** database, so commerce must never be started here again:
> any write it took would exist only in a database production no longer reads. Unlike the relay module,
> this state owns no DNS record, so teardown is a plain `terraform destroy` once the rollback window
> has closed.
>
> ⚠⚠ **Publish the commerce image first.** The pinned `tenshu-commerce:2757b533` is not in GHCR. It
> exists only on this droplet and on the Scaleway box, so once this one is gone a lost or rebuilt
> Scaleway root disk leaves commerce with no image to boot. Push it from the Scaleway box, which
> holds the same image ID (`sha256:624c1a6b…`), with a classic PAT that has `write:packages`, and
> verify it against the registry rather than trusting the push output:
>
> ```bash
> ssh root@51.15.229.94 'docker image inspect ghcr.io/b3nedikt/tenshu-commerce:2757b533 --format "{{.Id}}"'
> printf '%s' "$GHCR_WRITE_TOKEN" | ssh root@51.15.229.94 'docker login ghcr.io -u b3nedikt --password-stdin &&
>   docker push ghcr.io/b3nedikt/tenshu-commerce:2757b533 &&
>   docker manifest inspect ghcr.io/b3nedikt/tenshu-commerce:2757b533 >/dev/null && echo PUBLISHED;
>   docker logout ghcr.io'
> ```
>
> Destroy only after `PUBLISHED`.

One-command, single-droplet deployment of the Tenshu **commerce issuer**
(`:commerce-service`) — the paid-storage plane that mints signed entitlements and
holds the account/session store. Provisions a droplet + block-storage volume +
firewall + reserved IP (+ optional DNS), and boots the issuer behind Caddy
(automatic HTTPS) with a **bundled Postgres** (A28 store) via cloud-init.

> **Separate trust boundary from the relay** (`commerce-billing-plane.md` D1). This
> box holds the A29 signing seed + email PII (A28); it shares no host, network, or
> credential with the ciphertext-only relay. Deploy it on its **own** droplet.

```bash
cp terraform.tfvars.example terraform.tfvars   # token, ssh key, domain, email,
                                               # issuer_seed, database_password (+ Stripe)
terraform init
terraform apply
```

First boot clones the repo (for the compose + Caddy files), stands up Postgres +
the issuer, and gets a TLS cert. Then:

1. Read the issuer **public** key from the boot log (the `first_boot_hint` output
   shows the exact command) and set the **relay's** `ENTITLEMENT_ISSUER_PUBKEY` to it
   in `/opt/tenshu/deploy.env` on the relay box ([`deploy/scaleway-relay/`](../scaleway-relay/)), then
   recreate the relay container on its pinned tag. ⚠⚠ Never `terraform apply` `deploy/digitalocean/` for
   this: it is the retired relay, and its state would repoint production DNS at the dead droplet.
2. Point clients at the `commerce_url` output via `TENSHU_COMMERCE_URL`.

Full walkthrough — Stripe test-mode setup, the relay wiring, and the client
override — is in
[`docs/how-to/deploy-commerce-digitalocean.md`](../../docs/how-to/deploy-commerce-digitalocean.md).

| File | Purpose |
|------|---------|
| `versions.tf` | Terraform + DO provider version pins. |
| `variables.tf` | Inputs (token, sizing, domain, DNS toggle, issuer seed, Postgres, Stripe). |
| `main.tf` | Droplet, volume, firewall, reserved IP, DNS + safety preconditions. |
| `cloud-init.yaml.tftpl` | First-boot bootstrap (Docker, volume mount, clone, Postgres override, compose up). |
| `outputs.tf` | Public IP + issuer URL + how to grab the issuer pubkey. |

The issuer run definition (compose + Caddy) lives next to the module it serves:
[`commerce-service/docker-compose.yml`](../../commerce-service/docker-compose.yml).
This module adds a `postgres:16` sidecar at deploy time (the committed compose omits
it deliberately); point `database_url` at a managed DB to use that instead.

**Redeploy** a new issuer image without a full re-apply:
[`../redeploy-commerce.sh`](../redeploy-commerce.sh).
